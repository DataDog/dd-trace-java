package datadog.trace.codecoverage;

import datadog.trace.coverage.CoverageKey;
import datadog.trace.coverage.LinesCoverage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Map;

/**
 * Encodes coverage data into the Coverage Binary Protocol v1
 */
public final class CoverageBinaryEncoder {

  private static final int VERSION = 1;
  private static final int NUM_EXTRA_FIELDS = 1; // className

  public static void encode(Map<CoverageKey, LinesCoverage> coverage, OutputStream out)
      throws IOException {
    out.write(VERSION);
    writeUvarint(NUM_EXTRA_FIELDS, out);
    writeUvarint(coverage.size(), out);
    for (Map.Entry<CoverageKey, LinesCoverage> entry : coverage.entrySet()) {
      writeRecord(entry.getKey(), entry.getValue(), out);
    }
  }

  private static void writeRecord(CoverageKey key, LinesCoverage lines, OutputStream out)
      throws IOException {
    writeString(key.sourceFile, out);
    writeString(key.className, out);

    int maxLine =
        Math.max(lines.executableLines.length(), lines.coveredLines.length()) - 1;
    if (maxLine < 0) {
      writeUvarint(0, out);
      return;
    }
    int byteCount = (maxLine >> 3) + 1;
    writeUvarint(byteCount, out);
    writeBitVector(lines.executableLines, byteCount, out);
    writeBitVector(lines.coveredLines, byteCount, out);
  }

  private static void writeBitVector(BitSet bits, int byteCount, OutputStream out)
      throws IOException {
    byte[] data = bits.toByteArray();
    out.write(data, 0, Math.min(data.length, byteCount));
    for (int i = data.length; i < byteCount; i++) {
      out.write(0);
    }
  }

  private static void writeString(String s, OutputStream out) throws IOException {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    writeUvarint(bytes.length, out);
    out.write(bytes);
  }

  static void writeUvarint(int value, OutputStream out) throws IOException {
    while (value >= 0x80) {
      out.write((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    out.write(value);
  }
}
