package datadog.trace.agent.test.base

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

trait WebsocketServer implements HttpServer {

  abstract void serverSendText(String[] messages)
  abstract void serverSendBinary(byte[][] binaries)
  abstract void serverClose()
  abstract void setMaxPayloadSize(int size)

  static class ProtocolListener extends WebSocketListener {
    def closeLatch
    def receiveLatch

    ProtocolListener(closeLatch, receiveLatch) {
      this.closeLatch = closeLatch
      this.receiveLatch = receiveLatch
    }

    @Override
    void onOpen(WebSocket webSocket, Response response) {
    }

    @Override
    void onMessage(WebSocket webSocket, String text) {
      receiveLatch.countDown()
    }

    @Override
    void onMessage(WebSocket webSocket, ByteString bytes) {
      receiveLatch.countDown()
    }

    @Override
    void onClosed(WebSocket webSocket, int code, String reason) {
      closeLatch.countDown()
    }
  }
}
