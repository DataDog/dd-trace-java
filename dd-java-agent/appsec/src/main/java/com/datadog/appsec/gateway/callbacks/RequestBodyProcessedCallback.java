package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.ObjectIntrospection;
import com.datadog.appsec.event.data.SingletonDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.appsec.gateway.NoopFlow;
import com.datadog.appsec.gateway.SubscribersCache;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestBodyProcessedCallback
    implements BiFunction<RequestContext, Object, Flow<Void>> {

  private static final Logger log = LoggerFactory.getLogger(RequestBodyProcessedCallback.class);

  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;

  public RequestBodyProcessedCallback(
      SubscribersCache subscribersCache, EventProducerService producerService) {
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext requestContext, Object obj) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    if (ctx.isConvertedReqBodyPublished()) {
      log.debug("Request body already published; will ignore new value of type {}", obj.getClass());
      return NoopFlow.INSTANCE;
    }
    ctx.setConvertedReqBodyPublished(true);

    while (true) {
      EventProducerService.DataSubscriberInfo subInfo = subscribersCache.getRequestBodySubInfo();
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_BODY_OBJECT);
        subscribersCache.setRequestBodySubInfo(subInfo);
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new SingletonDataBundle<>(
              KnownAddresses.REQUEST_BODY_OBJECT, ObjectIntrospection.convert(obj));
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        subscribersCache.setRequestBodySubInfo(null);
      }
    }
  }
}
