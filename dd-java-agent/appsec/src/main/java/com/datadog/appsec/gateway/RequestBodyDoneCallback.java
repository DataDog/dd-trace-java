package com.datadog.appsec.gateway;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.SingletonDataBundle;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodySupplier;
import java.util.function.BiFunction;

class RequestBodyDoneCallback
    implements BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> {
  private final EventProducerService producerService;
  // subscriber cache
  private volatile EventProducerService.DataSubscriberInfo rawRequestBodySubInfo;

  public RequestBodyDoneCallback(final EventProducerService producerService) {
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(final RequestContext ctx_, final StoredBodySupplier supplier) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isRawReqBodyPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setRawReqBodyPublished(true);

    while (true) {
      EventProducerService.DataSubscriberInfo subInfo = rawRequestBodySubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_BODY_RAW);
        rawRequestBodySubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }

      CharSequence bodyContent = supplier.get();
      if (bodyContent == null || bodyContent.length() == 0) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.REQUEST_BODY_RAW, bodyContent);
      try {
        return producerService.publishDataEvent(subInfo, ctx, bundle, false);
      } catch (ExpiredSubscriberInfoException e) {
        rawRequestBodySubInfo = null;
      }
    }
  }
}
