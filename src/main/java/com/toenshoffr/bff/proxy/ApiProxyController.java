package com.toenshoffr.bff.proxy;

import com.toenshoffr.bff.auth.TokenService;
import com.toenshoffr.bff.session.TokenSet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Authenticated reverse proxy: {@code ALL /api/**} -> {@code API_BASE_URL}, per SPEC.md. */
@RestController
public class ApiProxyController {

    private final TokenService tokenService;
    private final ApiProxyService apiProxyService;

    public ApiProxyController(TokenService tokenService, ApiProxyService apiProxyService) {
        this.tokenService = tokenService;
        this.apiProxyService = apiProxyService;
    }

    @RequestMapping("${bff.api-proxy-path:/api}/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        TokenSet tokens = tokenService.ensureFreshAccessToken(request.getSession(false));
        apiProxyService.forward(request, response, tokens.accessToken());
    }
}
