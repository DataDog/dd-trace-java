import javax.websocket.CloseReason
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.OnClose
import javax.websocket.OnMessage
import javax.websocket.OnOpen
import javax.websocket.Session
import javax.websocket.server.PathParam
import javax.websocket.server.ServerEndpoint
import java.nio.ByteBuffer

class JavaxEndpoints {
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
