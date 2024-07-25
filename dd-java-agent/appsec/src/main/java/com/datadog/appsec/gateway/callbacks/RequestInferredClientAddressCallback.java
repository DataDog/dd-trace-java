package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.NoopFlow;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiFunction;

public class RequestInferredClientAddressCallback
    implements BiFunction<RequestContext, String, Flow<Void>> {

  @Override
  public Flow<Void> apply(RequestContext requestContext, String ip) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx != null) {
      ctx.setInferredClientIp(ip);
    }
    return NoopFlow.INSTANCE; // expected to be called before requestClientSocketAddress
  }
}
