package com.datadog.appsec;

import com.datadog.appsec.config.AppSecModuleConfigurer;
import com.datadog.appsec.event.DataListener;
import com.datadog.appsec.event.data.Address;
import java.util.Collection;

public interface AppSecModule {
  void config(AppSecModuleConfigurer appSecConfigService) throws AppSecModuleActivationException;

  String getName();

  String getInfo();

  Collection<DataSubscription> getDataSubscriptions();

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
