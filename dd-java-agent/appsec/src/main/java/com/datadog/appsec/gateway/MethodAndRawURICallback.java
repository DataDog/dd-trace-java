package com.datadog.appsec.gateway;

import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.net.URI;
import java.net.URISyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MethodAndRawURICallback
    implements TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> {

  private static final Logger log = LoggerFactory.getLogger(MethodAndRawURICallback.class);
  private final MaybePublishRequestDataCallback maybePublishRequestDataCallback;

  public MethodAndRawURICallback(
      final MaybePublishRequestDataCallback maybePublishRequestDataCallback) {
    this.maybePublishRequestDataCallback = maybePublishRequestDataCallback;
  }

  @Override
  public Flow<Void> apply(RequestContext ctx_, String method, URIDataAdapter uri) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    if (ctx.isReqDataPublished()) {
      log.debug(
          "Request method and URI already published; will ignore new values {}, {}", method, uri);
      return NoopFlow.INSTANCE;
    }
    ctx.setMethod(method);
    ctx.setScheme(uri.scheme());
    if (uri.supportsRaw()) {
      ctx.setRawURI(uri.raw());
    } else {
      try {
        URI encodedUri = new URI(null, null, uri.path(), uri.query(), null);
        String q = encodedUri.getRawQuery();
        StringBuilder encoded = new StringBuilder();
        encoded.append(encodedUri.getRawPath());
        if (null != q && !q.isEmpty()) {
          encoded.append('?').append(q);
        }
        ctx.setRawURI(encoded.toString());
      } catch (URISyntaxException e) {
        log.debug("Failed to encode URI '{}{}'", uri.path(), uri.query());
      }
    }
    return maybePublishRequestDataCallback.apply(ctx);
  }
}
