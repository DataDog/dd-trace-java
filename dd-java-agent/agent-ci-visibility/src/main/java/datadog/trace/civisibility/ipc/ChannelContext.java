package datadog.trace.civisibility.ipc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.function.Consumer;

class ChannelContext {
  private static final byte ACK = 1;
  private static final int BYTES_USED_FOR_MESSAGE_LENGTH = 2;
  private final ByteBuffer readBuffer;
  private final Consumer<byte[]> messageCallback;

  private int currentMessageIdx;
  private byte[] currentMessage;
  private int unacknowledgedMessages;

  ChannelContext(int bufferCapacity, Consumer<byte[]> messageCallback) {
    this.readBuffer = ByteBuffer.allocate(bufferCapacity);
    this.messageCallback = messageCallback;
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
        if (readBuffer.remaining() < BYTES_USED_FOR_MESSAGE_LENGTH) {
          break;
        } else {
          int length = readBuffer.getShort() & 0xFFFF;
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
        messageCallback.accept(currentMessage);
        writeResponse(channel);
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

  private void writeResponse(ByteChannel channel) throws IOException {
    ByteBuffer response = ByteBuffer.wrap(new byte[] {ACK});
    if (channel.write(response) != 1) {
      unacknowledgedMessages++;
    }
  }

  public void write(WritableByteChannel channel) throws IOException {
    if (unacknowledgedMessages == 0) {
      return;
    }

    byte[] acks = new byte[unacknowledgedMessages];
    Arrays.fill(acks, ACK);
    ByteBuffer response = ByteBuffer.wrap(acks);
    int written = channel.write(response);
    if (written >= 0) {
      unacknowledgedMessages -= written;
    }
  }
}
