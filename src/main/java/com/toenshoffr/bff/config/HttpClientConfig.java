package com.toenshoffr.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared JDK {@link HttpClient} used for both the upstream token calls (password
 * login/refresh, OAuth token endpoint) and the {@code /api/**} reverse proxy. Its
 * native support for streaming request/response bodies avoids buffering large
 * proxied payloads in memory.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient upstreamHttpClient(BffProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getApiTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                // Pin HTTP/1.1: an HTTP/2-negotiated upstream response's ":status" pseudo-header
                // can otherwise leak into HttpResponse.headers(), and blindly forwarding that
                // (see ApiProxyService) corrupts the HTTP/1.1 response this BFF sends the browser.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
