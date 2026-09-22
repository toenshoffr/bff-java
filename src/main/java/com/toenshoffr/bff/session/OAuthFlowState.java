package com.toenshoffr.bff.session;

import java.io.Serializable;

/**
 * Transient session data for one in-flight OAuth Authorization Code + PKCE round trip,
 * stored between {@code /auth/oauth/login} and {@code /auth/oauth/callback}.
 */
public record OAuthFlowState(String state, String codeVerifier, String redirectTo) implements Serializable {
}
