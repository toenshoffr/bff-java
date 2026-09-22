package com.toenshoffr.bff.auth;

/** Backend rejected a username/password login attempt (400/401 from the API). */
public class InvalidCredentialsException extends RuntimeException {
}
