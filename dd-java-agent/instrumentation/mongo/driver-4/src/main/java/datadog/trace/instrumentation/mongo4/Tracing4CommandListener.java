package datadog.trace.instrumentation.mongo4;

import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.api.cache.RadixTreeCache.PORTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mongo4.Mongo4ClientDecorator.DECORATE;
import static datadog.trace.instrumentation.mongo4.Mongo4ClientDecorator.MONGO_QUERY;

import com.mongodb.ServerAddress;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class Tracing4CommandListener implements CommandListener {

  private static final DDCache<String, UTF8BytesString> COMMAND_NAMES =
      DDCaches.newUnboundedCache(16);

  private String applicationName;

  public void setApplicationName(final String applicationName) {
    this.applicationName = applicationName;
  }

  @Override
  public void commandStarted(final CommandStartedEvent event) {
    final AgentSpan span = startSpan(MONGO_QUERY);
    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, event);
      // overlay Mongo application name if we have it (replaces the deprecated cluster description)
      if (applicationName != null) {
        span.setTag(Tags.DB_INSTANCE, applicationName);
        if (Config.get().isDbClientSplitByInstance()) {
          span.setServiceName(applicationName);
        }
      }
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
      RequestSpanMap.addRequestSpan(event.getRequestId(), span);
    }
  }

  @Override
  public void commandSucceeded(final CommandSucceededEvent event) {
    final RequestSpanMap.RequestSpan requestSpan =
        RequestSpanMap.removeRequestSpan(event.getRequestId());
    final AgentSpan span = requestSpan != null ? requestSpan.span : null;
    if (span != null) {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void commandFailed(final CommandFailedEvent event) {
    final RequestSpanMap.RequestSpan requestSpan =
        RequestSpanMap.removeRequestSpan(event.getRequestId());
    final AgentSpan span = requestSpan != null ? requestSpan.span : null;
    if (span != null) {
      DECORATE.onError(span, event.getThrowable());
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
