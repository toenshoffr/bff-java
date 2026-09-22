package com.toenshoffr.bff.auth;

import com.toenshoffr.bff.config.AuthMethod;
import com.toenshoffr.bff.config.BffProperties;
import com.toenshoffr.bff.session.OAuthFlowState;
import com.toenshoffr.bff.session.SessionKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** OAuth 2.0 Authorization Code + PKCE flow against an external IdP, on behalf of the BFF's confidential client. */
@RestController
@RequestMapping("/auth/oauth")
public class OAuthController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BffProperties props;
    private final TokenService tokenService;

    public OAuthController(BffProperties props, TokenService tokenService) {
        this.props = props;
        this.tokenService = tokenService;
    }

    @GetMapping("/login")
    public void login(@RequestParam(name = "redirectTo", required = false) String redirectTo,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!props.isOauthEnabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String state = randomToken();
        String codeVerifier = randomToken();
        String codeChallenge = codeChallenge(codeVerifier);

        HttpSession session = request.getSession(true);
        session.setAttribute(SessionKeys.OAUTH_FLOW, new OAuthFlowState(state, codeVerifier, redirectTo));

        String authorizeUrl = UriComponentsBuilder.fromHttpUrl(props.getOauthAuthorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", props.getOauthClientId())
                .queryParam("redirect_uri", props.getOauthRedirectUri())
                .queryParam("scope", props.getOauthScopes())
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUriString();
        response.sendRedirect(authorizeUrl);
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                          @RequestParam(required = false) String state,
                          @RequestParam(required = false) String error,
                          HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!props.isOauthEnabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if (error != null) {
            String separator = props.getOauthPostLoginRedirect().contains("?") ? "&" : "?";
            response.sendRedirect(props.getOauthPostLoginRedirect() + separator
                    + "authError=" + URLEncoder.encode(error, StandardCharsets.UTF_8));
            return;
        }

        HttpSession session = request.getSession(false);
        OAuthFlowState flow = session != null ? (OAuthFlowState) session.getAttribute(SessionKeys.OAUTH_FLOW) : null;
        if (flow == null || state == null || !state.equals(flow.state()) || code == null || code.isBlank()) {
            throw new InvalidOAuthStateException();
        }

        AuthResult result = tokenService.exchangeOAuthCode(code, flow.codeVerifier());

        // Regenerate the session (fixation mitigation) before storing tokens.
        request.changeSessionId();
        session.removeAttribute(SessionKeys.OAUTH_FLOW);
        session.setAttribute(SessionKeys.AUTH_METHOD, AuthMethod.OAUTH);
        session.setAttribute(SessionKeys.TOKENS, result.tokens());
        session.setAttribute(SessionKeys.USER, result.user());

        String redirectTo = (flow.redirectTo() != null && !flow.redirectTo().isBlank())
                ? flow.redirectTo() : props.getOauthPostLoginRedirect();
        response.sendRedirect(redirectTo);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
