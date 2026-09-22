package com.toenshoffr.bff.auth;

import com.toenshoffr.bff.config.AuthMethod;
import com.toenshoffr.bff.config.BffProperties;
import com.toenshoffr.bff.session.SessionKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class PasswordAuthController {

    private final BffProperties props;
    private final TokenService tokenService;

    public PasswordAuthController(BffProperties props, TokenService tokenService) {
        this.props = props;
        this.tokenService = tokenService;
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest body,
                                                       HttpServletRequest request) {
        if (!props.isPasswordEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }

        String username = body != null ? body.username() : null;
        String password = body != null ? body.password() : null;
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new InvalidRequestException("invalid_request");
        }

        AuthResult result = tokenService.login(username, password);

        // Regenerate the session (fixation mitigation) before storing tokens.
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(SessionKeys.AUTH_METHOD, AuthMethod.PASSWORD);
        session.setAttribute(SessionKeys.TOKENS, result.tokens());
        session.setAttribute(SessionKeys.USER, result.user());

        return ResponseEntity.ok(Map.of("authenticated", true, "user", result.user()));
    }
}
