package com.toenshoffr.bff.auth;

/** Client-supplied request body/params failed validation (maps to 400). */
public class InvalidRequestException extends RuntimeException {

    private final String errorCode;

    public InvalidRequestException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
