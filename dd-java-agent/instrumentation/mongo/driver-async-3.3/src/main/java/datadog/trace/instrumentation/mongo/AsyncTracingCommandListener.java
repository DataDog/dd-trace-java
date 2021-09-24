package datadog.trace.instrumentation.mongo;

import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.api.cache.RadixTreeCache.PORTS;
import static datadog.trace.instrumentation.mongo.MongoClientDecorator.DECORATE;

import com.mongodb.ServerAddress;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import org.bson.BsonDocument;
import org.bson.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncTracingCommandListener implements CommandListener {
  private static final Logger log = LoggerFactory.getLogger(AsyncTracingCommandListener.class);

  private static final DDCache<String, UTF8BytesString> COMMAND_NAMES =
      DDCaches.newUnboundedCache(16);

  private final ThreadLocal<AgentSpan> pendingSpan = new ThreadLocal<>();
  private volatile RequestSpanMap spanMap = null;

  private final ContextStore<BsonDocument, ByteBuf> byteBufAccessor;

  public AsyncTracingCommandListener(ContextStore<BsonDocument, ByteBuf> byteBufAccessor) {
    this.byteBufAccessor = byteBufAccessor;
  }

  public void setSpanMap(RequestSpanMap map) {
    // this is to be set by a custom ConnectionListener injected by our instrumentation
    this.spanMap = map;
  }

  public void storeCommandSpan(AgentSpan span) {
    // this is to be set by our instrumentation when the command is created
    pendingSpan.set(span);
  }

  @Override
  public void commandStarted(final CommandStartedEvent event) {
    /*
    Will use the span map and command span set previously
    The code here is extending the span with information not easily available at the place
    of creation and associating the span with a particular request id.
     */
    RequestSpanMap map = spanMap;
    AgentSpan span = pendingSpan.get();
    if (map != null && span != null) {
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
        DECORATE.onStatement(span, event.getCommand(), byteBufAccessor);
        map.put(event.getRequestId(), span);
      }
    } else {
      if (log.isDebugEnabled()) {
        log.error(
            "Command listener is misconfigured: span={}, spanMap={}",
            (span != null),
            (map != null));
      }
    }
  }

  @Override
  public void commandSucceeded(final CommandSucceededEvent event) {
    // need to cache the value here in case it is rewritten between check and call to `get`
    final RequestSpanMap map = spanMap;
    final AgentSpan span = map != null ? map.get(event.getRequestId()) : null;
    if (span != null) {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void commandFailed(final CommandFailedEvent event) {
    // need to cache the value here in case it is rewrittent between check and call to `get`
    final RequestSpanMap map = spanMap;
    final AgentSpan span = map != null ? map.get(event.getRequestId()) : null;
    if (span != null) {
      DECORATE.onError(span, event.getThrowable());
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
