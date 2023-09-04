package com.datadog.appsec.gateway;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.SingletonDataBundle;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RequestPathParamsCallback implements BiFunction<RequestContext, Map<String, ?>, Flow<Void>> {
  private static final Logger log = LoggerFactory.getLogger(RequestPathParamsCallback.class);
  private final EventProducerService producerService;
  // subscriber cache
  private volatile EventProducerService.DataSubscriberInfo pathParamsSubInfo;

  public RequestPathParamsCallback(final EventProducerService producerService) {
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext ctx_, Map<String, ?> data) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    if (ctx.isPathParamsPublished()) {
      log.debug("Second or subsequent publication of request params");
      return NoopFlow.INSTANCE;
    }

    while (true) {
      EventProducerService.DataSubscriberInfo subInfo = pathParamsSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_PATH_PARAMS);
        pathParamsSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.REQUEST_PATH_PARAMS, data);
      try {
        Flow<Void> flow = producerService.publishDataEvent(subInfo, ctx, bundle, false);
        return flow;
      } catch (ExpiredSubscriberInfoException e) {
        pathParamsSubInfo = null;
      }
    }
  }
}
