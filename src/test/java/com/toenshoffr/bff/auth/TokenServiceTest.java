package com.toenshoffr.bff.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.toenshoffr.bff.config.BffProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit-level tests for TokenService's response normalization and error mapping, against a stubbed upstream API. */
class TokenServiceTest {

    private WireMockServer wireMock;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        BffProperties props = new BffProperties();
        props.setFrontendOrigin("http://localhost:4200");
        props.setApiBaseUrl("http://localhost:" + wireMock.port());
        props.setSessionSecret("test-session-secret-1234567890");
        props.setAuthMethods("password");

        tokenService = new TokenService(props, HttpClient.newHttpClient(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void normalizesCamelCaseLoginResponse() {
        wireMock.stubFor(post(urlEqualTo("/api/auth/login")).willReturn(okJson(
                "{\"accessToken\":\"at-1\",\"refreshToken\":\"rt-1\",\"expiresIn\":3600,\"user\":{\"id\":1}}")));

        AuthResult result = tokenService.login("someone", "secret");

        assertThat(result.tokens().accessToken()).isEqualTo("at-1");
        assertThat(result.tokens().refreshToken()).isEqualTo("rt-1");
        assertThat(result.tokens().isValid(0)).isTrue();
        assertThat(result.user()).isEqualTo(Map.of("id", 1));
    }

    @Test
    void normalizesSnakeCaseLoginResponseAndAppliesUsernameFallback() {
        wireMock.stubFor(post(urlEqualTo("/api/auth/login")).willReturn(okJson(
                "{\"access_token\":\"at-2\",\"refresh_token\":\"rt-2\",\"expires_in\":3600}")));

        AuthResult result = tokenService.login("someone", "secret");

        assertThat(result.tokens().accessToken()).isEqualTo("at-2");
        assertThat(result.tokens().refreshToken()).isEqualTo("rt-2");
        assertThat(result.user()).isEqualTo(Map.of("username", "someone"));
    }

    @Test
    void badCredentialsMapToInvalidCredentialsException() {
        wireMock.stubFor(post(urlEqualTo("/api/auth/login")).willReturn(unauthorized()));

        assertThatThrownBy(() -> tokenService.login("bad", "creds"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void ensureFreshAccessTokenThrowsWhenNoSession() {
        assertThatThrownBy(() -> tokenService.ensureFreshAccessToken(null))
                .isInstanceOf(UnauthenticatedException.class);
    }
}
