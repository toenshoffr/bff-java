package com.toenshoffr.bff.auth;

import com.toenshoffr.bff.session.TokenSet;

/** Outcome of a successful login/token-exchange call: the tokens to store, plus the user object (if any). */
public record AuthResult(TokenSet tokens, Object user) {
}
