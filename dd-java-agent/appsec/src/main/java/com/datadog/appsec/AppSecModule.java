package com.datadog.appsec;

import com.datadog.appsec.event.DataListener;
import com.datadog.appsec.event.EventListener;
import com.datadog.appsec.event.EventType;
import com.datadog.appsec.event.data.Address;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

public interface AppSecModule {
  String getName();

  Collection<EventSubscription> getEventSubscriptions();

  Collection<DataSubscription> getDataSubscriptions();

  abstract class EventSubscription implements EventListener {
    private static final AtomicInteger SEQUENCE_NUMBER = new AtomicInteger();
    public final EventType eventType;
    private final int priority;
    private final int sequenceNumber = SEQUENCE_NUMBER.getAndIncrement();

    protected EventSubscription(EventType eventType, int priority) {
      this.eventType = eventType;
      this.priority = priority;
    }

    @Override
    public int getPriority() {
      return priority;
    }

    @Override
    public int getSequenceNumber() {
      return sequenceNumber;
    }
  }

  abstract class DataSubscription implements DataListener {
    private static final AtomicInteger SEQUENCE_NUMBER = new AtomicInteger();
    private final Collection<Address<?>> subscribedAddresses;
    private final int priority;
    private final int sequenceNumber = SEQUENCE_NUMBER.getAndIncrement();

    protected DataSubscription(Collection<Address<?>> subscribedAddresses, int priority) {
      this.subscribedAddresses = subscribedAddresses;
      this.priority = priority;
    }

    @Override
    public int getPriority() {
      return priority;
    }

    @Override
    public int getSequenceNumber() {
      return sequenceNumber;
    }

    public Collection<Address<?>> getSubscribedAddresses() {
      return subscribedAddresses;
    }
  }
}
