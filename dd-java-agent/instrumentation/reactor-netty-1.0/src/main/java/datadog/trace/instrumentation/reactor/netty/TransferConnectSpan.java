package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.reactor.netty.CaptureConnectSpan.CONNECT_SPAN;

import datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.Channel;
import java.util.function.BiConsumer;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClientRequest;

public class TransferConnectSpan implements BiConsumer<HttpClientRequest, Connection> {
  @Override
  public void accept(HttpClientRequest httpClientRequest, Connection connection) {
    final AgentSpan span = httpClientRequest.currentContextView().getOrDefault(CONNECT_SPAN, null);
    final Continuation continuation = null == span ? null : captureSpan(span);
    if (null != continuation) {
      final Channel channel = connection.channel();
      final Continuation current =
          channel.attr(CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY).getAndSet(continuation);
      if (null != current) {
        current.cancel();
      }

      // HTTP/2 requests operate on stream channels. Netty captures the TCP connect continuation on
      // the parent channel, but request tracing consumes the request-specific continuation from the
      // stream channel, so the parent copy must not keep the trace open.
      final Channel parent = channel.parent();
      if (parent != null) {
        final Continuation parentCurrent =
            parent.attr(CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY).getAndRemove();
        if (null != parentCurrent) {
          parentCurrent.cancel();
        }
      }
    }
  }
}
