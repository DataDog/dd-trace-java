package datadog.communication.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.communication.serialization.msgpack.MsgPackWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

public class IntPackingTest {

  public static Object[][] inputs() {
    return new Object[][] {
      {
        new long[] {
          -1,
          Long.MIN_VALUE,
          Long.MAX_VALUE,
          0,
          1,
          0x80,
          0xF,
          0xFF,
          0xFFF,
          0xFFFF,
          0xF000,
          0xFFFFF,
          0xFFFFFF,
          0xFFFFFF,
          0xFFFFFFFF,
          0xFFFFFFFFFL,
          0xFFFFFFFFFFL,
          0xFFFFFFFFFFFFL,
          0xEFEFEFEFEFEFEFEFL,
          -0xF,
          -0xFF,
          -0xFFF,
          -0xFFFF,
          -0xF000,
          -0xFFFFF,
          -0xFFFFFF,
          -0xFFFFFF,
          -0xFFFFFFFF,
          -0xFFFFFFFFFL,
          -0xFFFFFFFFFFL,
          -0xFFFFFFFFFFFFL
        }
      },
      {
        new long[] {
          -1,
          Integer.MIN_VALUE,
          Integer.MAX_VALUE,
          0,
          1,
          0x80,
          0xF,
          0xFF,
          0xFFF,
          0xFFFF,
          0xF000,
          0xFFFFF,
          0xFFFFFF,
          0xFFFFFF,
          0xFFFFFFFF,
          0xEFEFEFEF,
          -0xF,
          -0xFF,
          -0xFFF,
          -0xFFFF,
          -0xFFFFFF
        }
      },
      {random(100)},
      {random(10_000)},
      {random(100_000)},
    };
  }

  private static long[] random(int size) {
    long[] random = new long[size];
    for (int i = 0; i < random.length; ++i) {
      // yeah, JDK7...
      int signum = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
      random[i] = signum * ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE);
    }
    return random;
  }

  @ParameterizedTest
  @MethodSource("inputs")
  public void packLongs(long[] input) {
    ByteBuffer buffer = ByteBuffer.allocate(input.length * 9 + 10);
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                input.length * 9 + 10,
                (messageCount, buffy) -> {
                  try {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    assertEquals(1, messageCount);
                    assertEquals(input.length, unpacker.unpackArrayHeader());
                    for (long i : input) {
                      assertEquals(i, unpacker.unpackLong());
                    }
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    messageFormatter.format(input, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  @ParameterizedTest
  @MethodSource("inputs")
  public void packInts(long[] input) {
    final int[] asInts = new int[input.length];
    for (int i = 0; i < input.length; ++i) {
      asInts[i] = (int) input[i];
    }
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                input.length * 5 + 10,
                (messageCount, buffy) -> {
                  try {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    assertEquals(1, messageCount);
                    assertEquals(asInts.length, unpacker.unpackArrayHeader());
                    for (int i : asInts) {
                      assertEquals(i, unpacker.unpackInt());
                    }
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));

    messageFormatter.format(asInts, (x, w) -> w.writeObject(x, null));
    messageFormatter.flush();
  }

  private StreamingBuffer newBuffer(int capacity, ByteBufferConsumer consumer) {
    return new FlushingBuffer(capacity, consumer);
  }
}
