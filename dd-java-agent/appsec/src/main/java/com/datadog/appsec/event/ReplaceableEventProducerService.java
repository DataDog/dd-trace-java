package com.datadog.appsec.event;

import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.gateway.Flow;
import java.util.Collection;

public class ReplaceableEventProducerService implements EventProducerService {
  private volatile EventProducerService cur;

  public void replaceEventProducerService(EventProducerService ed) {
    this.cur = ed;
  }

  @Override
  public DataSubscriberInfo getDataSubscribers(Address<?>... newAddresses) {
    return cur.getDataSubscribers(newAddresses);
  }

  @Override
  public Flow<Void> publishDataEvent(
      DataSubscriberInfo subscribers,
      AppSecRequestContext ctx,
      DataBundle newData,
      boolean isTransient)
      throws ExpiredSubscriberInfoException {
    return cur.publishDataEvent(subscribers, ctx, newData, isTransient);
  }

  @Override
  public Collection<Address<?>> allSubscribedDataAddresses() {
    return cur.allSubscribedDataAddresses();
  }
}
