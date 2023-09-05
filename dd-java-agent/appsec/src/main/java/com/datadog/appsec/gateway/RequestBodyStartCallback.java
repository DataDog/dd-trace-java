package com.datadog.appsec.gateway;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodySupplier;
import java.util.function.BiFunction;

class RequestBodyStartCallback implements BiFunction<RequestContext, StoredBodySupplier, Void> {

  @Override
  public Void apply(RequestContext ctx_, StoredBodySupplier supplier) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return null;
    }

    ctx.setStoredRequestBodySupplier(supplier);
    return null;
  }
}
