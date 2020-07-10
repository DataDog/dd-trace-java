package datadog.trace.core.serialization.msgpack;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

@RunWith(Parameterized.class)
public class IntPackingTest {

  private final long[] input;

  public IntPackingTest(long[] input) {
    this.input = input;
  }

  @Parameterized.Parameters
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

  @Test
  public void packLongs() {
    ByteBuffer buffer = ByteBuffer.allocate(input.length * 9 + 10);
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                try {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(1, messageCount);
                  assertEquals(input.length, unpacker.unpackArrayHeader());
                  for (long i : input) {
                    assertEquals(i, unpacker.unpackLong());
                  }
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            buffer);
    messageFormatter.format(
        input,
        new Mapper<long[]>() {
          @Override
          public void map(long[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void packInts() {
    final int[] asInts = new int[input.length];
    for (int i = 0; i < input.length; ++i) {
      asInts[i] = (int) input[i];
    }
    ByteBuffer buffer = ByteBuffer.allocate(input.length * 5 + 10);
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                try {
                  MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(1, messageCount);
                  assertEquals(asInts.length, unpacker.unpackArrayHeader());
                  for (int i : asInts) {
                    assertEquals(i, unpacker.unpackInt());
                  }
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            buffer);

    messageFormatter.format(
        asInts,
        new Mapper<int[]>() {
          @Override
          public void map(int[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
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
}
