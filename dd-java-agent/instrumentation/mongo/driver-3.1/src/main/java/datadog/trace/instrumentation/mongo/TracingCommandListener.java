package datadog.trace.instrumentation.mongo;

import static datadog.trace.api.Functions.UTF8_ENCODE;
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
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TracingCommandListener implements CommandListener {

  private static final DDCache<String, UTF8BytesString> COMMAND_NAMES =
      DDCaches.newUnboundedCache(16);

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
            .setTag(Tags.PEER_PORT, PORTS.get(serverAddress.getPort()))
            .setTag(
                Tags.DB_OPERATION,
                COMMAND_NAMES.computeIfAbsent(event.getCommandName(), UTF8_ENCODE));
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
