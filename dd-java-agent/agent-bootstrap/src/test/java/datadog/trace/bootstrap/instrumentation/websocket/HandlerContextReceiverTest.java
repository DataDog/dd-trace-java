package datadog.trace.bootstrap.instrumentation.websocket;

import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_BINARY;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class HandlerContextReceiverTest {

  @Test
  void sizeCalculatorIsChosenPerMessage() {
    HandlerContext.Receiver receiver = newReceiver();

    receiver.recordChunkData("hello", false);
    assertEquals(MESSAGE_TYPE_TEXT, receiver.getMessageType());
    assertEquals(5, receiver.getMsgSize());
    receiver.reset();

    receiver.recordChunkData(new byte[] {1, 2, 3}, false);
    assertEquals(MESSAGE_TYPE_BINARY, receiver.getMessageType());
    assertEquals(3, receiver.getMsgSize());
    receiver.reset();

    receiver.recordChunkData(ByteBuffer.allocate(7), false);
    assertEquals(MESSAGE_TYPE_BINARY, receiver.getMessageType());
    assertEquals(7, receiver.getMsgSize());
    receiver.reset();

    receiver.recordChunkData("hi", false);
    assertEquals(MESSAGE_TYPE_TEXT, receiver.getMessageType());
    assertEquals(2, receiver.getMsgSize());
  }

  @Test
  void sizeAccumulatesAcrossChunksOfOneMessage() {
    HandlerContext.Receiver receiver = newReceiver();

    receiver.recordChunkData("abc", true);
    receiver.recordChunkData("de", true);
    assertEquals(MESSAGE_TYPE_TEXT, receiver.getMessageType());
    assertEquals(5, receiver.getMsgSize());
    assertEquals(2, receiver.getMsgChunks());
  }

  private static HandlerContext.Receiver newReceiver() {
    AgentSpan handshakeSpan = mock(AgentSpan.class);
    when(handshakeSpan.getResourceName()).thenReturn("GET /ws");
    return new HandlerContext.Receiver(handshakeSpan, "session-1");
  }
}
