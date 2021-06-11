package com.datadog.appsec.event;

import com.datadog.appsec.event.data.Address;
import java.util.Collection;

public interface EventConsumerService {
  void subscribeEvent(EventType event, EventListener listener);

  void subscribeDataAvailable(
      Collection<Address<?>> anyOfTheseAddresses, DataListener dataListener);
}
