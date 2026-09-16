package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.reactor.netty.CaptureConnectSpan.CONNECT_CONTEXT;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import java.util.function.BiConsumer;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClientRequest;

public class TransferConnectSpan implements BiConsumer<HttpClientRequest, Connection> {
  @Override
  public void accept(HttpClientRequest clientRequest, Connection connection) {
    final Context context = clientRequest.currentContextView().getOrDefault(CONNECT_CONTEXT, null);
    if (null == context) {
      return;
    }
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
