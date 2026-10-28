package com.vx.marketplace.domain;

/** The caller is not allowed to perform this action on the aggregate (maps to HTTP 403). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
