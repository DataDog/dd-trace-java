package com.datadog.appsec.event;

import com.datadog.appsec.AppSecRequestContext;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;

public interface EventProducerService {
  ChangeableFlow publishEvent(AppSecRequestContext ctx, EventType event);

  DataSubscriberInfo getDataSubscribers(AppSecRequestContext ctx, Address<?>... newAddresses);

  ChangeableFlow publishDataEvent(
      DataSubscriberInfo subscribers, AppSecRequestContext ctx, DataBundle newData);

  interface DataSubscriberInfo {
    boolean isEmpty();
  }
}
