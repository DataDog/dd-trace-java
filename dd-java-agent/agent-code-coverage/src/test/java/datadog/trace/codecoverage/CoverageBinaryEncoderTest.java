package datadog.trace.codecoverage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.coverage.CoverageKey;
import datadog.trace.coverage.LinesCoverage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoverageBinaryEncoderTest {

  // --- uvarint encoding ---

  @Test
  void uvarintZero() throws IOException {
    assertUvarint(0, new byte[] {0x00});
  }

  @Test
  void uvarintSingleByte() throws IOException {
    assertUvarint(1, new byte[] {0x01});
    assertUvarint(0x7F, new byte[] {0x7F});
  }

  @Test
  void uvarintTwoBytes() throws IOException {
    // 128 = 0x80 → low 7 bits = 0x00 with continuation, then 0x01
    assertUvarint(128, new byte[] {(byte) 0x80, 0x01});
    // 16383 = 0x3FFF → 0xFF, 0x7F
    assertUvarint(16383, new byte[] {(byte) 0xFF, 0x7F});
  }

  @Test
  void uvarintThreeBytes() throws IOException {
    // 16384 = 0x4000 → 0x80, 0x80, 0x01
    assertUvarint(16384, new byte[] {(byte) 0x80, (byte) 0x80, 0x01});
  }

  @Test
  void uvarintLargeValue() throws IOException {
    // 300 = 0x12C → low 7: 0x2C | 0x80 = 0xAC, remaining 2 → 0x02
    assertUvarint(300, new byte[] {(byte) 0xAC, 0x02});
  }

  // --- Empty coverage map ---

  @Test
  void emptyMapProducesHeaderOnly() throws IOException {
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    byte[] result = encode(coverage);
    // version=1, num_extra_fields=1, num_records=0
    assertArrayEquals(new byte[] {0x01, 0x01, 0x00}, result);
  }

  // --- Single record with empty BitSets ---

  @Test
  void singleRecordEmptyLines() throws IOException {
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    coverage.put(new CoverageKey("A.java", "A"), new LinesCoverage());

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01); // version
    expected.write(0x01); // num_extra_fields
    expected.write(0x01); // num_records = 1
    writeExpectedString("A.java", expected);
    writeExpectedString("A", expected);
    expected.write(0x00); // bitvec_byte_count = 0, no bit vector data

    assertArrayEquals(expected.toByteArray(), result);
  }

  // --- Bit vector encoding ---

  @Test
  void singleLineSet() throws IOException {
    // Line 1 only: byte_count = (1>>3)+1 = 1, exec byte 0 = 0x02, cov byte 0 = 0x02
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    LinesCoverage lc = new LinesCoverage();
    lc.executableLines.set(1);
    lc.coveredLines.set(1);
    coverage.put(new CoverageKey("X.java", "X"), lc);

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01); // version
    expected.write(0x01); // num_extra_fields
    expected.write(0x01); // num_records
    writeExpectedString("X.java", expected);
    writeExpectedString("X", expected);
    expected.write(0x01); // bitvec_byte_count = 1
    expected.write(0x02); // executable: line 1 → bit 1 of byte 0
    expected.write(0x02); // covered: line 1 → bit 1 of byte 0

    assertArrayEquals(expected.toByteArray(), result);
  }

  @Test
  void linesSpanMultipleBytes() throws IOException {
    // Lines {1, 8}: max_line=8, byte_count=(8>>3)+1=2
    // Line 1: byte 0, bit 1 → 0x02
    // Line 8: byte 1, bit 0 → 0x01
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    LinesCoverage lc = new LinesCoverage();
    lc.executableLines.set(1);
    lc.executableLines.set(8);
    lc.coveredLines.set(8);
    coverage.put(new CoverageKey("F.java", "F"), lc);

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01);
    expected.write(0x01);
    expected.write(0x01);
    writeExpectedString("F.java", expected);
    writeExpectedString("F", expected);
    expected.write(0x02); // bitvec_byte_count = 2
    expected.write(0x02); // exec byte 0: line 1
    expected.write(0x01); // exec byte 1: line 8
    expected.write(0x00); // cov byte 0: no lines
    expected.write(0x01); // cov byte 1: line 8

    assertArrayEquals(expected.toByteArray(), result);
  }

  @Test
  void coveredLinesBitVectorPaddedWithZeros() throws IOException {
    // executable has line 15 (byte 1), covered has only line 1 (byte 0)
    // Both bit vectors must be 2 bytes (covered padded to match)
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    LinesCoverage lc = new LinesCoverage();
    lc.executableLines.set(1);
    lc.executableLines.set(15);
    lc.coveredLines.set(1);
    coverage.put(new CoverageKey("P.java", "P"), lc);

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01);
    expected.write(0x01);
    expected.write(0x01);
    writeExpectedString("P.java", expected);
    writeExpectedString("P", expected);
    expected.write(0x02); // bitvec_byte_count = 2 (max line 15: (15>>3)+1=2)
    expected.write(0x02); // exec byte 0: line 1
    expected.write((byte) 0x80); // exec byte 1: line 15 → bit 7
    expected.write(0x02); // cov byte 0: line 1
    expected.write(0x00); // cov byte 1: padding

    assertArrayEquals(expected.toByteArray(), result);
  }

  @Test
  void executableLinesBitVectorPaddedWhenCoveredHasHigherLine() throws IOException {
    // covered has line 10 (higher than executable's max of 3)
    // This violates the spec constraint but encoder should still handle it
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    LinesCoverage lc = new LinesCoverage();
    lc.executableLines.set(3);
    lc.coveredLines.set(10);
    coverage.put(new CoverageKey("Q.java", "Q"), lc);

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01);
    expected.write(0x01);
    expected.write(0x01);
    writeExpectedString("Q.java", expected);
    writeExpectedString("Q", expected);
    expected.write(0x02); // bitvec_byte_count = (10>>3)+1 = 2
    expected.write(0x08); // exec byte 0: line 3 → bit 3
    expected.write(0x00); // exec byte 1: padding
    expected.write(0x00); // cov byte 0: no lines in lower byte
    expected.write(0x04); // cov byte 1: line 10 → byte 1, bit 2

    assertArrayEquals(expected.toByteArray(), result);
  }

  // --- Multiple records ---

  @Test
  void multipleRecords() throws IOException {
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();

    LinesCoverage lc1 = new LinesCoverage();
    lc1.executableLines.set(1);
    lc1.coveredLines.set(1);
    coverage.put(new CoverageKey("A.java", "A"), lc1);

    LinesCoverage lc2 = new LinesCoverage();
    lc2.executableLines.set(2);
    coverage.put(new CoverageKey("B.java", "B"), lc2);

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01); // version
    expected.write(0x01); // num_extra_fields
    expected.write(0x02); // num_records = 2

    // Record 1
    writeExpectedString("A.java", expected);
    writeExpectedString("A", expected);
    expected.write(0x01); // bitvec_byte_count = 1
    expected.write(0x02); // exec: line 1
    expected.write(0x02); // cov: line 1

    // Record 2
    writeExpectedString("B.java", expected);
    writeExpectedString("B", expected);
    expected.write(0x01); // bitvec_byte_count = 1
    expected.write(0x04); // exec: line 2
    expected.write(0x00); // cov: none

    assertArrayEquals(expected.toByteArray(), result);
  }

  // --- String encoding ---

  @Test
  void emptyStringEncoding() throws IOException {
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    coverage.put(new CoverageKey("", ""), new LinesCoverage());

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01); // version
    expected.write(0x01); // num_extra_fields
    expected.write(0x01); // num_records
    expected.write(0x00); // file_name: length 0
    expected.write(0x00); // extra_fields[0]: length 0
    expected.write(0x00); // bitvec_byte_count = 0

    assertArrayEquals(expected.toByteArray(), result);
  }

  @Test
  void utf8MultiByteStringEncoding() throws IOException {
    // UTF-8 multi-byte: "Ñ" is 2 bytes (0xC3 0x91)
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    coverage.put(new CoverageKey("Ñ.java", "Ñ"), new LinesCoverage());

    byte[] result = encode(coverage);
    byte[] fileName = "Ñ.java".getBytes(StandardCharsets.UTF_8);
    byte[] className = "Ñ".getBytes(StandardCharsets.UTF_8);

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01);
    expected.write(0x01);
    expected.write(0x01);
    // file_name length is byte count, not char count
    writeExpectedUvarint(fileName.length, expected);
    expected.write(fileName);
    writeExpectedUvarint(className.length, expected);
    expected.write(className);
    expected.write(0x00); // bitvec_byte_count

    assertArrayEquals(expected.toByteArray(), result);
    // Verify byte length != char length
    assertEquals(7, fileName.length); // "Ñ" is 2 bytes + ".java" is 5 bytes
  }

  // --- Spec example ---

  @Test
  void specExampleTwoRecords() throws IOException {
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();

    // Record 1: com/example/Foo.java, com.example.Foo, exec={1,2,3,5,8}, cov={1,3,5}
    LinesCoverage lc1 = new LinesCoverage();
    for (int line : new int[] {1, 2, 3, 5, 8}) {
      lc1.executableLines.set(line);
    }
    for (int line : new int[] {1, 3, 5}) {
      lc1.coveredLines.set(line);
    }
    coverage.put(new CoverageKey("com/example/Foo.java", "com.example.Foo"), lc1);

    // Record 2: com/example/Bar.java, com.example.Bar, exec={2,4,6}, cov={4}
    LinesCoverage lc2 = new LinesCoverage();
    for (int line : new int[] {2, 4, 6}) {
      lc2.executableLines.set(line);
    }
    lc2.coveredLines.set(4);
    coverage.put(new CoverageKey("com/example/Bar.java", "com.example.Bar"), lc2);

    byte[] result = encode(coverage);

    // Expected byte sequence from the spec
    byte[] expected = {
      0x01,
      0x01,
      0x02, // header: version=1, extra_fields=1, records=2
      0x14, // file_name length = 20
      0x63,
      0x6F,
      0x6D,
      0x2F,
      0x65,
      0x78,
      0x61,
      0x6D, // "com/exam"
      0x70,
      0x6C,
      0x65,
      0x2F,
      0x46,
      0x6F,
      0x6F,
      0x2E, // "ple/Foo."
      0x6A,
      0x61,
      0x76,
      0x61, // "java"
      0x0F, // extra_fields[0] length = 15
      0x63,
      0x6F,
      0x6D,
      0x2E,
      0x65,
      0x78,
      0x61,
      0x6D, // "com.exam"
      0x70,
      0x6C,
      0x65,
      0x2E,
      0x46,
      0x6F,
      0x6F, // "ple.Foo"
      0x02, // bitvec_byte_count = 2
      0x2E,
      0x01, // executable_lines
      0x2A,
      0x00, // covered_lines
      0x14, // file_name length = 20
      0x63,
      0x6F,
      0x6D,
      0x2F,
      0x65,
      0x78,
      0x61,
      0x6D, // "com/exam"
      0x70,
      0x6C,
      0x65,
      0x2F,
      0x42,
      0x61,
      0x72,
      0x2E, // "ple/Bar."
      0x6A,
      0x61,
      0x76,
      0x61, // "java"
      0x0F, // extra_fields[0] length = 15
      0x63,
      0x6F,
      0x6D,
      0x2E,
      0x65,
      0x78,
      0x61,
      0x6D, // "com.exam"
      0x70,
      0x6C,
      0x65,
      0x2E,
      0x42,
      0x61,
      0x72, // "ple.Bar"
      0x01, // bitvec_byte_count = 1
      0x54, // executable_lines
      0x10 // covered_lines
    };

    assertArrayEquals(expected, result);
  }

  // --- Edge cases ---

  @Test
  void highLineNumber() throws IOException {
    // Line 1000: byte index = 1000>>3 = 125, bit = 1000&7 = 0
    // byte_count = (1000>>3)+1 = 126
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    LinesCoverage lc = new LinesCoverage();
    lc.executableLines.set(1000);
    lc.coveredLines.set(1000);
    coverage.put(new CoverageKey("H.java", "H"), lc);

    byte[] result = encode(coverage);

    // Parse manually: skip header and strings, check bitvec
    int offset = 0;
    assertEquals(0x01, result[offset++] & 0xFF); // version
    assertEquals(0x01, result[offset++] & 0xFF); // num_extra_fields
    assertEquals(0x01, result[offset++] & 0xFF); // num_records

    // Skip file_name "H.java" (length 6)
    assertEquals(0x06, result[offset++] & 0xFF);
    offset += 6;

    // Skip extra_field "H" (length 1)
    assertEquals(0x01, result[offset++] & 0xFF);
    offset += 1;

    // bitvec_byte_count = 126 → varint encoding: (126 & 0x7F) = 0x7E, fits in 1 byte
    assertEquals(126, result[offset++] & 0xFF);

    // executable_lines: 126 bytes, only byte 125 has bit 0 set
    for (int i = 0; i < 126; i++) {
      int expectedByte = (i == 125) ? 0x01 : 0x00;
      assertEquals(expectedByte, result[offset + i] & 0xFF, "exec byte " + i);
    }
    offset += 126;

    // covered_lines: same pattern
    for (int i = 0; i < 126; i++) {
      int expectedByte = (i == 125) ? 0x01 : 0x00;
      assertEquals(expectedByte, result[offset + i] & 0xFF, "cov byte " + i);
    }
  }

  @Test
  void line7SetsHighBitOfByte0() throws IOException {
    // Line 7: byte 0, bit 7 → 0x80
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    LinesCoverage lc = new LinesCoverage();
    lc.executableLines.set(7);
    coverage.put(new CoverageKey("S.java", "S"), lc);

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01);
    expected.write(0x01);
    expected.write(0x01);
    writeExpectedString("S.java", expected);
    writeExpectedString("S", expected);
    expected.write(0x01); // bitvec_byte_count = 1
    expected.write((byte) 0x80); // exec: line 7 → bit 7
    expected.write(0x00); // cov: none

    assertArrayEquals(expected.toByteArray(), result);
  }

  @Test
  void allLinesInOneByte() throws IOException {
    // Lines {1,2,3,4,5,6,7}: all in byte 0
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    LinesCoverage lc = new LinesCoverage();
    for (int i = 1; i <= 7; i++) {
      lc.executableLines.set(i);
    }
    lc.coveredLines.set(4);
    coverage.put(new CoverageKey("Z.java", "Z"), lc);

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01);
    expected.write(0x01);
    expected.write(0x01);
    writeExpectedString("Z.java", expected);
    writeExpectedString("Z", expected);
    expected.write(0x01); // bitvec_byte_count = 1
    // exec: bits 1-7 set → 0xFE
    expected.write((byte) 0xFE);
    // cov: bit 4 → 0x10
    expected.write(0x10);

    assertArrayEquals(expected.toByteArray(), result);
  }

  @Test
  void onlyExecutableLinesNoCoverage() throws IOException {
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    LinesCoverage lc = new LinesCoverage();
    lc.executableLines.set(1);
    lc.executableLines.set(5);
    coverage.put(new CoverageKey("N.java", "N"), lc);

    byte[] result = encode(coverage);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(0x01);
    expected.write(0x01);
    expected.write(0x01);
    writeExpectedString("N.java", expected);
    writeExpectedString("N", expected);
    expected.write(0x01); // bitvec_byte_count = 1
    expected.write(0x22); // exec: line 1 (0x02) | line 5 (0x20) = 0x22
    expected.write(0x00); // cov: empty

    assertArrayEquals(expected.toByteArray(), result);
  }

  @Test
  void stringLengthRequiresMultiByteUvarint() throws IOException {
    // Create a string longer than 127 bytes so its length needs 2 uvarint bytes
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 130; i++) {
      sb.append('a');
    }
    String longName = sb.toString();

    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();
    coverage.put(new CoverageKey(longName, "C"), new LinesCoverage());

    byte[] result = encode(coverage);

    // Check the uvarint encoding of 130: (130 & 0x7F) | 0x80 = 0x82, 130 >> 7 = 1 → 0x01
    int offset = 3; // skip version + num_extra_fields + num_records
    assertEquals((byte) 0x82, result[offset]); // low 7 bits of 130 with continuation
    assertEquals((byte) 0x01, result[offset + 1]); // remaining bits
    offset += 2;
    // Verify string data
    for (int i = 0; i < 130; i++) {
      assertEquals((byte) 'a', result[offset + i]);
    }
  }

  @Test
  void outputSizeMatchesExpectedForSpecExample() throws IOException {
    // The spec says total message size is 85 bytes
    Map<CoverageKey, LinesCoverage> coverage = new LinkedHashMap<>();

    LinesCoverage lc1 = new LinesCoverage();
    for (int line : new int[] {1, 2, 3, 5, 8}) {
      lc1.executableLines.set(line);
    }
    for (int line : new int[] {1, 3, 5}) {
      lc1.coveredLines.set(line);
    }
    coverage.put(new CoverageKey("com/example/Foo.java", "com.example.Foo"), lc1);

    LinesCoverage lc2 = new LinesCoverage();
    for (int line : new int[] {2, 4, 6}) {
      lc2.executableLines.set(line);
    }
    lc2.coveredLines.set(4);
    coverage.put(new CoverageKey("com/example/Bar.java", "com.example.Bar"), lc2);

    byte[] result = encode(coverage);
    assertEquals(85, result.length);
  }

  // --- Helpers ---

  private static byte[] encode(Map<CoverageKey, LinesCoverage> coverage) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CoverageBinaryEncoder.encode(coverage, out);
    return out.toByteArray();
  }

  private static void assertUvarint(int value, byte[] expected) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CoverageBinaryEncoder.writeUvarint(value, out);
    assertArrayEquals(expected, out.toByteArray(), "uvarint(" + value + ")");
  }

  private static void writeExpectedUvarint(int value, ByteArrayOutputStream out) {
    while (value >= 0x80) {
      out.write((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    out.write(value);
  }

  private static void writeExpectedString(String s, ByteArrayOutputStream out) throws IOException {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    writeExpectedUvarint(bytes.length, out);
    out.write(bytes);
  }
}
