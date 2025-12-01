package com.datadog.profiling.otel.proto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProtobufEncoderTest {

  private ProtobufEncoder encoder;

  @BeforeEach
  void setUp() {
    encoder = new ProtobufEncoder();
  }

  @Test
  void writeVarintSingleByte() {
    encoder.writeVarint(0);
    assertArrayEquals(new byte[] {0}, encoder.toByteArray());
  }

  @Test
  void writeVarintSingleByteMax() {
    encoder.writeVarint(127);
    assertArrayEquals(new byte[] {127}, encoder.toByteArray());
  }

  @Test
  void writeVarintTwoBytes() {
    encoder.writeVarint(128);
    assertArrayEquals(new byte[] {(byte) 0x80, 0x01}, encoder.toByteArray());
  }

  @Test
  void writeVarint300() {
    encoder.writeVarint(300);
    // 300 = 0b100101100 = 0xAC 0x02
    assertArrayEquals(new byte[] {(byte) 0xAC, 0x02}, encoder.toByteArray());
  }

  @Test
  void writeVarintLargeValue() {
    encoder.writeVarint(0xFFFFFFFFL);
    // Max 32-bit value
    assertArrayEquals(
        new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F},
        encoder.toByteArray());
  }

  @Test
  void writeFixed64() {
    encoder.writeFixed64(0x0102030405060708L);
    // Little-endian
    assertArrayEquals(
        new byte[] {0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01}, encoder.toByteArray());
  }

  @Test
  void writeFixed32() {
    encoder.writeFixed32(0x01020304);
    // Little-endian
    assertArrayEquals(new byte[] {0x04, 0x03, 0x02, 0x01}, encoder.toByteArray());
  }

  @Test
  void writeTag() {
    encoder.writeTag(1, ProtobufEncoder.WIRETYPE_VARINT);
    // Field 1, wire type 0 = (1 << 3) | 0 = 0x08
    assertArrayEquals(new byte[] {0x08}, encoder.toByteArray());
  }

  @Test
  void writeTagField2LengthDelimited() {
    encoder.writeTag(2, ProtobufEncoder.WIRETYPE_LENGTH_DELIMITED);
    // Field 2, wire type 2 = (2 << 3) | 2 = 0x12
    assertArrayEquals(new byte[] {0x12}, encoder.toByteArray());
  }

  @Test
  void writeStringEmpty() {
    encoder.writeString("");
    // Length 0
    assertArrayEquals(new byte[] {0x00}, encoder.toByteArray());
  }

  @Test
  void writeStringNull() {
    encoder.writeString(null);
    // Length 0
    assertArrayEquals(new byte[] {0x00}, encoder.toByteArray());
  }

  @Test
  void writeStringHello() {
    encoder.writeString("hello");
    // Length 5 + "hello"
    assertArrayEquals(new byte[] {0x05, 'h', 'e', 'l', 'l', 'o'}, encoder.toByteArray());
  }

  @Test
  void writeBytes() {
    encoder.writeBytes(new byte[] {0x01, 0x02, 0x03});
    // Length 3 + bytes
    assertArrayEquals(new byte[] {0x03, 0x01, 0x02, 0x03}, encoder.toByteArray());
  }

  @Test
  void writeVarintField() {
    encoder.writeVarintField(1, 150);
    // Tag (field 1, varint) + value 150
    // 150 = 0x96 0x01
    assertArrayEquals(new byte[] {0x08, (byte) 0x96, 0x01}, encoder.toByteArray());
  }

  @Test
  void writeVarintFieldSkipsZero() {
    encoder.writeVarintField(1, 0);
    assertEquals(0, encoder.size());
  }

  @Test
  void writeStringField() {
    encoder.writeStringField(2, "test");
    // Tag (field 2, length-delimited) + length + "test"
    assertArrayEquals(new byte[] {0x12, 0x04, 't', 'e', 's', 't'}, encoder.toByteArray());
  }

  @Test
  void writeStringFieldSkipsEmpty() {
    encoder.writeStringField(2, "");
    assertEquals(0, encoder.size());
  }

  @Test
  void writeStringFieldSkipsNull() {
    encoder.writeStringField(2, null);
    assertEquals(0, encoder.size());
  }

  @Test
  void writeBoolFieldTrue() {
    encoder.writeBoolField(1, true);
    // Tag (field 1, varint) + 1
    assertArrayEquals(new byte[] {0x08, 0x01}, encoder.toByteArray());
  }

  @Test
  void writeBoolFieldFalseSkips() {
    encoder.writeBoolField(1, false);
    assertEquals(0, encoder.size());
  }

  @Test
  void writeNestedMessage() {
    encoder.writeNestedMessage(
        1,
        nested -> {
          nested.writeVarintField(1, 42);
        });
    // Tag (field 1, length-delimited) + length + nested content
    // Nested: tag 0x08 + varint 42 (0x2A) = 2 bytes
    assertArrayEquals(new byte[] {0x0A, 0x02, 0x08, 0x2A}, encoder.toByteArray());
  }

  @Test
  void writeNestedMessageEmpty() {
    encoder.writeNestedMessage(
        1,
        nested -> {
          // empty message
        });
    // Empty nested messages are not written
    assertEquals(0, encoder.size());
  }

  @Test
  void writePackedVarintFieldInts() {
    encoder.writePackedVarintField(1, new int[] {1, 2, 3});
    // Tag (field 1, length-delimited) + length + packed values
    // Values: 0x01, 0x02, 0x03 = 3 bytes
    assertArrayEquals(new byte[] {0x0A, 0x03, 0x01, 0x02, 0x03}, encoder.toByteArray());
  }

  @Test
  void writePackedVarintFieldEmpty() {
    encoder.writePackedVarintField(1, new int[0]);
    assertEquals(0, encoder.size());
  }

  @Test
  void writePackedVarintFieldNull() {
    encoder.writePackedVarintField(1, (int[]) null);
    assertEquals(0, encoder.size());
  }

  @Test
  void writePackedFixed64Field() {
    encoder.writePackedFixed64Field(1, new long[] {0x0102030405060708L});
    // Tag + length (8) + little-endian value
    assertArrayEquals(
        new byte[] {0x0A, 0x08, 0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01},
        encoder.toByteArray());
  }

  @Test
  void reset() {
    encoder.writeVarint(123);
    assertEquals(1, encoder.size());
    encoder.reset();
    assertEquals(0, encoder.size());
  }

  @Test
  void writeSignedVarintPositive() {
    encoder.writeSignedVarint(1);
    // ZigZag: 1 -> 2
    assertArrayEquals(new byte[] {0x02}, encoder.toByteArray());
  }

  @Test
  void writeSignedVarintNegative() {
    encoder.writeSignedVarint(-1);
    // ZigZag: -1 -> 1
    assertArrayEquals(new byte[] {0x01}, encoder.toByteArray());
  }

  @Test
  void writeSignedVarintNegativeTwo() {
    encoder.writeSignedVarint(-2);
    // ZigZag: -2 -> 3
    assertArrayEquals(new byte[] {0x03}, encoder.toByteArray());
  }
}
