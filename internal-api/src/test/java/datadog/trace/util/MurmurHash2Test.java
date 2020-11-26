package datadog.trace.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * Adapted from
 * https://github.com/apache/commons-codec/blob/master/src/test/java/org/apache/commons/codec/digest/MurmurHash2Test.java
 */
public class MurmurHash2Test {
  /** Random input data with various length. */
  static final byte[][] input = {
    {
      (byte) 0xed,
      (byte) 0x53,
      (byte) 0xc4,
      (byte) 0xa5,
      (byte) 0x3b,
      (byte) 0x1b,
      (byte) 0xbd,
      (byte) 0xc2,
      (byte) 0x52,
      (byte) 0x7d,
      (byte) 0xc3,
      (byte) 0xef,
      (byte) 0x53,
      (byte) 0x5f,
      (byte) 0xae,
      (byte) 0x3b
    },
    {
      (byte) 0x21,
      (byte) 0x65,
      (byte) 0x59,
      (byte) 0x4e,
      (byte) 0xd8,
      (byte) 0x12,
      (byte) 0xf9,
      (byte) 0x05,
      (byte) 0x80,
      (byte) 0xe9,
      (byte) 0x1e,
      (byte) 0xed,
      (byte) 0xe4,
      (byte) 0x56,
      (byte) 0xbb
    },
    {
      (byte) 0x2b,
      (byte) 0x02,
      (byte) 0xb1,
      (byte) 0xd0,
      (byte) 0x3d,
      (byte) 0xce,
      (byte) 0x31,
      (byte) 0x3d,
      (byte) 0x97,
      (byte) 0xc4,
      (byte) 0x91,
      (byte) 0x0d,
      (byte) 0xf7,
      (byte) 0x17
    },
    {
      (byte) 0x8e,
      (byte) 0xa7,
      (byte) 0x9a,
      (byte) 0x02,
      (byte) 0xe8,
      (byte) 0xb9,
      (byte) 0x6a,
      (byte) 0xda,
      (byte) 0x92,
      (byte) 0xad,
      (byte) 0xe9,
      (byte) 0x2d,
      (byte) 0x21
    },
    {
      (byte) 0xa9,
      (byte) 0x6d,
      (byte) 0xea,
      (byte) 0x77,
      (byte) 0x06,
      (byte) 0xce,
      (byte) 0x1b,
      (byte) 0x85,
      (byte) 0x48,
      (byte) 0x27,
      (byte) 0x4c,
      (byte) 0xfe
    },
    {
      (byte) 0xec,
      (byte) 0x93,
      (byte) 0xa0,
      (byte) 0x12,
      (byte) 0x60,
      (byte) 0xee,
      (byte) 0xc8,
      (byte) 0x0a,
      (byte) 0xc5,
      (byte) 0x90,
      (byte) 0x62
    },
    {
      (byte) 0x55,
      (byte) 0x6d,
      (byte) 0x93,
      (byte) 0x66,
      (byte) 0x14,
      (byte) 0x6d,
      (byte) 0xdf,
      (byte) 0x00,
      (byte) 0x58,
      (byte) 0x99
    },
    {
      (byte) 0x3c,
      (byte) 0x72,
      (byte) 0x20,
      (byte) 0x1f,
      (byte) 0xd2,
      (byte) 0x59,
      (byte) 0x19,
      (byte) 0xdb,
      (byte) 0xa1
    },
    {
      (byte) 0x23,
      (byte) 0xa8,
      (byte) 0xb1,
      (byte) 0x87,
      (byte) 0x55,
      (byte) 0xf7,
      (byte) 0x8a,
      (byte) 0x4b,
    },
    {(byte) 0xe2, (byte) 0x42, (byte) 0x1c, (byte) 0x2d, (byte) 0xc1, (byte) 0xe4, (byte) 0x3e},
    {(byte) 0x66, (byte) 0xa6, (byte) 0xb5, (byte) 0x5a, (byte) 0x74, (byte) 0xd9},
    {(byte) 0xe8, (byte) 0x76, (byte) 0xa8, (byte) 0x90, (byte) 0x76},
    {(byte) 0xeb, (byte) 0x25, (byte) 0x3f, (byte) 0x87},
    {(byte) 0x37, (byte) 0xa0, (byte) 0xa9},
    {(byte) 0x5b, (byte) 0x5d},
    {(byte) 0x7e},
    {}
  };

  /*
   * Expected results - from the original C implementation.
   */

  /** Murmur 64bit hash results, default library seed. */
  static final long[] results64_standard = {
    0x4987cb15118a83d9l,
    0x28e2a79e3f0394d9l,
    0x8f4600d786fc5c05l,
    0xa09b27fea4b54af3l,
    0x25f34447525bfd1el,
    0x32fad4c21379c7bfl,
    0x4b30b99a9d931921l,
    0x4e5dab004f936cdbl,
    0x06825c27bc96cf40l,
    0xff4bf2f8a4823905l,
    0x7f7e950c064e6367l,
    0x821ade90caaa5889l,
    0x6d28c915d791686al,
    0x9c32649372163ba2l,
    0xd66ae956c14d5212l,
    0x38ed30ee5161200fl,
    0x9bfae0a4e613fc3cl,
  };

  /** Murmur 64bit hash results, special test seed. */
  static final long[] results64_seed = {
    0x0822b1481a92e97bl,
    0xf8a9223fef0822ddl,
    0x4b49e56affae3a89l,
    0xc970296e32e1d1c1l,
    0xe2f9f88789f1b08fl,
    0x2b0459d9b4c10c61l,
    0x377e97ea9197ee89l,
    0xd2ccad460751e0e7l,
    0xff162ca8d6da8c47l,
    0xf12e051405769857l,
    0xdabba41293d5b035l,
    0xacf326b0bb690d0el,
    0x0617f431bc1a8e04l,
    0x15b81f28d576e1b2l,
    0x28c1fe59e4f8e5bal,
    0x694dd315c9354ca9l,
    0xa97052a8f088ae6cl
  };

  /** Dummy test text. */
  static final String text = "Lorem ipsum dolor sit amet, consectetur adipisicing elit";

  @Test
  public void testHash64ByteArrayIntInt() {
    for (int i = 0; i < input.length; i++) {
      final long hash = MurmurHash2.hash64(input[i], input[i].length, 0x344d1f5c);
      if (hash != results64_seed[i]) {
        Assert.fail(
            String.format(
                "Unexpected hash64 result for example %d: 0x%016x instead of 0x%016x",
                i, hash, results64_seed[i]));
      }
    }
  }

  @Test
  public void testHash64ByteArrayInt() {
    for (int i = 0; i < input.length; i++) {
      final long hash = MurmurHash2.hash64(input[i], input[i].length);
      if (hash != results64_standard[i]) {
        Assert.fail(
            String.format(
                "Unexpected hash64 result for example %d: 0x%016x instead of 0x%016x",
                i, hash, results64_standard[i]));
      }
    }
  }

  @Test
  public void testHash64String() {
    final long hash = MurmurHash2.hash64(text);
    Assert.assertEquals(0x0920e0c1b7eeb261L, hash);
  }

  @Test
  public void testHash64StringIntInt() {
    final long hash = MurmurHash2.hash64(text, 2, text.length() - 4);
    Assert.assertEquals(0xa8b33145194985a2L, hash);
  }
}
