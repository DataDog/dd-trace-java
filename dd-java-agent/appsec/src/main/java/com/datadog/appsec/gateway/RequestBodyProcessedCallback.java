package com.datadog.appsec.gateway;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.ObjectIntrospection;
import com.datadog.appsec.event.data.SingletonDataBundle;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestBodyProcessedCallback
    implements BiFunction<RequestContext, Object, Flow<Void>> {
  private static final Logger log = LoggerFactory.getLogger(RequestBodyProcessedCallback.class);
  private final EventProducerService producerService;
  // subscriber cache
  private volatile EventProducerService.DataSubscriberInfo requestBodySubInfo;

  public RequestBodyProcessedCallback(final EventProducerService producerService) {
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext ctx_, Object obj) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    if (ctx.isConvertedReqBodyPublished()) {
      log.debug("Request body already published; will ignore new value of type {}", obj.getClass());
      return NoopFlow.INSTANCE;
    }
    ctx.setConvertedReqBodyPublished(true);

    while (true) {
      EventProducerService.DataSubscriberInfo subInfo = requestBodySubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_BODY_OBJECT);
        requestBodySubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new SingletonDataBundle<>(
              KnownAddresses.REQUEST_BODY_OBJECT, ObjectIntrospection.convert(obj));
      try {
        return producerService.publishDataEvent(subInfo, ctx, bundle, false);
      } catch (ExpiredSubscriberInfoException e) {
        requestBodySubInfo = null;
      }
    }
  }
}
