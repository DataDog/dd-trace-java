package datadog.communication.serialization.msgpack;

import static org.junit.Assert.*;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.Codec;
import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.Mapper;
import datadog.communication.serialization.MessageFormatter;
import datadog.communication.serialization.StreamingBuffer;
import datadog.communication.serialization.Writable;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

public class MsgPackWriterTest {

  @Test
  public void testOverflow() {
    MessageFormatter packer =
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {}
                }));
    assertFalse(
        packer.format(
            new HashMap<String, String>() {
              {
                put("foo", "abcdefghijklmnopqrstuvwxyz");
              }
            },
            new Mapper<Map<String, String>>() {

              @Override
              public void map(Map<String, String> data, Writable writable) {
                writable.writeObject(data, null);
              }
            }));
  }

  @Test
  public void testInsertAfterOverflow() {
    Mapper<String> mapper =
        new Mapper<String>() {
          @Override
          public void map(String data, Writable writable) {
            writable.writeString(data, null);
          }
        };
    MessageFormatter packer =
        new MsgPackWriter(
            newBuffer(
                2 + 25, // enough space for a 25 element string and its 2 byte header
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {}
                }));
    assertTrue("data fits in buffer", packer.format("abcdefghijklmnopqrstuvwxy", mapper));
    assertFalse(
        "data doesn't fit in finite buffer", packer.format("abcdefghijklmnopqrstuvwxyz", mapper));
    assertTrue(
        "data fits in buffer after overflow", packer.format("abcdefghijklmnopqrstuvwxy", mapper));
  }

  @Test
  public void testFlushOfOverflow() {
    final List<String> flushed = new ArrayList<>();
    Mapper<String> mapper =
        new Mapper<String>() {
          @Override
          public void map(String data, Writable writable) {
            writable.writeString(data, null);
          }
        };
    MessageFormatter packer =
        new MsgPackWriter(
            newBuffer(
                2 + 25, // enough space for a 25 element string and its 2 byte header
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    for (int i = 0; i < messageCount; ++i) {
                      try {
                        flushed.add(unpacker.unpackString());
                      } catch (Exception error) {
                        Assert.fail(error.getMessage());
                      }
                    }
                  }
                }));
    assertTrue("data fits in buffer", packer.format("abcdefghijklm", mapper));
    assertTrue(
        "data fits in empty buffer but triggers flush", packer.format("nopqrstuvwxyz", mapper));
    assertTrue(
        "data fits in buffer after overflow", packer.format("abcdefghijklmnopqrstuvwxy", mapper));
    assertEquals(2, flushed.size());
    assertEquals("abcdefghijklm", flushed.get(0));
    assertEquals("nopqrstuvwxyz", flushed.get(1));
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
    MsgPackWriter packer =
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    i.getAndIncrement();
                  }
                }));
    Mapper<Object> mapper =
        new Mapper<Object>() {
          @Override
          public void map(Object data, Writable writable) {
            writable.writeObject(data, null);
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
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      int length = unpacker.unpackBinaryHeader();
                      assertEquals(4, length);
                      assertArrayEquals(data, unpacker.readPayload(length));
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
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
  public void testWriteBinaryNoArgVariant() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    try {
                      assertEquals(unpacker.unpackBinaryHeader(), 6);
                      assertArrayEquals(
                          unpacker.readPayload(6), "foobar".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                      fail(e.getMessage());
                    }
                  }
                }));
    writer.writeBinary("foobar".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void testWriteBinaryAsObject() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      int length = unpacker.unpackBinaryHeader();
                      assertEquals(4, length);
                      assertArrayEquals(data, unpacker.readPayload(length));
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<byte[]>() {
          @Override
          public void map(byte[] ba, Writable writable) {
            writable.writeObject(ba, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteByteBuffer() {
    final byte[] data = new byte[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      int length = unpacker.unpackBinaryHeader();
                      assertEquals(4, length);
                      assertArrayEquals(data, unpacker.readPayload(length));
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
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
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      int length = unpacker.unpackBinaryHeader();
                      assertEquals(4, length);
                      assertArrayEquals(data, unpacker.readPayload(length));
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        ByteBuffer.wrap(data),
        new Mapper<ByteBuffer>() {
          @Override
          public void map(ByteBuffer bb, Writable writable) {
            writable.writeObject(bb, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteNull() {
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      unpacker.unpackNil();
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        null,
        new Mapper<Object>() {
          @Override
          public void map(Object x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteBooleanAsObject() {
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertTrue(unpacker.unpackBoolean());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        true,
        new Mapper<Boolean>() {
          @Override
          public void map(Boolean x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteBoolean() {
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertTrue(unpacker.unpackBoolean());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
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
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(data, unpacker.unpackString());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data.toCharArray(),
        new Mapper<char[]>() {
          @Override
          public void map(char[] x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteUTF8ByteString() {
    final UTF8BytesString utf8BytesString = UTF8BytesString.create("xyz");
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals("xyz", unpacker.unpackString());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        utf8BytesString,
        new Mapper<UTF8BytesString>() {
          @Override
          public void map(UTF8BytesString x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteBooleanArray() {
    final boolean[] data = new boolean[] {true, false, true, true};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                25,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(4, unpacker.unpackArrayHeader());
                      for (boolean datum : data) {
                        assertEquals(datum, unpacker.unpackBoolean());
                      }
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<boolean[]>() {
          @Override
          public void map(boolean[] x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteFloatArray() {
    final float[] data = new float[] {0.1f, 0.2f, 0.3f, 0.4f};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(4, unpacker.unpackArrayHeader());
                      for (float datum : data) {
                        assertEquals(datum, unpacker.unpackFloat(), 0.001);
                      }
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<float[]>() {
          @Override
          public void map(float[] x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteDoubleArray() {
    final double[] data = new double[] {0.1f, 0.2f, 0.3f, 0.4f};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(4, unpacker.unpackArrayHeader());
                      for (double datum : data) {
                        assertEquals(datum, unpacker.unpackDouble(), 0.001);
                      }
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<double[]>() {
          @Override
          public void map(double[] x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteLongArray() {
    final long[] data = new long[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(4, unpacker.unpackArrayHeader());
                      for (long datum : data) {
                        assertEquals(datum, unpacker.unpackLong());
                      }
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<long[]>() {
          @Override
          public void map(long[] x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteIntArray() {
    final int[] data = new int[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(4, unpacker.unpackArrayHeader());
                      for (int datum : data) {
                        assertEquals(datum, unpacker.unpackInt());
                      }
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<int[]>() {
          @Override
          public void map(int[] x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteShortArray() {
    final short[] data = new short[] {1, 2, 3, 4};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(4, unpacker.unpackArrayHeader());
                      for (short datum : data) {
                        assertEquals(datum, unpacker.unpackInt());
                      }
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<short[]>() {
          @Override
          public void map(short[] x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteLongBoxed() {
    final long data = 1234L;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(data, unpacker.unpackLong());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<Long>() {
          @Override
          public void map(Long x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteLongPrimitive() {
    final long data = 1234L;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(data, unpacker.unpackLong());
                      assertEquals(data, unpacker.unpackLong());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<Long>() {
          @Override
          public void map(Long x, Writable w) {
            w.writeLong(x);
            w.writeSignedLong(x);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteIntBoxed() {
    final int data = 1234;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(data, unpacker.unpackInt());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<Integer>() {
          @Override
          public void map(Integer x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteIntPrimitive() {
    final int data = 1234;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(data, unpacker.unpackInt());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
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
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(data, unpacker.unpackInt());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<Short>() {
          @Override
          public void map(Short x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testUnknownObject() {
    final Object data = Codec.INSTANCE;
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(data.toString(), unpacker.unpackString());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<Object>() {
          @Override
          public void map(Object x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteObjectArray() {
    final Object[] data = new Object[] {"foo", "bar"};
    MessageFormatter messageFormatter =
        new MsgPackWriter(
            newBuffer(
                100,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffy) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    try {
                      assertEquals(data.length, unpacker.unpackArrayHeader());
                      assertEquals(data[0].toString(), unpacker.unpackString());
                      assertEquals(data[1].toString(), unpacker.unpackString());
                    } catch (IOException e) {
                      Assert.fail(e.getMessage());
                    }
                  }
                }));
    messageFormatter.format(
        data,
        new Mapper<Object[]>() {
          @Override
          public void map(Object[] x, Writable w) {
            w.writeObject(x, null);
          }
        });
    messageFormatter.flush();
  }

  @Test
  public void testWriteStringUTF8BytesString() {
    UTF8BytesString value = UTF8BytesString.create("foobár");
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                20,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    try {
                      assertEquals(unpacker.unpackString(), "foobár");
                      assertEquals(unpacker.unpackString(), "foobár");
                    } catch (IOException e) {
                      fail(e.getMessage());
                    }
                  }
                }));
    writer.writeObjectString(value, null);
    writer.writeString(value, null);
  }

  @Test
  public void testWriteStringNull() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                20,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    try {
                      unpacker.unpackNil();
                      unpacker.unpackNil();
                    } catch (IOException e) {
                      fail(e.getMessage());
                    }
                  }
                }));
    writer.writeObjectString(null, null);
    writer.writeString(null, null);
  }

  @Test
  public void testWriteObjectStringGeneralPath() {
    Object value =
        new Object() {
          @Override
          public String toString() {
            return "foobár";
          }
        };
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                40,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    try {
                      assertEquals(unpacker.unpackString(), "foobár");
                      assertEquals(unpacker.unpackString(), "foobàr");
                    } catch (IOException e) {
                      fail(e.getMessage());
                    }
                  }
                }));
    writer.writeObjectString(value, null);
    writer.writeObjectString(
        value,
        new EncodingCache() {
          @Override
          public byte[] encode(CharSequence s) {
            return "foobàr".getBytes(StandardCharsets.UTF_8);
          }
        });
  }

  @Test
  public void testWriteStringGeneralCharSequence() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    try {
                      assertEquals(unpacker.unpackString(), "foobár");
                    } catch (IOException e) {
                      fail(e.getMessage());
                    }
                  }
                }));
    CharBuffer charSeq = CharBuffer.wrap("foobár");
    writer.writeString(charSeq, null);
  }

  @Test
  public void testWriteStringEncodingCache() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    try {
                      assertEquals(unpacker.unpackString(), "foobár");
                    } catch (IOException e) {
                      fail(e.getMessage());
                    }
                  }
                }));
    writer.writeString(
        "",
        new EncodingCache() {
          @Override
          public byte[] encode(CharSequence s) {
            return "foobár".getBytes(StandardCharsets.UTF_8);
          }
        });
  }

  @Test
  public void testStartArray() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    try {
                      assertEquals(unpacker.unpackArrayHeader(), 1);
                      assertEquals(unpacker.unpackArrayHeader(), 0xFFFF);
                      assertEquals(unpacker.unpackArrayHeader(), 0x10000);
                      assertEquals(unpacker.unpackArrayHeader(), 1);
                    } catch (IOException e) {
                      fail(e.getMessage());
                    }
                  }
                }));
    writer.startArray(1);
    writer.startArray(0xFFFF);
    writer.startArray(0x10000);
    writer.startStruct(1);
  }

  @Test
  public void testStartMap() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    try {
                      assertEquals(unpacker.unpackMapHeader(), 1);
                      assertEquals(unpacker.unpackMapHeader(), 0xFFFF);
                      assertEquals(unpacker.unpackMapHeader(), 0x10000);
                    } catch (IOException e) {
                      fail(e.getMessage());
                    }
                  }
                }));
    writer.startMap(1);
    writer.startMap(0xFFFF);
    writer.startMap(0x10000);
  }

  @Test
  public void testStartStringHeader() {
    MsgPackWriter writer =
        new MsgPackWriter(
            newBuffer(
                10,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
                    try {
                      assertEquals(unpacker.unpackRawStringHeader(), 1);
                      assertEquals(unpacker.unpackRawStringHeader(), 0xFFFF);
                      assertEquals(unpacker.unpackRawStringHeader(), 0x10000);
                    } catch (IOException e) {
                      fail(e.getMessage());
                    }
                  }
                }));
    writer.writeStringHeader(1);
    writer.writeStringHeader(0xFFFF);
    writer.writeStringHeader(0x10000);
  }

  private StreamingBuffer newBuffer(int capacity, ByteBufferConsumer consumer) {
    return new FlushingBuffer(capacity, consumer);
  }
}
