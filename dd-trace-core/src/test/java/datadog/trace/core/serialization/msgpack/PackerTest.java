package datadog.trace.core.serialization.msgpack;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

public class PackerTest {

  @Test(expected = BufferOverflowException.class)
  public void testOverflow() {
    MessageFormatter packer =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffer) {}
            },
            ByteBuffer.allocate(25));
    packer.format(
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
    Packer packer =
        new Packer(
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
    packer.format(map, mapper);
    packer.format(map, mapper);
    packer.format(map, mapper);
    packer.format(map, mapper);
    assertEquals(1, i.getAndIncrement());
  }

  @Test
  public void testWriteBinary() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  int length = unpacker.unpackBinaryHeader();
                  assertEquals(4, length);
                  assertArrayEquals(data, unpacker.readPayload(length));
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
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
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  int length = unpacker.unpackBinaryHeader();
                  assertEquals(4, length);
                  assertArrayEquals(data, unpacker.readPayload(length));
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
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
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  int length = unpacker.unpackBinaryHeader();
                  assertEquals(4, length);
                  assertArrayEquals(data, unpacker.readPayload(length));
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
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
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  int length = unpacker.unpackBinaryHeader();
                  assertEquals(4, length);
                  assertArrayEquals(data, unpacker.readPayload(length));
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(25));
    messageFormatter.format(
        ByteBuffer.wrap(data),
        new Mapper<ByteBuffer>() {
          @Override
          public void map(ByteBuffer bb, Writable writable) {
            writable.writeObject(bb, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteNull() {
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  unpacker.unpackNil();
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(25));
    messageFormatter.format(
        null,
        new Mapper<Object>() {
          @Override
          public void map(Object x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteBooleanAsObject() {
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertTrue(unpacker.unpackBoolean());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(25));
    messageFormatter.format(
        true,
        new Mapper<Boolean>() {
          @Override
          public void map(Boolean x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteBoolean() {
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertTrue(unpacker.unpackBoolean());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(25));
    messageFormatter.format(
        true,
        new Mapper<Boolean>() {
          @Override
          public void map(Boolean x, Writable w) {
            w.writeBoolean(x);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteCharArray() {
    final String data = "xyz";
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(data, unpacker.unpackString());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(25));
    messageFormatter.format(
        data.toCharArray(),
        new Mapper<char[]>() {
          @Override
          public void map(char[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteBooleanArray() {
    final boolean[] data = new boolean[] {true, false, true, true};
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(4, unpacker.unpackArrayHeader());
                  for (boolean datum : data) {
                    assertEquals(datum, unpacker.unpackBoolean());
                  }
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(25));
    messageFormatter.format(
        data,
        new Mapper<boolean[]>() {
          @Override
          public void map(boolean[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteFloatArray() {
    final float[] data = new float[] {0.1f, 0.2f, 0.3f, 0.4f};
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(4, unpacker.unpackArrayHeader());
                  for (float datum : data) {
                    assertEquals(datum, unpacker.unpackFloat(), 0.001);
                  }
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<float[]>() {
          @Override
          public void map(float[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteDoubleArray() {
    final double[] data = new double[] {0.1f, 0.2f, 0.3f, 0.4f};
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(4, unpacker.unpackArrayHeader());
                  for (double datum : data) {
                    assertEquals(datum, unpacker.unpackDouble(), 0.001);
                  }
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<double[]>() {
          @Override
          public void map(double[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteLongArray() {
    final long[] data = new long[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(4, unpacker.unpackArrayHeader());
                  for (long datum : data) {
                    assertEquals(datum, unpacker.unpackLong());
                  }
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<long[]>() {
          @Override
          public void map(long[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteIntArray() {
    final int[] data = new int[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(4, unpacker.unpackArrayHeader());
                  for (int datum : data) {
                    assertEquals(datum, unpacker.unpackInt());
                  }
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<int[]>() {
          @Override
          public void map(int[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteShortArray() {
    final short[] data = new short[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(4, unpacker.unpackArrayHeader());
                  for (short datum : data) {
                    assertEquals(datum, unpacker.unpackInt());
                  }
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<short[]>() {
          @Override
          public void map(short[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteLongBoxed() {
    final long data = 1234L;
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(data, unpacker.unpackLong());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<Long>() {
          @Override
          public void map(Long x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteLongPrimitive() {
    final long data = 1234L;
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(data, unpacker.unpackLong());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<Long>() {
          @Override
          public void map(Long x, Writable w) {
            w.writeLong(x);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteIntBoxed() {
    final int data = 1234;
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(data, unpacker.unpackInt());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<Integer>() {
          @Override
          public void map(Integer x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteIntPrimitive() {
    final int data = 1234;
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(data, unpacker.unpackInt());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<Integer>() {
          @Override
          public void map(Integer x, Writable w) {
            w.writeInt(x);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteShortBoxed() {
    final short data = 1234;
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(data, unpacker.unpackInt());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<Short>() {
          @Override
          public void map(Short x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testUnknownObject() {
    final Object data = Codec.INSTANCE;
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(data.toString(), unpacker.unpackString());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<Object>() {
          @Override
          public void map(Object x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteObjectArray() {
    final Object[] data = new Object[] {"foo", "bar"};
    MessageFormatter messageFormatter =
        new Packer(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffy) {
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                try {
                  assertEquals(1, unpacker.unpackArrayHeader());
                  assertEquals(data.length, unpacker.unpackArrayHeader());
                  assertEquals(data[0].toString(), unpacker.unpackString());
                  assertEquals(data[1].toString(), unpacker.unpackString());
                } catch (IOException e) {
                  Assert.fail(e.getMessage());
                }
              }
            },
            ByteBuffer.allocate(100));
    messageFormatter.format(
        data,
        new Mapper<Object[]>() {
          @Override
          public void map(Object[] x, Writable w) {
            w.writeObject(x, EncodingCachingStrategies.NO_CACHING);
          }
        });
    messageFormatter.flush();
  }
}
