import jakarta.websocket.CloseReason
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.MessageHandler
import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint
import java.nio.ByteBuffer

class Endpoints {
  static class TestEndpoint extends Endpoint {
    final MessageHandler handler

    TestEndpoint(final MessageHandler handler) {
      this.handler = handler
    }

    @Override
    void onOpen(Session session, EndpointConfig endpointConfig) {
      session.addMessageHandler(handler)
    }
  }

  // Full handlers
  static class FullStringHandler implements MessageHandler.Whole<String> {
    @Override
    void onMessage(String s) {
      assert s != null
    }
  }

  static class FullBytesHandler implements MessageHandler.Whole<byte[]> {
    @Override
    void onMessage(byte[] b) {
      assert b != null
    }
  }

  static class FullByteBufferHandler implements MessageHandler.Whole<ByteBuffer> {
    @Override
    void onMessage(ByteBuffer b) {
      assert b != null
    }
  }

  // Partial Handlers
  static class PartialStringHandler implements MessageHandler.Partial<String> {
    @Override
    void onMessage(String s, boolean last) {
      assert s != null && last
    }
  }

  static class PartialBytesHandler implements MessageHandler.Partial<byte[]> {
    @Override
    void onMessage(byte[] b, boolean last) {
      assert b != null && last
    }
  }

  static class PartialByteBufferHandler implements MessageHandler.Partial<ByteBuffer> {
    @Override
    void onMessage(ByteBuffer b, boolean last) {
      assert b != null && last
    }
  }

  @ServerEndpoint("/test/{p}")
  static class PojoFullEndpoint {
    @OnOpen
    void onOpen(Session s) {
      assert s != null
    }

    @OnMessage
    void onText(Session s, String payload, @PathParam("p") String param) {
      assert s != null && payload != null && param == "param"
    }

    @OnMessage
    void onBinary(ByteBuffer payload, @PathParam("p") String param) {
      assert payload != null && param == "param"
    }

    @OnClose
    void onClose() {
    }
  }

  @ServerEndpoint("/test/{p}")
  static class PojoPartialEndpoint {
    @OnOpen
    void onOpen(EndpointConfig cfg, @PathParam("p") String param) {
      assert param == "param" && cfg != null
    }

    @OnMessage
    void onText(Session s, String payload, boolean last) {
      assert s != null && payload != null && last
    }

    @OnMessage
    void onBinary(ByteBuffer payload, boolean last, @PathParam("p") String param) {
      assert payload != null && param == "param" && last
    }

    @OnClose
    void onClose(CloseReason cr, @PathParam("p") String param) {
      assert cr != null && param == "param"
    }
  }
}
