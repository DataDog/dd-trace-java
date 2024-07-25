package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.SingletonDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.appsec.gateway.NoopFlow;
import com.datadog.appsec.gateway.SubscribersCache;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodySupplier;
import java.util.function.BiFunction;

public class RequestBodyDoneCallback
    implements BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> {

  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;

  public RequestBodyDoneCallback(
      SubscribersCache subscribersCache, EventProducerService producerService) {
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext requestContext, StoredBodySupplier storedBodySupplier) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isRawReqBodyPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setRawReqBodyPublished(true);

    while (true) {
      EventProducerService.DataSubscriberInfo subInfo = subscribersCache.getRawRequestBodySubInfo();
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_BODY_RAW);
        subscribersCache.setRawRequestBodySubInfo(subInfo);
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }

      CharSequence bodyContent = storedBodySupplier.get();
      if (bodyContent == null || bodyContent.length() == 0) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.REQUEST_BODY_RAW, bodyContent);
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        subscribersCache.setRawRequestBodySubInfo(null);
      }
    }
  }
}
