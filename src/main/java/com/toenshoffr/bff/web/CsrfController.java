package com.toenshoffr.bff.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@RestController
public class CsrfController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CookieUtil cookieUtil;

    public CsrfController(CookieUtil cookieUtil) {
        this.cookieUtil = cookieUtil;
    }

    @GetMapping("/auth/csrf")
    public Map<String, Object> issueCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        String token = cookieUtil.readCsrfCookie(request);
        if (token == null || token.isBlank()) {
            token = randomToken();
        }
        cookieUtil.setCsrfCookie(response, token);
        return Map.of("csrfToken", token);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
