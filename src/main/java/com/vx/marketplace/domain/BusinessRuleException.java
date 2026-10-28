package com.vx.marketplace.domain;

/** A request was well-formed but violates a business rule (maps to HTTP 409). */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
