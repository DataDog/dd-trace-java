package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.reactor.netty.CaptureConnectSpan.CONNECT_CONTEXT;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.bootstrap.ContextStore;
import java.util.function.BiConsumer;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClientRequest;

public class TransferConnectSpan implements BiConsumer<HttpClientRequest, Connection> {
  private final ContextStore<HttpClientRequest, Context> requestContexts;

  public TransferConnectSpan(ContextStore<HttpClientRequest, Context> requestContexts) {
    this.requestContexts = requestContexts;
  }

  @Override
  public void accept(HttpClientRequest clientRequest, Connection connection) {
    final Context context = clientRequest.currentContextView().getOrDefault(CONNECT_CONTEXT, null);
    if (null == context) {
      return;
    }
    // Reactor clears its owner context before the pool-release callback runs.
    requestContexts.put(clientRequest, context);
    ContextContinuation newContinuation = context.capture();
    ContextContinuation oldContinuation =
        connection
            .channel()
            .attr(CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY)
            .getAndSet(newContinuation);
    if (null != oldContinuation) {
      oldContinuation.release();
    }
  }
}
