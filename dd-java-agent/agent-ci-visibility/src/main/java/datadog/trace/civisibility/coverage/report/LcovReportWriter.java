package datadog.trace.civisibility.coverage.report;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.BitSet;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;

/** Serializes coverage data into LCOV format. */
public final class LcovReportWriter {

  public static void write(Map<String, BitSet> coverage, Writer out) throws IOException {
    Objects.requireNonNull(coverage, "coverage");
    Objects.requireNonNull(out, "out");

    Map<String, BitSet> sorted = (coverage instanceof TreeMap) ? coverage : toTreeMap(coverage);

    for (Map.Entry<String, BitSet> e : sorted.entrySet()) {
      String path = e.getKey();
      BitSet bits = e.getValue();
      if (path == null || path.isEmpty()) {
        continue;
      }
      if (bits == null) {
        bits = new BitSet();
      }

      out.write("SF:" + path + "\n");

      int hits = 0;
      for (int i = bits.nextSetBit(1); i >= 0; i = bits.nextSetBit(i + 1)) {
        out.write("DA:" + i + ",1\n");
        hits++;
      }

      int lf = Math.max(0, bits.length() - 1); // exclude bit 0
      out.write("LH:" + hits + "\n");
      out.write("LF:" + lf + "\n");
      out.write("end_of_record\n");
    }
  }

  @NotNull
  private static TreeMap<String, BitSet> toTreeMap(Map<String, BitSet> coverage) {
    TreeMap<String, BitSet> treeMap = new TreeMap<>();
    for (Map.Entry<String, BitSet> e : coverage.entrySet()) {
      if (e.getKey() == null) {
        continue;
      }
      treeMap.put(e.getKey(), e.getValue());
    }
    return treeMap;
  }

  public static String toString(Map<String, BitSet> coverage) {
    try {
      StringWriter sw = new StringWriter();
      write(coverage, sw);
      return sw.toString();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }
}
