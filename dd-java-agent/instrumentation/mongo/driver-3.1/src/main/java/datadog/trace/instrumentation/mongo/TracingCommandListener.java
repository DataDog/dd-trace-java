package datadog.trace.instrumentation.mongo;

import static datadog.trace.api.cache.RadixTreeCache.PORTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mongo.MongoClientDecorator.DECORATE;
import static datadog.trace.instrumentation.mongo.MongoClientDecorator.MONGO_QUERY;

import com.mongodb.ServerAddress;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TracingCommandListener implements CommandListener {

  private final Map<Integer, AgentSpan> spanMap = new ConcurrentHashMap<>();

  @Override
  public void commandStarted(final CommandStartedEvent event) {
    final AgentSpan span = startSpan(MONGO_QUERY);
    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, event);
      if (event.getConnectionDescription() != null
          && event.getConnectionDescription() != null
          && event.getConnectionDescription().getServerAddress() != null) {
        // cannot use onPeerConnection because ServerAddress.getSocketAddress()
        // may do a DNS lookup
        ServerAddress serverAddress = event.getConnectionDescription().getServerAddress();
        span.setTag(Tags.PEER_HOSTNAME, serverAddress.getHost())
            .setTag(Tags.PEER_PORT, PORTS.get(serverAddress.getPort()));
      }
      DECORATE.onStatement(span, event.getCommand());
      spanMap.put(event.getRequestId(), span);
    }
  }

  @Override
  public void commandSucceeded(final CommandSucceededEvent event) {
    final AgentSpan span = spanMap.remove(event.getRequestId());
    if (span != null) {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void commandFailed(final CommandFailedEvent event) {
    final AgentSpan span = spanMap.remove(event.getRequestId());
    if (span != null) {
      DECORATE.onError(span, event.getThrowable());
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
