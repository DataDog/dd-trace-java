package datadog.trace.api.flare;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TracerFlare {

  public interface Reporter {
    default void prepareForFlare() {}

    void addReportToFlare(ZipOutputStream zip) throws IOException;

    default void cleanupAfterFlare() {}
  }

  private static final Map<Class<?>, Reporter> reporters = new ConcurrentHashMap<>();

  public static void prepareForFlare() {
    for (Reporter reporter : reporters.values()) {
      try {
        reporter.prepareForFlare();
      } catch (Throwable ignore) {
      }
    }
  }

  public static void addReportsToFlare(ZipOutputStream zip) throws IOException {
    List<Throwable> errors = null;

    for (Reporter reporter : reporters.values()) {
      try {
        reporter.addReportToFlare(zip);
      } catch (Throwable e) {
        if (null == errors) {
          errors = new ArrayList<>();
        }
        errors.add(e);
      }
    }

    if (null != errors) {
      zip.putNextEntry(new ZipEntry("flare_errors.txt"));
      for (Throwable e : errors) {
        zip.write(e.toString().getBytes(UTF_8));
        zip.write('\n');
      }
    }
  }

  public static void cleanupAfterFlare() {
    for (Reporter reporter : reporters.values()) {
      try {
        reporter.cleanupAfterFlare();
      } catch (Throwable ignore) {
      }
    }
  }

  public static void addReporter(Reporter reporter) {
    reporters.put(reporter.getClass(), reporter);
  }

  public static Collection<Reporter> getReporters() {
    return Collections.unmodifiableCollection(reporters.values());
  }

  public static Reporter getReporter(String className) {
    for (Reporter reporter : getReporters()) {
      if (reporter.getClass().getName().equals(className)) {
        return reporter;
      }
    }
    return null;
  }

  public static void addText(ZipOutputStream zip, String section, String text) throws IOException {
    zip.putNextEntry(new ZipEntry(section));
    if (null != text) {
      zip.write(text.getBytes(UTF_8));
    }
  }

  public static void addBinary(ZipOutputStream zip, String section, byte[] bytes)
      throws IOException {
    zip.putNextEntry(new ZipEntry(section));
    if (null != bytes) {
      zip.write(bytes);
    }
  }
}
