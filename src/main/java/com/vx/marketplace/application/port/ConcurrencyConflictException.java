package com.vx.marketplace.application.port;

import com.vx.marketplace.domain.BusinessRuleException;

/** Contention outlasted the unit of work's attempts. Extends BusinessRuleException so it maps to 409. */
public class ConcurrencyConflictException extends BusinessRuleException {
    public ConcurrencyConflictException(String message) {
        super(message);
    }
}
