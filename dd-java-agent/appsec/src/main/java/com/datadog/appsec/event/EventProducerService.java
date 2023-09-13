package com.datadog.appsec.event;

import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.gateway.Flow;
import java.util.Collection;

public interface EventProducerService {

  /**
   * Determines the data callbacks for the given addresses. The return value can be cached if it's
   * guaranteed that no modifications to the subscriptions will be made between usages, and that the
   * set of addresses is identical.
   *
   * <p>The return value is to be passed to {@link #publishDataEvent(DataSubscriberInfo,
   * AppSecRequestContext, DataBundle, boolean)}.
   *
   * @param newAddresses the addresses contained in the {@link DataBundle} that is to be passed to
   *     <code>publishDataEvent()</code>.
   * @return an object describing the callbacks
   */
  DataSubscriberInfo getDataSubscribers(Address<?>... newAddresses);

  /**
   * Runs the data callbacks for the given data. The subscribers must have been previously obtained
   * with {@link #getDataSubscribers(Address[])}.
   *
   * <p>This method does not throw. If one of the callbacks throws, the exception is caught and the
   * processing continues.
   *
   * @return the resulting action
   */
  Flow<Void> publishDataEvent(
      DataSubscriberInfo subscribers,
      AppSecRequestContext ctx,
      DataBundle newData,
      boolean isTransient)
      throws ExpiredSubscriberInfoException;

  interface DataSubscriberInfo {
    boolean isEmpty();
  }

  Collection<Address<?>> allSubscribedDataAddresses();
}
