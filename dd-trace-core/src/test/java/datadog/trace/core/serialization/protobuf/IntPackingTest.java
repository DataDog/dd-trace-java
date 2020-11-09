package datadog.trace.core.serialization.protobuf;

import static datadog.trace.core.serialization.protobuf.CompactRepeatedFieldHelper.verifyCompactVarints;

import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.EncodingCachingStrategies;
import datadog.trace.core.serialization.Mapper;
import datadog.trace.core.serialization.MessageFormatter;
import datadog.trace.core.serialization.Writable;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

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
      {random(1000)}
    };
  }

  @Test
  public void packLongs() {
    final ByteBuffer buffer = ByteBuffer.allocate(input.length * 10 + 5);
    MessageFormatter messageFormatter =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyCompactVarints(input, buffy);
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
    final ByteBuffer buffer = ByteBuffer.allocate(input.length * 5 + 10);
    MessageFormatter messageFormatter =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyCompactVarints(asInts, buffy);
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
