package datadog.trace.core.serialization.msgpack;

import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.Mapper;
import datadog.trace.core.serialization.Writable;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.InvalidMarkException;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class VerifyThatMsgPackerDoesNotThrowInvalidMarkException {

  @Parameterized.Parameters
  public static Object[][] bufferSizes() {
    return new Object[][] {{10}, {16}, {100}, {128}, {1000}, {1024}};
  }

  private final int bufferSize;

  public VerifyThatMsgPackerDoesNotThrowInvalidMarkException(int bufferSize) {
    this.bufferSize = bufferSize;
  }

  @Test
  public void provokeInvalidMarkException() {
    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
    MsgPacker packer =
        new MsgPacker(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffer) {}
            },
            buffer);
    Mapper<byte[]> mapper =
        new Mapper<byte[]>() {
          @Override
          public void map(byte[] data, Writable packer) {
            packer.writeBinary(data, 0, data.length);
          }
        };
    int position = buffer.position();
    int length = 0;
    for (int i = 0; i < 10_000; ++i) {
      try {
        byte[] message = new byte[ThreadLocalRandom.current().nextInt(0, bufferSize * 2)];
        position = buffer.position();
        length = message.length;
        packer.format(message, mapper);
      } catch (BufferOverflowException e) {
        // acceptable, need to be able to handle data larger than the limit
      } catch (InvalidMarkException e) {
        Assert.fail(e.getMessage() + " " + position + " " + length);
      }
    }
  }
}
