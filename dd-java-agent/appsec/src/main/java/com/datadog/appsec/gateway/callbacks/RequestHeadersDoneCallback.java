package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.NoopFlow;
import com.datadog.appsec.gateway.SubscribersCache;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.Function;

public class RequestHeadersDoneCallback implements Function<RequestContext, Flow<Void>> {

  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;

  public RequestHeadersDoneCallback(
      SubscribersCache subscribersCache, EventProducerService producerService) {
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
  }

  public Flow<Void> apply(RequestContext ctx_) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isReqDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.finishRequestHeaders();
    return CallbackUtils.INSTANCE.maybePublishRequestData(ctx, subscribersCache, producerService);
  }
}
