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
import java.util.Map;
import java.util.function.BiFunction;

public class RequestPathParamsCallback
    implements BiFunction<RequestContext, Map<String, ?>, Flow<Void>> {

  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;

  public RequestPathParamsCallback(
      SubscribersCache subscribersCache, EventProducerService producerService) {
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext requestContext, Map<String, ?> data) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isPathParamsPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setPathParamsPublished(true);

    while (true) {
      EventProducerService.DataSubscriberInfo subInfo = subscribersCache.getPathParamsSubInfo();
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_PATH_PARAMS);
        subscribersCache.setPathParamsSubInfo(subInfo);
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.REQUEST_PATH_PARAMS, data);
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        subscribersCache.setPathParamsSubInfo(null);
      }
    }
  }
}
