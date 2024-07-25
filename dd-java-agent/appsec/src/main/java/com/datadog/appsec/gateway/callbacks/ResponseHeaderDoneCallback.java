package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.NoopFlow;
import com.datadog.appsec.gateway.SubscribersCache;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.Function;

public class ResponseHeaderDoneCallback implements Function<RequestContext, Flow<Void>> {

  private final SubscribersCache subscribersCache;
  private final EventProducerService producerService;

  public ResponseHeaderDoneCallback(
      SubscribersCache subscribersCache, EventProducerService producerService) {
    this.subscribersCache = subscribersCache;
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(RequestContext requestContext) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isRespDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.finishResponseHeaders();
    return CallbackUtils.INSTANCE.maybePublishResponseData(ctx, subscribersCache, producerService);
  }
}
