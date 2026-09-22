package com.toenshoffr.bff.auth;

/** The Spring Boot API or IdP could not be reached, timed out, or returned an unexpected error. */
public class UpstreamServiceException extends RuntimeException {

    public UpstreamServiceException(String message) {
        super(message);
    }

    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
