package com.toenshoffr.bff.session;

/** HttpSession attribute keys used to store the BFF's per-session data model (see SPEC.md). */
public final class SessionKeys {

    public static final String AUTH_METHOD = "bff.authMethod";
    public static final String TOKENS = "bff.tokens";
    public static final String USER = "bff.user";
    public static final String OAUTH_FLOW = "bff.oauthFlow";

    private SessionKeys() {
    }
}
