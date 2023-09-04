package com.datadog.appsec.gateway;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiFunction;

class ResponseStartedCallback implements BiFunction<RequestContext, Integer, Flow<Void>> {
  private final MaybePublishRequestDataCallback maybePublishRequestDataCallback;

  public ResponseStartedCallback(
      final MaybePublishRequestDataCallback maybePublishRequestDataCallback) {
    this.maybePublishRequestDataCallback = maybePublishRequestDataCallback;
  }

  @Override
  public Flow<Void> apply(final RequestContext ctx_, final Integer status) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isRespDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setResponseStatus(status);
    return maybePublishRequestDataCallback.apply(ctx);
  }
}
