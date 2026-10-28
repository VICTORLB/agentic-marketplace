package com.vx.marketplace.domain;

/** The requested aggregate does not exist (maps to HTTP 404). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
