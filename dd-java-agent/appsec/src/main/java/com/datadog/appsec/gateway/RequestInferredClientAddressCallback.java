package com.datadog.appsec.gateway;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiFunction;

class RequestInferredClientAddressCallback
    implements BiFunction<RequestContext, String, Flow<Void>> {
  @Override
  public Flow<Void> apply(final RequestContext ctx_, final String ip) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx != null) {
      ctx.setInferredClientIp(ip);
    }
    return NoopFlow.INSTANCE; // expected to be called before requestClientSocketAddress
  }
}
