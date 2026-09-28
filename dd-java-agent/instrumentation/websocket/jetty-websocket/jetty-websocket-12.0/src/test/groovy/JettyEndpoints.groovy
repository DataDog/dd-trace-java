import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import org.eclipse.jetty.websocket.api.Callback
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen
import org.eclipse.jetty.websocket.api.annotations.WebSocket

import java.nio.ByteBuffer

import static java.nio.charset.StandardCharsets.UTF_8
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class JettyEndpoints {
  static class EndpointEvents {
    Session session
    final List<String> messages = []
    final List<Boolean> finalFragments = []
    final List<AgentSpan> messageSpans = []
    boolean failMessages
    boolean failClose
    boolean deferCallback
    boolean failCallback
    boolean failAfterCallback
    Callback pendingCallback
    int closeCode
    String closeReason

    void recordMessage(String message) {
      messages.add(message)
      messageSpans.add(activeSpan())
      if (failMessages) {
        throw new IllegalStateException("handler failed")
      }
    }

    void recordClose(int statusCode, String reason) {
      closeCode = statusCode
      closeReason = reason
      if (failClose) {
        throw new IllegalStateException("handler failed")
      }
    }

    void completeBinary(Callback callback) {
      if (deferCallback) {
        pendingCallback = callback
      } else if (failCallback) {
        callback.fail(new IllegalStateException("callback failed"))
      } else {
        callback.succeed()
      }
      if (failAfterCallback) {
        throw new IllegalStateException("handler failed")
      }
    }
  }

  static class ListenerEndpoint extends EndpointEvents implements Session.Listener.AutoDemanding {
    @Override
    void onWebSocketOpen(Session session) {
      this.session = session
    }

    @Override
    void onWebSocketClose(int statusCode, String reason) {
      recordClose(statusCode, reason)
    }
  }

  static class FullListener extends ListenerEndpoint {
    @Override
    void onWebSocketText(String message) {
      recordMessage(message)
    }

    @Override
    void onWebSocketBinary(ByteBuffer payload, Callback callback) {
      recordMessage(UTF_8.decode(payload).toString())
      completeBinary(callback)
    }
  }

  static class PartialListener extends ListenerEndpoint {
    @Override
    void onWebSocketPartialText(String payload, boolean last) {
      finalFragments.add(last)
      recordMessage(payload)
    }

    @Override
    void onWebSocketPartialBinary(ByteBuffer payload, boolean last, Callback callback) {
      finalFragments.add(last)
      recordMessage(UTF_8.decode(payload).toString())
      completeBinary(callback)
    }
  }

  static class PojoEndpoint extends EndpointEvents {
    @OnWebSocketOpen
    void onOpen(Session session) {
      this.session = session
    }

    @OnWebSocketClose
    void onClose(int statusCode, String reason) {
      recordClose(statusCode, reason)
    }
  }

  @WebSocket
  static class PojoFullEndpoint extends PojoEndpoint {
    @OnWebSocketMessage
    void onText(Session session, String payload) {
      assert session == this.session
      recordMessage(payload)
    }

    @OnWebSocketMessage
    void onBinary(ByteBuffer payload, Callback callback) {
      recordMessage(UTF_8.decode(payload).toString())
      completeBinary(callback)
    }
  }

  @WebSocket
  static class PojoPartialEndpoint extends PojoEndpoint {
    @OnWebSocketMessage
    void onText(String payload, boolean last) {
      finalFragments.add(last)
      recordMessage(payload)
    }

    @OnWebSocketMessage
    void onBinary(Session session, ByteBuffer payload, boolean last, Callback callback) {
      assert session == this.session
      finalFragments.add(last)
      recordMessage(UTF_8.decode(payload).toString())
      completeBinary(callback)
    }
  }

  @WebSocket
  static class NoCloseEndpoint extends EndpointEvents {
    @OnWebSocketMessage
    void onText(String payload, boolean last) {
      recordMessage(payload)
    }

    @OnWebSocketMessage
    void onBinary(ByteBuffer payload, boolean last, Callback callback) {
      recordMessage(UTF_8.decode(payload).toString())
      completeBinary(callback)
    }
  }
}
