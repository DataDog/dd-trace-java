package com.datadog.appsec.gateway;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.EventType;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodySupplier;
import java.util.function.BiFunction;

class RequestBodyStartCallback implements BiFunction<RequestContext, StoredBodySupplier, Void> {
  private final EventProducerService producerService;

  public RequestBodyStartCallback(final EventProducerService producerService) {
    this.producerService = producerService;
  }

  @Override
  public Void apply(RequestContext ctx_, StoredBodySupplier supplier) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return null;
    }

    ctx.setStoredRequestBodySupplier(supplier);
    producerService.publishEvent(ctx, EventType.REQUEST_BODY_START);
    return null;
  }
}
