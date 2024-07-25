package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.NoopFlow;
import com.datadog.appsec.gateway.SubscribersCache;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;

public class RequestClientSocketAddressCallback
    implements TriFunction<RequestContext, String, Integer, Flow<Void>> {

  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;

  public RequestClientSocketAddressCallback(
      SubscribersCache subscribersCache, EventProducerService producerService) {
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext requestContext, String ip, Integer port) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isReqDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setPeerAddress(ip);
    ctx.setPeerPort(port);
    return CallbackUtils.INSTANCE.maybePublishRequestData(ctx, subscribersCache, producerService);
  }
}
