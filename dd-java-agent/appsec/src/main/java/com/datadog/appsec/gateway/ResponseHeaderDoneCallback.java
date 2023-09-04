package com.datadog.appsec.gateway;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.MapDataBundle;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.Function;

class ResponseHeaderDoneCallback implements Function<RequestContext, Flow<Void>> {
  private final EventProducerService producerService;
  // subscriber cache
  private volatile EventProducerService.DataSubscriberInfo respDataSubInfo;

  public ResponseHeaderDoneCallback(final EventProducerService producerService) {
    this.producerService = producerService;
  }

  @Override
  public Flow<Void> apply(final RequestContext ctx_) {
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isRespDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.finishResponseHeaders();
    return maybePublishResponseData(ctx);
  }

  private Flow<Void> maybePublishResponseData(AppSecRequestContext ctx) {
    int status = ctx.getResponseStatus();

    if (status == 0 || !ctx.isFinishedResponseHeaders()) {
      return NoopFlow.INSTANCE;
    }

    ctx.setRespDataPublished(true);

    MapDataBundle bundle =
        MapDataBundle.of(
            KnownAddresses.RESPONSE_STATUS, String.valueOf(ctx.getResponseStatus()),
            KnownAddresses.RESPONSE_HEADERS_NO_COOKIES, ctx.getResponseHeaders());

    while (true) {
      if (respDataSubInfo == null) {
        respDataSubInfo =
            producerService.getDataSubscribers(
                KnownAddresses.RESPONSE_STATUS, KnownAddresses.RESPONSE_HEADERS_NO_COOKIES);
      }

      try {
        return producerService.publishDataEvent(respDataSubInfo, ctx, bundle, false);
      } catch (ExpiredSubscriberInfoException e) {
        respDataSubInfo = null;
      }
    }
  }
}
