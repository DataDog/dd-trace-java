package datadog.trace.core.serialization.protobuf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.Assert;

public class CompactRepeatedFieldHelper {

  public static void verifyCompactFloats(float[] expected, ByteBuffer formatted) {
    try {
      UnknownFieldSet parsed = UnknownFieldSet.parseFrom(ByteString.copyFrom(formatted));
      assertTrue(parsed.hasField(1));
      assertFalse(parsed.hasField(2));
      List<ByteString> arrays = parsed.getField(1).getLengthDelimitedList();
      assertEquals(1, arrays.size());
      ByteBuffer floats = arrays.get(0).asReadOnlyByteBuffer();
      for (float l : expected) {
        if (l != 0) {
          assertEquals(l, floats.getFloat(), 1e-7);
        }
      }
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    }
  }

  public static void verifyCompactDoubles(double[] expected, ByteBuffer formatted) {
    try {
      UnknownFieldSet parsed = UnknownFieldSet.parseFrom(ByteString.copyFrom(formatted));
      assertTrue(parsed.hasField(1));
      assertFalse(parsed.hasField(2));
      List<ByteString> arrays = parsed.getField(1).getLengthDelimitedList();
      assertEquals(1, arrays.size());
      ByteBuffer floats = arrays.get(0).asReadOnlyByteBuffer();
      for (double l : expected) {
        if (l != 0) {
          assertEquals(l, floats.getDouble(), 1e-7);
        }
      }
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    }
  }

  public static void verifyCompactVarints(long[] expected, ByteBuffer formatted) {
    try {
      UnknownFieldSet parsed = UnknownFieldSet.parseFrom(ByteString.copyFrom(formatted));
      assertTrue(parsed.hasField(1));
      assertFalse(parsed.hasField(2));
      List<ByteString> arrays = parsed.getField(1).getLengthDelimitedList();
      assertEquals(1, arrays.size());
      ByteBuffer varints = arrays.get(0).asReadOnlyByteBuffer();
      for (long l : expected) {
        if (l != 0) {
          assertEquals(l, nextVarInt64(varints));
        }
      }
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    }
  }

  public static void verifyCompactVarints(int[] expected, ByteBuffer formatted) {
    try {
      UnknownFieldSet parsed = UnknownFieldSet.parseFrom(ByteString.copyFrom(formatted));
      assertTrue(parsed.hasField(1));
      assertFalse(parsed.hasField(2));
      List<ByteString> arrays = parsed.getField(1).getLengthDelimitedList();
      assertEquals(1, arrays.size());
      ByteBuffer varints = arrays.get(0).asReadOnlyByteBuffer();
      for (int l : expected) {
        if (l != 0) {
          assertEquals(l, nextVarInt32(varints));
        }
      }
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    }
  }

  public static void verifyCompactVarints(short[] expected, ByteBuffer formatted) {
    try {
      UnknownFieldSet parsed = UnknownFieldSet.parseFrom(ByteString.copyFrom(formatted));
      assertTrue(parsed.hasField(1));
      assertFalse(parsed.hasField(2));
      List<ByteString> arrays = parsed.getField(1).getLengthDelimitedList();
      assertEquals(1, arrays.size());
      ByteBuffer varints = arrays.get(0).asReadOnlyByteBuffer();
      for (short l : expected) {
        if (l != 0) {
          assertEquals(l, nextVarInt32(varints));
        }
      }
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    }
  }

  static int nextVarInt32(ByteBuffer src) {
    int acc;
    if ((acc = src.get()) >= 0) {
      return acc;
    }
    int varint = acc & 0x7f;
    if ((acc = src.get()) >= 0) {
      varint |= acc << 7;
    } else {
      varint |= (acc & 0x7f) << 7;
      if ((acc = src.get()) >= 0) {
        varint |= acc << 14;
      } else {
        varint |= (acc & 0x7f) << 14;
        if ((acc = src.get()) >= 0) {
          varint |= acc << 21;
        } else {
          varint |= (acc & 0x7f) << 21;
          varint |= (acc = src.get()) << 28;
          while (acc < 0) {
            acc = src.get();
          }
        }
      }
    }
    return varint;
  }

  static long nextVarInt64(ByteBuffer src) {
    long acc;
    if ((acc = src.get()) >= 0) {
      return acc;
    }
    long varint = acc & 0x7f;
    if ((acc = src.get()) >= 0) {
      varint |= acc << 7;
    } else {
      varint |= (acc & 0x7f) << 7;
      if ((acc = src.get()) >= 0) {
        varint |= acc << 14;
      } else {
        varint |= (acc & 0x7f) << 14;
        if ((acc = src.get()) >= 0) {
          varint |= acc << 21;
        } else {
          varint |= (acc & 0x7f) << 21;
          if ((acc = src.get()) >= 0) {
            varint |= acc << 28;
          } else {
            varint |= (acc & 0x7f) << 28;
            if ((acc = src.get()) >= 0) {
              varint |= acc << 35;
            } else {
              varint |= (acc & 0x7f) << 35;
              if ((acc = src.get()) >= 0) {
                varint |= acc << 42;
              } else {
                varint |= (acc & 0x7f) << 42;
                if ((acc = src.get()) >= 0) {
                  varint |= acc << 49;
                } else {
                  varint |= (acc & 0x7f) << 49;
                  if ((acc = src.get()) >= 0) {
                    varint |= acc << 56;
                  } else {
                    varint |= (acc & 0x7f) << 56;
                    varint |= ((long) src.get()) << 63;
                  }
                }
              }
            }
          }
        }
      }
    }
    return varint;
  }
}
