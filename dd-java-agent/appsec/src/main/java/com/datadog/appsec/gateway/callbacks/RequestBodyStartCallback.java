package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodySupplier;
import java.util.function.BiFunction;

public class RequestBodyStartCallback
    implements BiFunction<RequestContext, StoredBodySupplier, Void> {

  @Override
  public Void apply(RequestContext requestContext, StoredBodySupplier storedBodySupplier) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return null;
    }
    ctx.setStoredRequestBodySupplier(storedBodySupplier);
    return null;
  }
}
