package com.toenshoffr.bff.web;

import com.toenshoffr.bff.config.BffProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds/reads app-level cookies (the CSRF cookie, and manual session-cookie clearing) consistently with the session cookie's own COOKIE_* config. */
@Component
public class CookieUtil {

    private final BffProperties props;

    public CookieUtil(BffProperties props) {
        this.props = props;
    }

    public void setCsrfCookie(HttpServletResponse response, String value) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(CsrfFilter.COOKIE_NAME, value, false,
                Duration.ofMillis(props.getCookieMaxAgeMs())).toString());
    }

    public String readCsrfCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (CsrfFilter.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void expireCookie(HttpServletResponse response, String name, boolean httpOnly) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(name, "", httpOnly, Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String name, String value, boolean httpOnly, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(props.isCookieSecure())
                .sameSite(capitalize(props.getCookieSameSite()))
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Lax";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase();
    }
}
