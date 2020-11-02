package datadog.trace.core.serialization.protobuf;

import static datadog.trace.core.serialization.protobuf.CompactRepeatedFieldHelper.verifyCompactDoubles;
import static datadog.trace.core.serialization.protobuf.CompactRepeatedFieldHelper.verifyCompactFloats;
import static datadog.trace.core.serialization.protobuf.CompactRepeatedFieldHelper.verifyCompactVarints;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.Codec;
import datadog.trace.core.serialization.EncodingCachingStrategies;
import datadog.trace.core.serialization.Mapper;
import datadog.trace.core.serialization.Writable;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class ProtobufWriterTest {

  @Test(expected = BufferOverflowException.class)
  public void testOverflow() {
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffer) {}
            },
            ByteBuffer.allocate(25));
    writer.format(
        new HashMap<String, String>() {
          {
            put("foo", "abcdefghijklmnopqrstuvwxyz");
          }
        },
        new Mapper<Map<String, String>>() {

          @Override
          public void map(Map<String, String> data, Writable writable) {
            writable.writeObject(data, EncodingCachingStrategies.NO_CACHING);
          }
        });
  }

  @Test
  public void testRecycle() {
    final AtomicInteger i = new AtomicInteger();
    Map<String, String> map =
        new HashMap<String, String>() {
          {
            put("foo", "abcd");
          }
        };
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffer) {
                i.getAndIncrement();
              }
            },
            ByteBuffer.allocate(25));
    Mapper<Object> mapper =
        new Mapper<Object>() {
          @Override
          public void map(Object data, Writable writable) {
            writable.writeObject(data, EncodingCachingStrategies.NO_CACHING);
          }
        };
    writer.format(map, mapper); // no overflow
    writer.format(map, mapper); // overflow, writes overflowing message after flush
    writer.format(map, mapper); // overflow, writes overflowing message after flush
    assertEquals(2, i.getAndIncrement());
  }

  private static void verifyBytes(byte[] expected, ByteBuffer formatted) {
    try {
      UnknownFieldSet protobuf = UnknownFieldSet.parseFrom(ByteString.copyFrom(formatted));
      assertTrue(protobuf.hasField(1));
      assertFalse(protobuf.hasField(2));
      List<ByteString> lengthDelimiteds = protobuf.getField(1).getLengthDelimitedList();
      assertEquals(1, lengthDelimiteds.size());
      assertEquals(ByteString.copyFrom(expected), lengthDelimiteds.get(0));
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    }
  }

  @Test
  public void testWriteBinary() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    ProtobufWriter messageFormatter =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyBytes(data, buffy);
              }
            },
            ByteBuffer.allocate(25));
    messageFormatter.format(
        data,
        new Mapper<byte[]>() {
          @Override
          public void map(byte[] data, Writable writable) {
            writable.writeBinary(data, 0, data.length);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteBinaryAsObject() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    ProtobufWriter messageFormatter =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyBytes(data, buffy);
              }
            },
            ByteBuffer.allocate(25));
    messageFormatter.format(
        data,
        new Mapper<byte[]>() {
          @Override
          public void map(byte[] ba, Writable writable) {
            writable.writeObject(ba, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteByteBuffer() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    ProtobufWriter messageFormatter =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyBytes(data, buffy);
              }
            },
            ByteBuffer.allocate(25));
    messageFormatter.format(
        ByteBuffer.wrap(data),
        new Mapper<ByteBuffer>() {
          @Override
          public void map(ByteBuffer bb, Writable writable) {
            writable.writeBinary(bb);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteByteBufferAsObject() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyBytes(data, buffy);
              }
            },
            ByteBuffer.allocate(25));
    writer.format(
        ByteBuffer.wrap(data),
        new Mapper<ByteBuffer>() {
          @Override
          public void map(ByteBuffer bb, Writable writable) {
            writable.writeObject(bb, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteNull() {
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                assertFalse(buffy.hasRemaining());
              }
            },
            ByteBuffer.allocate(25));
    writer.format(
        null,
        new Mapper<Object>() {
          @Override
          public void map(Object x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  private static void verifyBoolean(ByteBuffer formatted) {
    try {
      UnknownFieldSet protobuf = UnknownFieldSet.parseFrom(ByteString.copyFrom(formatted));
      assertTrue(protobuf.hasField(1));
      assertFalse(protobuf.hasField(2));
      List<Long> varints = protobuf.getField(1).getVarintList();
      assertEquals(1, varints.get(0).intValue());
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    }
  }

  @Test
  public void testWriteBooleanAsObject() {
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyBoolean(buffy);
              }
            },
            ByteBuffer.allocate(25));
    writer.format(
        true,
        new Mapper<Boolean>() {
          @Override
          public void map(Boolean x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteBoolean() {
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyBoolean(buffy);
              }
            },
            ByteBuffer.allocate(25));
    writer.format(
        true,
        new Mapper<Boolean>() {
          @Override
          public void map(Boolean x, Writable w) {
            w.writeBoolean(x);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteCharArray() {
    final String data = "xyz";
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyBytes(data.getBytes(StandardCharsets.UTF_8), buffy);
              }
            },
            ByteBuffer.allocate(25));
    writer.format(
        data.toCharArray(),
        new Mapper<char[]>() {
          @Override
          public void map(char[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteUTF8ByteString() {
    final UTF8BytesString utf8BytesString = UTF8BytesString.create("xyz");
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyBytes(utf8BytesString.toString().getBytes(StandardCharsets.UTF_8), buffy);
              }
            },
            ByteBuffer.allocate(25));
    writer.format(
        utf8BytesString,
        new Mapper<UTF8BytesString>() {
          @Override
          public void map(UTF8BytesString x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteBooleanArray() {
    final boolean[] data = new boolean[] {true, false, true, true};
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyCompactVarints(new int[] {1, 1, 1}, buffy);
              }
            },
            ByteBuffer.allocate(25));
    writer.format(
        data,
        new Mapper<boolean[]>() {
          @Override
          public void map(boolean[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteFloatArray() {
    final float[] data = new float[] {0.1f, 0.2f, 0.3f, 0.4f};
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyCompactFloats(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<float[]>() {
          @Override
          public void map(float[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteDoubleArray() {
    final double[] data = new double[] {0.1f, 0.2f, 0.3f, 0.4f};
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyCompactDoubles(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<double[]>() {
          @Override
          public void map(double[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteLongArray() {
    final long[] data = new long[] {1, 2, 3, 4};
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyCompactVarints(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<long[]>() {
          @Override
          public void map(long[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteIntArray() {
    final int[] data = new int[] {1, 2, 3, 4};
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyCompactVarints(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<int[]>() {
          @Override
          public void map(int[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteShortArray() {
    final short[] data = new short[] {1, 2, 3, 4};
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyCompactVarints(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<short[]>() {
          @Override
          public void map(short[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  private static void verify(long expected, ByteBuffer formatted) {
    try {
      UnknownFieldSet protobuf = UnknownFieldSet.parseFrom(ByteString.copyFrom(formatted));
      assertTrue(protobuf.hasField(1));
      assertFalse(protobuf.hasField(2));
      List<Long> varints = protobuf.getField(1).getVarintList();
      assertEquals(1, varints.size());
      assertEquals(expected, varints.get(0).longValue());
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    }
  }

  @Test
  public void testWriteLongBoxed() {
    final long data = 1234L;
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verify(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<Long>() {
          @Override
          public void map(Long x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteLongPrimitive() {
    final long data = 1234L;
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verify(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<Long>() {
          @Override
          public void map(Long x, Writable w) {
            w.writeLong(x);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteIntBoxed() {
    final int data = 1234;
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verify(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<Integer>() {
          @Override
          public void map(Integer x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteIntPrimitive() {
    final int data = 1234;
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verify(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<Integer>() {
          @Override
          public void map(Integer x, Writable w) {
            w.writeInt(x);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteShortBoxed() {
    final short data = 1234;
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verify(data, buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<Short>() {
          @Override
          public void map(Short x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testUnknownObject() {
    final Object data = Codec.INSTANCE;
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                verifyBytes(data.toString().getBytes(StandardCharsets.UTF_8), buffy);
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<Object>() {
          @Override
          public void map(Object x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }

  @Test
  public void testWriteObjectArray() {
    final Object[] data = new Object[] {"foo", "bar"};
    ProtobufWriter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                try {
                  UnknownFieldSet protobuf = UnknownFieldSet.parseFrom(ByteString.copyFrom(buffy));
                  assertTrue(protobuf.hasField(1));
                  assertFalse(protobuf.hasField(2));
                  List<ByteString> lengthDelimiteds = protobuf.getField(1).getLengthDelimitedList();
                  assertEquals(1, lengthDelimiteds.size());
                  UnknownFieldSet array = UnknownFieldSet.parseFrom(lengthDelimiteds.get(0));
                  UnknownFieldSet.Field field = array.getField(1);
                  assertEquals(data.length, field.getLengthDelimitedList().size());
                  for (int i = 0; i < data.length; ++i) {
                    assertEquals(
                        String.valueOf(data[i]),
                        field.getLengthDelimitedList().get(i).toString(StandardCharsets.UTF_8));
                  }
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    writer.format(
        data,
        new Mapper<Object[]>() {
          @Override
          public void map(Object[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    writer.flush();
  }
}
