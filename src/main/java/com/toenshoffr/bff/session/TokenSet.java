package com.toenshoffr.bff.session;

import java.io.Serializable;

/**
 * Server-side-only token bundle for one session. Never sent to the browser.
 *
 * @param accessToken  current JWT access token
 * @param refreshToken refresh token, if the backend/IdP issued one
 * @param expiresAt    epoch-millis at which {@code accessToken} expires
 */
public record TokenSet(String accessToken, String refreshToken, long expiresAt) implements Serializable {

    /** True if this token is still usable, allowing a small skew before its real expiry. */
    public boolean isValid(long skewMillis) {
        return accessToken != null && System.currentTimeMillis() < (expiresAt - skewMillis);
    }
}
