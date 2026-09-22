package com.toenshoffr.bff;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests against the real Spring context (embedded server, real Servlet session
 * handling) with a WireMock server standing in for the Spring Boot API / IdP, exercising the
 * flows from SPEC.md's Acceptance Checklist: CSRF, password login, transparent token refresh,
 * the authenticated proxy (incl. Access-Control-* stripping), logout, and the OAuth PKCE flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "FRONTEND_ORIGIN=http://localhost:4200",
        "SESSION_SECRET=test-session-secret-1234567890",
        "AUTH_METHODS=password,oauth",
        "COOKIE_SECURE=false",
        "OAUTH_AUTHORIZATION_ENDPOINT=http://idp.test/authorize",
        "OAUTH_CLIENT_ID=test-client",
        "OAUTH_REDIRECT_URI=http://localhost:3000/auth/oauth/callback",
        "OAUTH_POST_LOGIN_REDIRECT=/"
})
class BffApplicationIntegrationTest {

    private static WireMockServer wireMock;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void upstreamUrls(DynamicPropertyRegistry registry) {
        registry.add("API_BASE_URL", () -> "http://localhost:" + wireMock.port());
        registry.add("OAUTH_TOKEN_ENDPOINT", () -> "http://localhost:" + wireMock.port() + "/oauth/token");
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    void healthzReturnsOkWithoutASession() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/healthz", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "ok");
    }

    @Test
    void proxyWithoutASessionIsUnauthenticated() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/orders", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "unauthenticated");
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() {
        CookieJar cookies = new CookieJar();
        doGet("/auth/csrf", cookies, null);

        ResponseEntity<Map> response = doPost("/auth/login", cookies, null,
                Map.of("username", "someone", "password", "secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "invalid_csrf_token");
    }

    @Test
    void badCredentialsAreRejected() {
        wireMock.stubFor(post(urlEqualTo("/api/auth/login")).willReturn(unauthorized()));

        CookieJar cookies = new CookieJar();
        String csrfToken = fetchCsrfToken(cookies);

        ResponseEntity<Map> response = doPost("/auth/login", cookies, csrfToken,
                Map.of("username", "baduser", "password", "wrong"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "invalid_credentials");
    }

    @Test
    void passwordLoginProxyTransparentRefreshAndLogout() {
        // expiresIn is intentionally shorter than the 10s refresh skew, so the very first
        // proxied call after login has to transparently refresh before reaching the backend.
        wireMock.stubFor(post(urlEqualTo("/api/auth/login"))
                .withRequestBody(matchingJsonPath("$.username", equalTo("gooduser")))
                .willReturn(okJson("{\"accessToken\":\"access-1\",\"refreshToken\":\"refresh-1\","
                        + "\"expiresIn\":5,\"user\":{\"username\":\"gooduser\",\"id\":42}}")));
        wireMock.stubFor(post(urlEqualTo("/api/auth/refresh"))
                .withRequestBody(matchingJsonPath("$.refreshToken", equalTo("refresh-1")))
                .willReturn(okJson("{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\",\"expiresIn\":3600}")));
        wireMock.stubFor(get(urlEqualTo("/orders"))
                .willReturn(okJson("{\"orders\":[]}")
                        .withHeader("Access-Control-Allow-Origin", "http://evil.example")
                        .withHeader("X-Upstream-Marker", "present")));

        CookieJar cookies = new CookieJar();
        String csrfToken = fetchCsrfToken(cookies);

        // 1. Login
        ResponseEntity<Map> loginResponse = doPost("/auth/login", cookies, csrfToken,
                Map.of("username", "gooduser", "password", "correct"));
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).containsEntry("authenticated", true);

        // 2. Status reflects the new session
        ResponseEntity<Map> statusResponse = doGet("/auth/status", cookies, null);
        assertThat(statusResponse.getBody()).containsEntry("authenticated", true);
        assertThat(statusResponse.getBody()).containsEntry("authMethod", "password");

        // 3. Proxy: the token is already-expired (per the skew), so this triggers a transparent
        // refresh before reaching the backend with the *refreshed* access token.
        HttpHeaders proxyHeaders = new HttpHeaders();
        proxyHeaders.add(HttpHeaders.COOKIE, cookies.header());
        proxyHeaders.add(HttpHeaders.ORIGIN, "http://localhost:4200");
        ResponseEntity<Map> proxyResponse = restTemplate.exchange("/api/orders", HttpMethod.GET,
                new HttpEntity<>(proxyHeaders), Map.class);

        assertThat(proxyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(proxyResponse.getBody()).containsEntry("orders", List.of());
        assertThat(proxyResponse.getHeaders().getFirst("X-Upstream-Marker")).isEqualTo("present");
        // Our own CORS config answers (matching the sent Origin) - never the upstream's forged value.
        assertThat(proxyResponse.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:4200");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/auth/refresh")));
        wireMock.verify(getRequestedFor(urlEqualTo("/orders"))
                .withHeader("Authorization", equalTo("Bearer access-2")));

        // 4. Logout destroys the session and clears the session cookie
        ResponseEntity<Map> logoutResponse = doPost("/auth/logout", cookies, csrfToken, null);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logoutResponse.getBody()).containsEntry("authenticated", false);

        ResponseEntity<Map> statusAfterLogout = doGet("/auth/status", cookies, null);
        assertThat(statusAfterLogout.getBody()).containsEntry("authenticated", false);
    }

    @Test
    void oauthLoginRedirectsWithPkceParamsAndCallbackEstablishesSession() {
        CookieJar cookies = new CookieJar();

        ResponseEntity<Void> loginResponse = restTemplate.exchange("/auth/oauth/login", HttpMethod.GET,
                HttpEntity.EMPTY, Void.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        cookies.update(loginResponse);

        URI location = loginResponse.getHeaders().getLocation();
        assertThat(location).isNotNull();
        Map<String, String> query = UriComponentsBuilder.fromUri(location).build().getQueryParams()
                .toSingleValueMap();
        assertThat(query.get("response_type")).isEqualTo("code");
        assertThat(query.get("client_id")).isEqualTo("test-client");
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        assertThat(query.get("state")).isNotBlank();
        assertThat(query.get("code_challenge")).isNotBlank();
        String state = query.get("state");

        wireMock.stubFor(post(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=test-code"))
                .willReturn(okJson("{\"accessToken\":\"oauth-access-1\",\"refreshToken\":\"oauth-refresh-1\","
                        + "\"expiresIn\":3600,\"user\":{\"sub\":\"user-1\"}}")));

        HttpHeaders callbackHeaders = new HttpHeaders();
        callbackHeaders.add(HttpHeaders.COOKIE, cookies.header());
        ResponseEntity<Void> callbackResponse = restTemplate.exchange(
                "/auth/oauth/callback?code=test-code&state=" + state, HttpMethod.GET,
                new HttpEntity<>(callbackHeaders), Void.class);
        assertThat(callbackResponse.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callbackResponse.getHeaders().getLocation().getPath()).isEqualTo("/");
        cookies.update(callbackResponse);

        ResponseEntity<Map> statusResponse = doGet("/auth/status", cookies, null);
        assertThat(statusResponse.getBody()).containsEntry("authenticated", true);
        assertThat(statusResponse.getBody()).containsEntry("authMethod", "oauth");
    }

    @Test
    void oauthCallbackWithMismatchedStateIsRejected() {
        CookieJar cookies = new CookieJar();
        ResponseEntity<Void> loginResponse = restTemplate.exchange("/auth/oauth/login", HttpMethod.GET,
                HttpEntity.EMPTY, Void.class);
        cookies.update(loginResponse);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookies.header());
        ResponseEntity<Map> response = restTemplate.exchange(
                "/auth/oauth/callback?code=test-code&state=wrong-state", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "invalid_oauth_state");
    }

    @Test
    void oauthCallbackWithIdpErrorRedirectsWithAuthError() {
        ResponseEntity<Void> response = restTemplate.exchange("/auth/oauth/callback?error=access_denied",
                HttpMethod.GET, HttpEntity.EMPTY, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        URI location = response.getHeaders().getLocation();
        assertThat(location.getPath()).isEqualTo("/");
        assertThat(location.getQuery()).isEqualTo("authError=access_denied");
    }

    // --- small test-local HTTP helpers -------------------------------------------------

    private String fetchCsrfToken(CookieJar cookies) {
        ResponseEntity<Map> response = doGet("/auth/csrf", cookies, null);
        return (String) response.getBody().get("csrfToken");
    }

    private ResponseEntity<Map> doGet(String path, CookieJar cookies, String csrfToken) {
        HttpHeaders headers = new HttpHeaders();
        if (!cookies.isEmpty()) {
            headers.add(HttpHeaders.COOKIE, cookies.header());
        }
        if (csrfToken != null) {
            headers.add("X-CSRF-Token", csrfToken);
        }
        ResponseEntity<Map> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        cookies.update(response);
        return response;
    }

    private ResponseEntity<Map> doPost(String path, CookieJar cookies, String csrfToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!cookies.isEmpty()) {
            headers.add(HttpHeaders.COOKIE, cookies.header());
        }
        if (csrfToken != null) {
            headers.add("X-CSRF-Token", csrfToken);
        }
        ResponseEntity<Map> response = restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        cookies.update(response);
        return response;
    }

    /** Minimal manual cookie jar: TestRestTemplate has no built-in cookie store. */
    private static final class CookieJar {
        private final Map<String, String> cookies = new LinkedHashMap<>();

        void update(ResponseEntity<?> response) {
            List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (setCookies == null) {
                return;
            }
            for (String header : setCookies) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }

        boolean isEmpty() {
            return cookies.isEmpty();
        }

        String header() {
            return cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; "));
        }
    }
}
