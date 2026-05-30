package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.reactor.netty.CaptureConnectSpan.CONNECT_CONTEXT;
import static datadog.trace.instrumentation.reactor.netty.CaptureConnectSpan.CONNECT_SPAN;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.BiConsumer;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClientRequest;

public class TransferConnectSpan implements BiConsumer<HttpClientRequest, Connection> {
  @Override
  public void accept(HttpClientRequest httpClientRequest, Connection connection) {
    final AgentSpan span = httpClientRequest.currentContextView().getOrDefault(CONNECT_SPAN, null);
    if (null == span) {
      return;
    }
    final Context capturedContext =
        httpClientRequest.currentContextView().getOrDefault(CONNECT_CONTEXT, null);
    final Continuation continuation =
        capturedContext != null ? captureSpan(span, capturedContext) : captureSpan(span);
    if (null != continuation) {
      Continuation current =
          connection
              .channel()
              .attr(CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY)
              .getAndSet(continuation);
      if (null != current) {
        current.cancel();
      }
    }
  }
}
