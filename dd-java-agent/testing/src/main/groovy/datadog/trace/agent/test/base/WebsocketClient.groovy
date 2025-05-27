package datadog.trace.agent.test.base

import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

interface WebsocketClient {
  void connect(String url)
  void send(String text)
  void send(byte[] bytes)
  void close(int code, String reason)
  boolean supportMessageChunks()
  void setSplitChunksAfter(int size)
}

class OkHttpWebsocketClient implements WebsocketClient {
  WebSocket session

  @Override
  void connect(String url) {
    session = OkHttpUtils.client().newWebSocket(new Request.Builder().url(url).get().build(), new WebSocketListener() {})
  }

  @Override
  void send(String text) {
    session.send(text)
  }

  @Override
  void send(byte[] bytes) {
    session.send(ByteString.of(bytes))
  }

  @Override
  void close(int code, String reason) {
    session.close(code, reason)
  }

  @Override
  boolean supportMessageChunks() {
    false
  }

  @Override
  void setSplitChunksAfter(int size) {
    // not supported
  }
}
