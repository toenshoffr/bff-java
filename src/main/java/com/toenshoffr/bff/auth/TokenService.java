package com.toenshoffr.bff.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toenshoffr.bff.config.AuthMethod;
import com.toenshoffr.bff.config.BffProperties;
import com.toenshoffr.bff.session.SessionKeys;
import com.toenshoffr.bff.session.TokenSet;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Talks to the Spring Boot API / IdP for password login+refresh and the OAuth token
 * endpoint, normalizes their responses (camelCase or snake_case) into a {@link TokenSet},
 * and implements transparent token refresh ahead of every proxied {@code /api/**} call.
 */
@Service
public class TokenService {

    /** Refresh a token slightly before it actually expires, per SPEC.md. */
    private static final long TOKEN_EXPIRY_SKEW_MILLIS = 10_000;

    private final BffProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TokenService(BffProperties props, HttpClient httpClient, ObjectMapper objectMapper) {
        this.props = props;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public AuthResult login(String username, String password) {
        String url = props.getApiBaseUrl() + props.getPasswordLoginPath();
        String body = writeJson(Map.of("username", username, "password", password));
        HttpResponse<String> response = send(url, "POST", body, "application/json");
        if (response.statusCode() == 400 || response.statusCode() == 401) {
            throw new InvalidCredentialsException();
        }
        requireSuccess(response, "Password login");
        JsonNode root = parseJson(response.body());
        return new AuthResult(toTokenSet(root), extractUser(root, username));
    }

    public TokenSet refreshPassword(String refreshToken) {
        String url = props.getApiBaseUrl() + props.getPasswordRefreshPath();
        String body = writeJson(Map.of("refreshToken", refreshToken));
        HttpResponse<String> response = send(url, "POST", body, "application/json");
        requireSuccess(response, "Password token refresh");
        return toTokenSet(parseJson(response.body()));
    }

    public AuthResult exchangeOAuthCode(String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", props.getOauthRedirectUri());
        form.put("client_id", props.getOauthClientId());
        form.put("code_verifier", codeVerifier);
        if (hasText(props.getOauthClientSecret())) {
            form.put("client_secret", props.getOauthClientSecret());
        }
        HttpResponse<String> response = sendForm(props.getOauthTokenEndpoint(), form);
        requireSuccess(response, "OAuth code exchange");
        JsonNode root = parseJson(response.body());
        return new AuthResult(toTokenSet(root), extractUser(root, null));
    }

    public TokenSet refreshOAuth(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", props.getOauthClientId());
        if (hasText(props.getOauthClientSecret())) {
            form.put("client_secret", props.getOauthClientSecret());
        }
        HttpResponse<String> response = sendForm(props.getOauthTokenEndpoint(), form);
        requireSuccess(response, "OAuth token refresh");
        return toTokenSet(parseJson(response.body()));
    }

    /**
     * Ensures the caller's session holds a still-valid access token, refreshing it
     * (via whichever auth method established the session) if it's expired or about
     * to expire. Throws {@link UnauthenticatedException} if there's no session/tokens,
     * or the refresh attempt fails — the caller should be treated as logged out.
     */
    public TokenSet ensureFreshAccessToken(HttpSession session) {
        if (session == null) {
            throw new UnauthenticatedException();
        }
        TokenSet tokens = (TokenSet) session.getAttribute(SessionKeys.TOKENS);
        if (tokens == null) {
            throw new UnauthenticatedException();
        }
        if (tokens.isValid(TOKEN_EXPIRY_SKEW_MILLIS)) {
            return tokens;
        }
        if (tokens.refreshToken() == null) {
            throw new UnauthenticatedException();
        }
        AuthMethod method = (AuthMethod) session.getAttribute(SessionKeys.AUTH_METHOD);
        if (method == null) {
            throw new UnauthenticatedException();
        }
        TokenSet refreshed;
        try {
            refreshed = switch (method) {
                case PASSWORD -> refreshPassword(tokens.refreshToken());
                case OAUTH -> refreshOAuth(tokens.refreshToken());
            };
        } catch (RuntimeException e) {
            throw new UnauthenticatedException();
        }
        session.setAttribute(SessionKeys.TOKENS, refreshed);
        return refreshed;
    }

    private TokenSet toTokenSet(JsonNode root) {
        String accessToken = text(root, "accessToken", "access_token");
        String refreshToken = text(root, "refreshToken", "refresh_token");
        long expiresInSeconds = number(root, 0, "expiresIn", "expires_in");
        long expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000;
        return new TokenSet(accessToken, refreshToken, expiresAt);
    }

    private Object extractUser(JsonNode root, String usernameFallback) {
        if (root.hasNonNull("user")) {
            return objectMapper.convertValue(root.get("user"), Object.class);
        }
        if (usernameFallback != null) {
            return Map.of("username", usernameFallback);
        }
        return null;
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            if (node.hasNonNull(name)) {
                return node.get(name).asText();
            }
        }
        return null;
    }

    private static long number(JsonNode node, long defaultValue, String... names) {
        for (String name : names) {
            if (node.hasNonNull(name)) {
                return node.get(name).asLong();
            }
        }
        return defaultValue;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UpstreamServiceException("Failed to serialize request body", e);
        }
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new UpstreamServiceException("Upstream returned invalid JSON", e);
        }
    }

    private void requireSuccess(HttpResponse<String> response, String what) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new UpstreamServiceException(what + " failed with upstream status " + status);
        }
    }

    private HttpResponse<String> send(String url, String method, String body, String contentType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getApiTimeoutMs()))
                    .header("Content-Type", contentType)
                    .header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UpstreamServiceException("Failed to call " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamServiceException("Interrupted while calling " + url, e);
        }
    }

    private HttpResponse<String> sendForm(String url, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(url, "POST", body, "application/x-www-form-urlencoded");
    }
}
