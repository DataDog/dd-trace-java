package datadog.trace.civisibility.coverage.report;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nonnull;

public final class LcovReportWriter {
  private LcovReportWriter() {}

  public static void write(Map<String, LinesCoverage> coverage, Writer out) throws IOException {
    Objects.requireNonNull(coverage, "coverage");
    Objects.requireNonNull(out, "out");

    Map<String, LinesCoverage> sorted =
        (coverage instanceof TreeMap) ? coverage : toTreeMap(coverage);

    for (Map.Entry<String, LinesCoverage> e : sorted.entrySet()) {
      String path = e.getKey();
      LinesCoverage lc = e.getValue();
      if (path == null || path.isEmpty()) {
        continue;
      }
      if (lc == null) {
        lc = new LinesCoverage();
      }

      out.write("SF:" + path + "\n");

      int lf = 0; // lines found (instrumented)
      int lh = 0; // lines hit (executed at least once)

      for (int line = lc.executableLines.nextSetBit(1);
          line >= 0;
          line = lc.executableLines.nextSetBit(line + 1)) { // skip bit 0
        lf++;
        int count = lc.coveredLines.get(line) ? 1 : 0;
        lh += count;
        out.write("DA:" + line + "," + count + "\n");
      }

      out.write("LH:" + lh + "\n");
      out.write("LF:" + lf + "\n");
      out.write("end_of_record\n");
    }
  }

  @Nonnull
  private static TreeMap<String, LinesCoverage> toTreeMap(Map<String, LinesCoverage> coverage) {
    TreeMap<String, LinesCoverage> treeMap = new TreeMap<>();
    for (Map.Entry<String, LinesCoverage> e : coverage.entrySet()) {
      if (e.getKey() == null) {
        continue;
      }
      treeMap.put(e.getKey(), e.getValue());
    }
    return treeMap;
  }

  public static String toString(Map<String, LinesCoverage> coverage) {
    try {
      StringWriter sw = new StringWriter();
      write(coverage, sw);
      return sw.toString();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }
}
