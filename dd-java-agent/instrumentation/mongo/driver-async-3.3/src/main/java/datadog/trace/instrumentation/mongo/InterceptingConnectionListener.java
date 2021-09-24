package datadog.trace.instrumentation.mongo;

import com.mongodb.connection.ConnectionId;
import com.mongodb.event.ConnectionClosedEvent;
import com.mongodb.event.ConnectionListener;
import com.mongodb.event.ConnectionMessageReceivedEvent;
import com.mongodb.event.ConnectionMessagesSentEvent;
import com.mongodb.event.ConnectionOpenedEvent;
import datadog.trace.bootstrap.ContextStore;

/**
 * A custom connection interceptor used to set up the associated {@linkplain RequestSpanMap}
 * instance for a {@link AsyncTracingCommandListener command listener}
 */
public class InterceptingConnectionListener implements ConnectionListener {
  private final ContextStore<ConnectionId, RequestSpanMap> context;
  private final AsyncTracingCommandListener tracingListener;

  public InterceptingConnectionListener(
      ContextStore<ConnectionId, RequestSpanMap> context,
      AsyncTracingCommandListener tracingListener) {
    this.context = context;
    this.tracingListener = tracingListener;
  }

  @Override
  public void connectionOpened(ConnectionOpenedEvent event) {
    // get or create the span map from the instrumentation context
    RequestSpanMap map = context.get(event.getConnectionId());
    if (map == null) {
      map = new RequestSpanMap();
      context.put(event.getConnectionId(), map);
    }
    // configure the associated command listener with this per-connection span map
    tracingListener.setSpanMap(map);
  }

  @Override
  public void connectionClosed(ConnectionClosedEvent event) {
    // connection is closed, the associated span map is not valid any more
    tracingListener.setSpanMap(null);
  }

  @Override
  public void messagesSent(ConnectionMessagesSentEvent event) {
    // ignored
  }

  @Override
  public void messageReceived(ConnectionMessageReceivedEvent event) {
    // ignored
  }
}
