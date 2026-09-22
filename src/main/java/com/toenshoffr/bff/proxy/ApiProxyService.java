package com.toenshoffr.bff.proxy;

import com.toenshoffr.bff.auth.UpstreamServiceException;
import com.toenshoffr.bff.config.BffProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * Streams a request through to {@code API_BASE_URL} (with the {@code API_PROXY_PATH}
 * prefix stripped) and streams the response back, without buffering whole bodies into
 * memory. Hop-by-hop and identity-bearing headers are filtered in both directions; the
 * caller is responsible for injecting the {@code Authorization} bearer token.
 */
@Service
public class ApiProxyService {

    private static final Set<String> STRIP_REQUEST_HEADERS = Set.of(
            "host", "content-length", "cookie", "authorization", "connection", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private static final Set<String> STRIP_RESPONSE_HEADERS = Set.of(
            "connection", "keep-alive", "transfer-encoding", "content-length", "upgrade", "trailer");

    private final BffProperties props;
    private final HttpClient httpClient;

    public ApiProxyService(BffProperties props, HttpClient httpClient) {
        this.props = props;
        this.httpClient = httpClient;
    }

    public void forward(HttpServletRequest request, HttpServletResponse response, String accessToken) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(buildTargetUri(request))
                .timeout(Duration.ofMillis(props.getApiTimeoutMs()))
                .method(request.getMethod(), bodyPublisher(request));

        copyRequestHeaders(request, builder);
        builder.header("Authorization", "Bearer " + accessToken);

        HttpResponse<InputStream> upstreamResponse;
        try {
            upstreamResponse = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new UpstreamServiceException("Failed to reach upstream API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamServiceException("Interrupted while calling upstream API", e);
        }

        response.setStatus(upstreamResponse.statusCode());
        copyResponseHeaders(upstreamResponse, response);
        try (InputStream body = upstreamResponse.body()) {
            body.transferTo(response.getOutputStream());
        }
        response.flushBuffer();
    }

    private URI buildTargetUri(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = props.getApiProxyPath();
        String remainder = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        if (!remainder.isEmpty() && !remainder.startsWith("/")) {
            remainder = "/" + remainder;
        }
        String query = request.getQueryString();
        return URI.create(props.getApiBaseUrl() + remainder + (query != null ? "?" + query : ""));
    }

    private HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) {
        long contentLength = request.getContentLengthLong();
        String transferEncoding = request.getHeader("Transfer-Encoding");
        boolean chunked = transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked");
        if (contentLength > 0 || chunked) {
            return HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return request.getInputStream();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return HttpRequest.BodyPublishers.noBody();
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (STRIP_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                try {
                    builder.header(name, values.nextElement());
                } catch (IllegalArgumentException ignored) {
                    // A header the JDK HttpClient treats as restricted/invalid; skip rather than fail the whole proxy call.
                }
            }
        }
    }

    private void copyResponseHeaders(HttpResponse<InputStream> upstreamResponse, HttpServletResponse response) {
        upstreamResponse.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            // CORS is decided solely by this BFF's own config (SecurityConfig) — never let the
            // upstream API's own Access-Control-* headers reach the browser. Also guard against an
            // HTTP/2 ":status"-style pseudo-header ever leaking through as a literal header name,
            // which would corrupt the HTTP/1.1 response written to the browser.
            if (lower.startsWith("access-control-") || lower.startsWith(":") || STRIP_RESPONSE_HEADERS.contains(lower)) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
    }
}
