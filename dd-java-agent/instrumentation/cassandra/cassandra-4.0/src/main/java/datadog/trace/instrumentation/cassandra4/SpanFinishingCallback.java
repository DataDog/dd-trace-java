package datadog.trace.instrumentation.cassandra4;

import static datadog.trace.instrumentation.cassandra4.CassandraClientDecorator.DECORATE;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.metadata.Node;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.BiConsumer;

public class SpanFinishingCallback implements BiConsumer<Object, Throwable> {

  private final AgentSpan span;

  public SpanFinishingCallback(final AgentSpan span) {
    this.span = span;
  }

  @Override
  public void accept(final Object result, final Throwable error) {
    try {
      if (error != null) {
        DECORATE.onError(span, error);
      } else if (result instanceof AsyncResultSet) {
        onAsyncResponse(span, (AsyncResultSet) result);
      }
      DECORATE.beforeFinish(span);
    } finally {
      span.finish();
    }
  }

  private static void onAsyncResponse(final AgentSpan span, final AsyncResultSet result) {
    final ExecutionInfo executionInfo = result.getExecutionInfo();
    if (executionInfo != null) {
      final Node coordinator = executionInfo.getCoordinator();
      if (coordinator != null) {
        final EndPoint endPoint = coordinator.getEndPoint();
        if (endPoint != null) {
          final SocketAddress socketAddress = endPoint.resolve();
          if (socketAddress instanceof InetSocketAddress) {
            DECORATE.onPeerConnection(span, (InetSocketAddress) socketAddress);
          }
        }
      }
    }
  }
}
