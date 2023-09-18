package com.datadog.iast;

import com.datadog.iast.util.HttpHeader;
import com.datadog.iast.util.HttpHeader.ContextAwareHeader;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;

public class RequestHeaderHandler implements TriConsumer<RequestContext, String, String> {

  @Override
  public void accept(RequestContext requestContext, String key, String value) {
    final IastRequestContext ctx = requestContext.getData(RequestContextSlot.IAST);
    if (null != ctx && key != null) {
      final HttpHeader header = HttpHeader.from(key);
      if (header instanceof ContextAwareHeader) {
        ((ContextAwareHeader) header).onHeader(ctx, value);
      }
    }
  }
}
