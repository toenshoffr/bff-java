package com.toenshoffr.bff.web;

import com.toenshoffr.bff.config.BffProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Stateless double-submit-cookie CSRF protection: every mutating request must echo the
 * {@code bff.csrf} cookie's value back in the {@code X-CSRF-Token} header. Disableable via
 * {@code CSRF_PROTECTION_ENABLED=false} for local curl testing only.
 */
@Component
@Order(1)
public class CsrfFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "bff.csrf";
    public static final String HEADER_NAME = "X-CSRF-Token";

    private static final Logger log = LoggerFactory.getLogger(CsrfFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final BffProperties props;
    private final CookieUtil cookieUtil;

    public CsrfFilter(BffProperties props, CookieUtil cookieUtil) {
        this.props = props;
        this.cookieUtil = cookieUtil;
    }

    @PostConstruct
    void warnIfDisabled() {
        if (!props.isCsrfProtectionEnabled()) {
            log.warn("CSRF_PROTECTION_ENABLED=false: double-submit CSRF checks are DISABLED. " +
                    "This is intended for local/testing use only — never leave it off in a deployed environment.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!props.isCsrfProtectionEnabled() || SAFE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String cookieToken = cookieUtil.readCsrfCookie(request);
        String headerToken = request.getHeader(HEADER_NAME);
        if (cookieToken == null || headerToken == null || !constantTimeEquals(cookieToken, headerToken)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_csrf_token\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
