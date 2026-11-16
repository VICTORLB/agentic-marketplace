package com.vx.marketplace.application.port;

import java.util.function.Function;

/** Runs work atomically. The function may run more than once on contention, so it must have no side effects. */
public interface UnitOfWork {
    /** @throws ConcurrencyConflictException when contention outlasts the adapter's attempts */
    <T> T execute(Function<TransactionContext, T> work);
}
