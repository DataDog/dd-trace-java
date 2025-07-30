import org.apache.commons.io.IOUtils

import javax.websocket.DecodeException
import javax.websocket.Decoder
import javax.websocket.EncodeException
import javax.websocket.Encoder
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.Session
import java.nio.ByteBuffer

class Endpoints {
  static class ClientTestEndpoint extends Endpoint {
    @Override
    void onOpen(Session session, EndpointConfig endpointConfig) {
    }
  }

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
    }
  }

  static class FullReaderHandler implements MessageHandler.Whole<Reader> {
    @Override
    void onMessage(Reader reader) {
      IOUtils.toString(reader)
    }
  }

  static class FullBytesHandler implements MessageHandler.Whole<byte[]> {
    @Override
    void onMessage(byte[] b) {
    }
  }

  static class FullByteBufferHandler implements MessageHandler.Whole<ByteBuffer> {
    @Override
    void onMessage(ByteBuffer b) {
    }
  }

  static class CustomMessage {
  }

  static class CustomMessageEncoder implements Encoder.Text<CustomMessage> {

    @Override
    void init(EndpointConfig endpointConfig) {
    }

    @Override
    void destroy() {
    }

    @Override
    String encode(CustomMessage customMessage) throws EncodeException {
      return "CustomMessage"
    }
  }

  static class CustomMessageDecoder implements Decoder.Text<CustomMessage> {

    @Override
    void init(EndpointConfig endpointConfig) {
    }

    @Override
    void destroy() {
    }

    @Override
    CustomMessage decode(String s) throws DecodeException {
      return new CustomMessage()
    }

    @Override
    boolean willDecode(String s) {
      return s == "CustomMessage"
    }
  }

  static class FullObjectHandler implements MessageHandler.Whole<CustomMessage> {
    @Override
    void onMessage(CustomMessage o) {
    }
  }

  static class FullStreamHandler implements MessageHandler.Whole<InputStream> {
    @Override
    void onMessage(InputStream is) {
      IOUtils.toByteArray(is)
    }
  }
  // Partial Handlers
  static class PartialStringHandler implements MessageHandler.Partial<String> {
    @Override
    void onMessage(String s, boolean last) {
    }
  }

  static class PartialBytesHandler implements MessageHandler.Partial<byte[]> {
    @Override
    void onMessage(byte[] b, boolean last) {
    }
  }

  static class PartialByteBufferHandler implements MessageHandler.Partial<ByteBuffer> {
    @Override
    void onMessage(ByteBuffer b, boolean last) {
    }
  }
}
