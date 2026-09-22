package com.toenshoffr.bff.auth;

/** No session, no tokens, or a token refresh failed — caller must be treated as logged out. */
public class UnauthenticatedException extends RuntimeException {
}
