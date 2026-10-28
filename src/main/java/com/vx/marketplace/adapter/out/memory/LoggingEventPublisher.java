package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.EventPublisher;
import com.vx.marketplace.domain.DomainEvent;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local/dev adapter: logs events and keeps them in memory so tests can assert on what was published. */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger LOG = System.getLogger(LoggingEventPublisher.class.getName());

    private final List<DomainEvent> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(DomainEvent event) {
        published.add(event);
        LOG.log(Level.INFO, "Published {0} for {1}", event.type(), event.aggregateId());
    }

    public List<DomainEvent> published() {
        return List.copyOf(published);
    }
}
