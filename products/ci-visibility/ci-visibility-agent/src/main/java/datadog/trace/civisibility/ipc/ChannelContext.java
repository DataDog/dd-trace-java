package datadog.trace.civisibility.ipc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Function;

class ChannelContext {
  private final ByteBuffer readBuffer;
  private final Function<ByteBuffer, ByteBuffer[]> messageProcessor;
  private final Queue<ByteBuffer> pendingResponses;

  private int currentMessageIdx;
  private byte[] currentMessage;

  ChannelContext(int bufferCapacity, Function<ByteBuffer, ByteBuffer[]> messageProcessor) {
    this.readBuffer = ByteBuffer.allocate(bufferCapacity);
    this.messageProcessor = messageProcessor;
    this.pendingResponses = new ArrayDeque<>();
  }

  void read(ByteChannel channel) throws IOException {
    int bytesRead;
    while ((bytesRead = channel.read(readBuffer)) > 0) {
      readBuffer.flip();
      processBuffer(channel);
    }
    if (bytesRead == -1) {
      channel.close();
    }
  }

  private void processBuffer(ByteChannel channel) throws IOException {
    while (readBuffer.remaining() > 0) {
      if (currentMessage == null) {
        if (readBuffer.remaining() < Integer.BYTES) {
          break;
        } else {
          int length = readBuffer.getInt();
          currentMessage = new byte[length];
        }
      }

      int length = Math.min(readBuffer.remaining(), currentMessage.length - currentMessageIdx);
      readBuffer.get(currentMessage, currentMessageIdx, length);

      currentMessageIdx += length;
      if (currentMessageIdx == currentMessage.length) {
        // processing messages is intentionally synchronous here:
        // writeResponse is invoked after message is handled
        // that way when the client receives ACK from the server
        // it knows that the server has already acted on the message.
        // this helps to avoid some race conditions
        ByteBuffer[] response = messageProcessor.apply(ByteBuffer.wrap(currentMessage));
        writeResponse(channel, response);
        currentMessageIdx = 0;
        currentMessage = null;
      }
    }

    if (readBuffer.remaining() > 0) {
      readBuffer.compact();
    } else {
      readBuffer.flip();
    }
  }

  private void writeResponse(ByteChannel channel, ByteBuffer[] response) throws IOException {
    int idx = 0;
    for (; idx < response.length; idx++) {
      int remaining = response[idx].remaining();
      if (channel.write(response[idx]) != remaining) {
        // could not write all the chunk's bytes,
        // assuming output buffer is full
        break;
      }
    }

    for (; idx < response.length; idx++) {
      pendingResponses.add(response[idx]);
    }
  }

  public void write(WritableByteChannel channel) throws IOException {
    if (pendingResponses.isEmpty()) {
      return;
    }

    while (!pendingResponses.isEmpty()) {
      ByteBuffer response = pendingResponses.peek();
      int remaining = response.remaining();
      if (channel.write(response) != remaining) {
        // could not write all the chunk's bytes,
        // assuming output buffer is full
        return;
      }
      pendingResponses.poll();
    }
  }

  boolean hasPendingResponses() {
    return !pendingResponses.isEmpty();
  }
}
