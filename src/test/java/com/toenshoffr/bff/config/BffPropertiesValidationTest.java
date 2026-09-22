package com.toenshoffr.bff.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the fail-fast config validation directly (no Spring context needed), matching
 * SPEC.md's "Config validation at startup" requirement, including the OAUTH_* fields that
 * become required only when AUTH_METHODS includes 'oauth'.
 */
class BffPropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validMinimalPasswordOnlyConfigPasses() {
        BffProperties props = new BffProperties();
        props.setFrontendOrigin("http://localhost:4200");
        props.setApiBaseUrl("http://localhost:8080");
        props.setSessionSecret("a-session-secret-16-plus-chars");
        props.setAuthMethods("password");

        assertThat(validator.validate(props)).isEmpty();
    }

    @Test
    void missingRequiredFieldsAreReported() {
        BffProperties props = new BffProperties();
        props.setAuthMethods("password");

        assertThat(validator.validate(props)).isNotEmpty();
    }

    @Test
    void sessionSecretShorterThan16CharsIsRejected() {
        BffProperties props = new BffProperties();
        props.setFrontendOrigin("http://localhost:4200");
        props.setApiBaseUrl("http://localhost:8080");
        props.setSessionSecret("short");
        props.setAuthMethods("password");

        assertThat(validator.validate(props)).isNotEmpty();
    }

    @Test
    void oauthEnabledWithoutOauthFieldsFailsWithReadablePerFieldErrors() {
        BffProperties props = new BffProperties();
        props.setFrontendOrigin("http://localhost:4200");
        props.setApiBaseUrl("http://localhost:8080");
        props.setSessionSecret("a-session-secret-16-plus-chars");
        props.setAuthMethods("oauth");

        Set<String> messages = messagesOf(validator.validate(props));

        assertThat(messages).anyMatch(m -> m.contains("OAUTH_AUTHORIZATION_ENDPOINT"));
        assertThat(messages).anyMatch(m -> m.contains("OAUTH_TOKEN_ENDPOINT"));
        assertThat(messages).anyMatch(m -> m.contains("OAUTH_CLIENT_ID"));
        assertThat(messages).anyMatch(m -> m.contains("OAUTH_REDIRECT_URI"));
    }

    @Test
    void oauthEnabledWithAllOauthFieldsPasses() {
        BffProperties props = new BffProperties();
        props.setFrontendOrigin("http://localhost:4200");
        props.setApiBaseUrl("http://localhost:8080");
        props.setSessionSecret("a-session-secret-16-plus-chars");
        props.setAuthMethods("password,oauth");
        props.setOauthAuthorizationEndpoint("http://idp.test/authorize");
        props.setOauthTokenEndpoint("http://idp.test/token");
        props.setOauthClientId("client-id");
        props.setOauthRedirectUri("http://localhost:3000/auth/oauth/callback");

        assertThat(validator.validate(props)).isEmpty();
    }

    @Test
    void invalidAuthMethodsValueIsRejected() {
        BffProperties props = new BffProperties();
        props.setFrontendOrigin("http://localhost:4200");
        props.setApiBaseUrl("http://localhost:8080");
        props.setSessionSecret("a-session-secret-16-plus-chars");
        props.setAuthMethods("not-a-real-method");

        assertThat(validator.validate(props)).isNotEmpty();
    }

    private static Set<String> messagesOf(Set<ConstraintViolation<BffProperties>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
