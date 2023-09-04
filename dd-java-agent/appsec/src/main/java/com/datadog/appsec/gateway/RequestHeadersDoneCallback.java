package com.datadog.appsec.gateway;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.Function;

class RequestHeadersDoneCallback implements Function<RequestContext, Flow<Void>> {

  private final MaybePublishRequestDataCallback maybePublishRequestDataCallback;

  public RequestHeadersDoneCallback(
      final MaybePublishRequestDataCallback maybePublishRequestDataCallback) {
    this.maybePublishRequestDataCallback = maybePublishRequestDataCallback;
  }

  public Flow<Void> apply(RequestContext ctx_) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isReqDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.finishRequestHeaders();
    return maybePublishRequestDataCallback.apply(ctx);
  }
}
