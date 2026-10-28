package com.vx.marketplace.adapter.out.gcp;

import com.google.api.core.ApiFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Blocks on Google client futures with a bounded wait and unchecked exceptions. */
final class Futures {

    private static final long TIMEOUT_SECONDS = 10;

    private Futures() {
    }

    static <T> T await(ApiFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for GCP call", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("GCP call failed: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("GCP call timed out after " + TIMEOUT_SECONDS + "s", e);
        }
    }
}
