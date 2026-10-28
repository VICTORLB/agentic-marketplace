package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.ProcessedMessageStore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryProcessedMessageStore implements ProcessedMessageStore {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markProcessed(String messageId) {
        return seen.add(messageId);
    }
}
