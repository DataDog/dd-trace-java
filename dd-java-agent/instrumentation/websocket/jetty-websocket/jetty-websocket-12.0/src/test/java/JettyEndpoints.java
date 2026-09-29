import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

public class JettyEndpoints {
  public static class EndpointEvents {
    Session session;
    final List<String> messages = new ArrayList<>();
    final List<Boolean> finalFragments = new ArrayList<>();
    final List<AgentSpan> messageSpans = new ArrayList<>();
    boolean failMessages;
    boolean failClose;
    boolean deferCallback;
    boolean failCallback;
    boolean failAfterCallback;
    Callback pendingCallback;
    int closeCode;
    String closeReason;

    void recordMessage(String message) {
      messages.add(message);
      messageSpans.add(activeSpan());
      if (failMessages) {
        throw new IllegalStateException("handler failed");
      }
    }

    void recordClose(int statusCode, String reason) {
      closeCode = statusCode;
      closeReason = reason;
      if (failClose) {
        throw new IllegalStateException("handler failed");
      }
    }

    void completeBinary(Callback callback) {
      if (deferCallback) {
        pendingCallback = callback;
      } else if (failCallback) {
        callback.fail(new IllegalStateException("callback failed"));
      } else {
        callback.succeed();
      }
      if (failAfterCallback) {
        throw new IllegalStateException("handler failed");
      }
    }
  }

  public static class ListenerEndpoint extends EndpointEvents
      implements Session.Listener.AutoDemanding {
    @Override
    public void onWebSocketOpen(Session session) {
      this.session = session;
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
      recordClose(statusCode, reason);
    }
  }

  public static class FullListener extends ListenerEndpoint {
    @Override
    public void onWebSocketText(String message) {
      recordMessage(message);
    }

    @Override
    public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
      recordMessage(UTF_8.decode(payload).toString());
      completeBinary(callback);
    }
  }

  public static class PartialListener extends ListenerEndpoint {
    @Override
    public void onWebSocketPartialText(String payload, boolean last) {
      finalFragments.add(last);
      recordMessage(payload);
    }

    @Override
    public void onWebSocketPartialBinary(ByteBuffer payload, boolean last, Callback callback) {
      finalFragments.add(last);
      recordMessage(UTF_8.decode(payload).toString());
      completeBinary(callback);
    }
  }

  public static class PojoEndpoint extends EndpointEvents {
    @OnWebSocketOpen
    public void onOpen(Session session) {
      this.session = session;
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
      recordClose(statusCode, reason);
    }
  }

  @WebSocket
  public static class PojoFullEndpoint extends PojoEndpoint {
    @OnWebSocketMessage
    public void onText(Session session, String payload) {
      assertSame(this.session, session);
      recordMessage(payload);
    }

    @OnWebSocketMessage
    public void onBinary(ByteBuffer payload, Callback callback) {
      recordMessage(UTF_8.decode(payload).toString());
      completeBinary(callback);
    }
  }

  @WebSocket
  public static class PojoPartialEndpoint extends PojoEndpoint {
    @OnWebSocketMessage
    public void onText(String payload, boolean last) {
      finalFragments.add(last);
      recordMessage(payload);
    }

    @OnWebSocketMessage
    public void onBinary(Session session, ByteBuffer payload, boolean last, Callback callback) {
      assertSame(this.session, session);
      finalFragments.add(last);
      recordMessage(UTF_8.decode(payload).toString());
      completeBinary(callback);
    }
  }

  @WebSocket
  public static class NoCloseEndpoint extends EndpointEvents {
    @OnWebSocketMessage
    public void onText(String payload, boolean last) {
      recordMessage(payload);
    }

    @OnWebSocketMessage
    public void onBinary(ByteBuffer payload, boolean last, Callback callback) {
      recordMessage(UTF_8.decode(payload).toString());
      completeBinary(callback);
    }
  }
}
