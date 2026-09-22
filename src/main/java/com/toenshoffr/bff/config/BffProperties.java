package com.toenshoffr.bff.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * All BFF-specific configuration, bound from {@code application.yml} (which in turn
 * reads the environment variables documented in SPEC.md). Conditionally-required
 * OAuth fields are validated via {@code @AssertTrue} methods so a missing/invalid
 * config produces one aggregated, per-field startup failure instead of a runtime
 * NPE deep in the OAuth flow.
 */
@ConfigurationProperties(prefix = "bff")
@Validated
public class BffProperties {

    @NotBlank
    private String frontendOrigin;

    @NotBlank
    private String apiBaseUrl;

    private long apiTimeoutMs = 10_000;

    private String apiProxyPath = "/api";

    @NotBlank
    private String authMethods = "password";

    @NotBlank
    @Size(min = 16, message = "must be at least 16 characters")
    private String sessionSecret;

    private String cookieName = "bff.sid";

    private boolean cookieSecure = true;

    private String cookieSameSite = "lax";

    private long cookieMaxAgeMs = 28_800_000;

    private boolean csrfProtectionEnabled = true;

    private String passwordLoginPath = "/api/auth/login";

    private String passwordRefreshPath = "/api/auth/refresh";

    private String oauthAuthorizationEndpoint;

    private String oauthTokenEndpoint;

    private String oauthEndSessionEndpoint;

    private String oauthClientId;

    private String oauthClientSecret;

    private String oauthRedirectUri;

    private String oauthScopes = "openid profile email";

    private String oauthPostLoginRedirect = "/";

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public Set<AuthMethod> getAuthMethodsSet() {
        if (!hasText(authMethods)) {
            return EnumSet.noneOf(AuthMethod.class);
        }
        return Arrays.stream(authMethods.split(","))
                .map(String::trim)
                .filter(BffProperties::hasText)
                .map(AuthMethod::fromWireName)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AuthMethod.class)));
    }

    public boolean isPasswordEnabled() {
        return getAuthMethodsSet().contains(AuthMethod.PASSWORD);
    }

    public boolean isOauthEnabled() {
        return getAuthMethodsSet().contains(AuthMethod.OAUTH);
    }

    @AssertTrue(message = "AUTH_METHODS must be a non-empty comma-separated list containing only 'password' and/or 'oauth'")
    public boolean isAuthMethodsValid() {
        if (!hasText(authMethods)) {
            return false;
        }
        try {
            return !getAuthMethodsSet().isEmpty();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @AssertTrue(message = "OAUTH_AUTHORIZATION_ENDPOINT is required when AUTH_METHODS includes 'oauth'")
    public boolean isOauthAuthorizationEndpointValid() {
        return !isOauthEnabledSafely() || hasText(oauthAuthorizationEndpoint);
    }

    @AssertTrue(message = "OAUTH_TOKEN_ENDPOINT is required when AUTH_METHODS includes 'oauth'")
    public boolean isOauthTokenEndpointValid() {
        return !isOauthEnabledSafely() || hasText(oauthTokenEndpoint);
    }

    @AssertTrue(message = "OAUTH_CLIENT_ID is required when AUTH_METHODS includes 'oauth'")
    public boolean isOauthClientIdValid() {
        return !isOauthEnabledSafely() || hasText(oauthClientId);
    }

    @AssertTrue(message = "OAUTH_REDIRECT_URI is required when AUTH_METHODS includes 'oauth'")
    public boolean isOauthRedirectUriValid() {
        return !isOauthEnabledSafely() || hasText(oauthRedirectUri);
    }

    private boolean isOauthEnabledSafely() {
        try {
            return isOauthEnabled();
        } catch (IllegalArgumentException e) {
            // Reported by isAuthMethodsValid(); don't cascade into every OAuth field too.
            return false;
        }
    }

    public String getFrontendOrigin() {
        return frontendOrigin;
    }

    public void setFrontendOrigin(String frontendOrigin) {
        this.frontendOrigin = frontendOrigin;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public long getApiTimeoutMs() {
        return apiTimeoutMs;
    }

    public void setApiTimeoutMs(long apiTimeoutMs) {
        this.apiTimeoutMs = apiTimeoutMs;
    }

    public String getApiProxyPath() {
        return apiProxyPath;
    }

    public void setApiProxyPath(String apiProxyPath) {
        this.apiProxyPath = apiProxyPath;
    }

    public String getAuthMethods() {
        return authMethods;
    }

    public void setAuthMethods(String authMethods) {
        this.authMethods = authMethods;
    }

    public String getSessionSecret() {
        return sessionSecret;
    }

    public void setSessionSecret(String sessionSecret) {
        this.sessionSecret = sessionSecret;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getCookieSameSite() {
        return cookieSameSite;
    }

    public void setCookieSameSite(String cookieSameSite) {
        this.cookieSameSite = cookieSameSite;
    }

    public long getCookieMaxAgeMs() {
        return cookieMaxAgeMs;
    }

    public void setCookieMaxAgeMs(long cookieMaxAgeMs) {
        this.cookieMaxAgeMs = cookieMaxAgeMs;
    }

    public boolean isCsrfProtectionEnabled() {
        return csrfProtectionEnabled;
    }

    public void setCsrfProtectionEnabled(boolean csrfProtectionEnabled) {
        this.csrfProtectionEnabled = csrfProtectionEnabled;
    }

    public String getPasswordLoginPath() {
        return passwordLoginPath;
    }

    public void setPasswordLoginPath(String passwordLoginPath) {
        this.passwordLoginPath = passwordLoginPath;
    }

    public String getPasswordRefreshPath() {
        return passwordRefreshPath;
    }

    public void setPasswordRefreshPath(String passwordRefreshPath) {
        this.passwordRefreshPath = passwordRefreshPath;
    }

    public String getOauthAuthorizationEndpoint() {
        return oauthAuthorizationEndpoint;
    }

    public void setOauthAuthorizationEndpoint(String oauthAuthorizationEndpoint) {
        this.oauthAuthorizationEndpoint = oauthAuthorizationEndpoint;
    }

    public String getOauthTokenEndpoint() {
        return oauthTokenEndpoint;
    }

    public void setOauthTokenEndpoint(String oauthTokenEndpoint) {
        this.oauthTokenEndpoint = oauthTokenEndpoint;
    }

    public String getOauthEndSessionEndpoint() {
        return oauthEndSessionEndpoint;
    }

    public void setOauthEndSessionEndpoint(String oauthEndSessionEndpoint) {
        this.oauthEndSessionEndpoint = oauthEndSessionEndpoint;
    }

    public String getOauthClientId() {
        return oauthClientId;
    }

    public void setOauthClientId(String oauthClientId) {
        this.oauthClientId = oauthClientId;
    }

    public String getOauthClientSecret() {
        return oauthClientSecret;
    }

    public void setOauthClientSecret(String oauthClientSecret) {
        this.oauthClientSecret = oauthClientSecret;
    }

    public String getOauthRedirectUri() {
        return oauthRedirectUri;
    }

    public void setOauthRedirectUri(String oauthRedirectUri) {
        this.oauthRedirectUri = oauthRedirectUri;
    }

    public String getOauthScopes() {
        return oauthScopes;
    }

    public void setOauthScopes(String oauthScopes) {
        this.oauthScopes = oauthScopes;
    }

    public String getOauthPostLoginRedirect() {
        return oauthPostLoginRedirect;
    }

    public void setOauthPostLoginRedirect(String oauthPostLoginRedirect) {
        this.oauthPostLoginRedirect = oauthPostLoginRedirect;
    }
}
