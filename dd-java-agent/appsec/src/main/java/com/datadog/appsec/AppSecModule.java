package com.datadog.appsec;

import com.datadog.appsec.config.AppSecConfigService;
import com.datadog.appsec.event.DataListener;
import com.datadog.appsec.event.EventListener;
import com.datadog.appsec.event.EventType;
import com.datadog.appsec.event.OrderedCallback.Priority;
import com.datadog.appsec.event.data.Address;
import java.util.Collection;

public interface AppSecModule {
  void config(AppSecConfigService appSecConfigService) throws AppSecModuleActivationException;

  String getName();

  Collection<EventSubscription> getEventSubscriptions();

  Collection<DataSubscription> getDataSubscriptions();

  abstract class EventSubscription implements EventListener {
    public final EventType eventType;
    private final Priority priority;

    protected EventSubscription(EventType eventType, Priority priority) {
      this.eventType = eventType;
      this.priority = priority;
    }

    @Override
    public Priority getPriority() {
      return priority;
    }
  }

  abstract class DataSubscription implements DataListener {
    private final Collection<Address<?>> subscribedAddresses;
    private final Priority priority;

    protected DataSubscription(Collection<Address<?>> subscribedAddresses, Priority priority) {
      this.subscribedAddresses = subscribedAddresses;
      this.priority = priority;
    }

    @Override
    public Priority getPriority() {
      return priority;
    }

    public Collection<Address<?>> getSubscribedAddresses() {
      return subscribedAddresses;
    }
  }

  class AppSecModuleActivationException extends Exception {
    public AppSecModuleActivationException(String message) {
      super(message);
    }

    public AppSecModuleActivationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
