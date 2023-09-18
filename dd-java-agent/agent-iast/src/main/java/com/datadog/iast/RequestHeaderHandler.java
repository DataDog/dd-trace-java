package com.datadog.iast;

import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;

public class RequestHeaderHandler implements TriConsumer<RequestContext, String, String> {
  private static final String X_FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

  @Override
  public void accept(RequestContext requestContext, String key, String value) {
    final IastRequestContext ctx = requestContext.getData(RequestContextSlot.IAST);
    if (null != ctx
        && X_FORWARDED_PROTO_HEADER.equalsIgnoreCase(key)
        && "https".equalsIgnoreCase(value)) {
      ctx.setXForwardedProtoIsHtttps();
    }
  }
}
