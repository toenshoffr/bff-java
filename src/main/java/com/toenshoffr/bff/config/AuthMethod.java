package com.toenshoffr.bff.config;

public enum AuthMethod {
    PASSWORD("password"),
    OAUTH("oauth");

    private final String wireName;

    AuthMethod(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static AuthMethod fromWireName(String value) {
        for (AuthMethod method : values()) {
            if (method.wireName.equalsIgnoreCase(value.trim())) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unknown auth method: " + value);
    }
}
