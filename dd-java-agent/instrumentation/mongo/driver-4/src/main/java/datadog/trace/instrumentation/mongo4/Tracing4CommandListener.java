package datadog.trace.instrumentation.mongo4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mongo4.Mongo4ClientDecorator.DECORATE;
import static datadog.trace.instrumentation.mongo4.Mongo4ClientDecorator.MONGO_QUERY;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Tracing4CommandListener implements CommandListener {

  private final Map<Integer, AgentSpan> spanMap = new ConcurrentHashMap<>();

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
          span.setTag(DDTags.SERVICE_NAME, applicationName);
        }
      }
      if (event.getConnectionDescription() != null
          && event.getConnectionDescription() != null
          && event.getConnectionDescription().getServerAddress() != null) {
        DECORATE.onPeerConnection(
            span, event.getConnectionDescription().getServerAddress().getSocketAddress());
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
