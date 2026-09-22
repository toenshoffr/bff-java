package com.toenshoffr.bff.auth;

import com.toenshoffr.bff.config.BffProperties;
import com.toenshoffr.bff.web.CookieUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class LogoutController {

    private final BffProperties props;
    private final CookieUtil cookieUtil;

    public LogoutController(BffProperties props, CookieUtil cookieUtil) {
        this.props = props;
        this.cookieUtil = cookieUtil;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        cookieUtil.expireCookie(response, props.getCookieName(), true);
        return Map.of("authenticated", false);
    }
}
