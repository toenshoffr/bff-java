package com.toenshoffr.bff.auth;

/** OAuth callback's {@code state} didn't match the flow stored at {@code /auth/oauth/login} time, or {@code code} was missing. */
public class InvalidOAuthStateException extends RuntimeException {
}
