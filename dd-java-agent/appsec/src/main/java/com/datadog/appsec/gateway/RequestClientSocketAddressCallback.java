package com.datadog.appsec.gateway;

import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;

public class RequestClientSocketAddressCallback
    implements TriFunction<RequestContext, String, Integer, Flow<Void>> {
  private final MaybePublishRequestDataCallback maybePublishRequestDataCallback;

  public RequestClientSocketAddressCallback(
      final MaybePublishRequestDataCallback maybePublishRequestDataCallback) {
    this.maybePublishRequestDataCallback = maybePublishRequestDataCallback;
  }

  @Override
  public Flow<Void> apply(final RequestContext ctx_, final String ip, final Integer port) {
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isReqDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setPeerAddress(ip);
    ctx.setPeerPort(port);
    return maybePublishRequestDataCallback.apply(ctx);
  }
}
