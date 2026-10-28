package com.vx.marketplace.application.port;

import com.vx.marketplace.domain.DomainEvent;

public interface EventPublisher {
    void publish(DomainEvent event);
}
