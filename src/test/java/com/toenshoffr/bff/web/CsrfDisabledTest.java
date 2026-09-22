package com.toenshoffr.bff.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CSRF_PROTECTION_ENABLED=false must actually disable the double-submit check (SPEC.md acceptance checklist). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "FRONTEND_ORIGIN=http://localhost:4200",
        "API_BASE_URL=http://localhost:1",
        "SESSION_SECRET=test-session-secret-1234567890",
        "AUTH_METHODS=password",
        "CSRF_PROTECTION_ENABLED=false"
})
class CsrfDisabledTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void mutatingRequestWithoutCsrfTokenIsNotBlocked() {
        // No X-CSRF-Token header at all. With CSRF disabled this must reach the controller
        // (which then fails on missing credentials, i.e. NOT a 403 from the CSRF filter).
        ResponseEntity<Map> response = restTemplate.postForEntity("/auth/logout", HttpEntity.EMPTY, Map.class);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("authenticated", false);
    }
}
