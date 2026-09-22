package com.toenshoffr.bff.auth;

import com.toenshoffr.bff.config.AuthMethod;
import com.toenshoffr.bff.session.SessionKeys;
import com.toenshoffr.bff.session.TokenSet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthStatusController {

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        TokenSet tokens = session == null ? null : (TokenSet) session.getAttribute(SessionKeys.TOKENS);
        if (tokens == null) {
            return Map.of("authenticated", false);
        }
        AuthMethod method = (AuthMethod) session.getAttribute(SessionKeys.AUTH_METHOD);
        Object user = session.getAttribute(SessionKeys.USER);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("authMethod", method != null ? method.wireName() : null);
        body.put("user", user);
        return body;
    }
}
