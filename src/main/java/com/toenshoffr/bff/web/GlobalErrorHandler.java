package com.toenshoffr.bff.web;

import com.toenshoffr.bff.auth.InvalidCredentialsException;
import com.toenshoffr.bff.auth.InvalidOAuthStateException;
import com.toenshoffr.bff.auth.InvalidRequestException;
import com.toenshoffr.bff.auth.UnauthenticatedException;
import com.toenshoffr.bff.auth.UpstreamServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps the BFF's domain exceptions onto the JSON error shapes documented in SPEC.md. */
@RestControllerAdvice
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(InvalidCredentialsException e) {
        return error(HttpStatus.UNAUTHORIZED, "invalid_credentials");
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthenticated(UnauthenticatedException e) {
        return error(HttpStatus.UNAUTHORIZED, "unauthenticated");
    }

    @ExceptionHandler(InvalidOAuthStateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOAuthState(InvalidOAuthStateException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_oauth_state");
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.errorCode());
    }

    @ExceptionHandler(UpstreamServiceException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamError(UpstreamServiceException e) {
        log.warn("Upstream call failed: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "upstream_error");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
