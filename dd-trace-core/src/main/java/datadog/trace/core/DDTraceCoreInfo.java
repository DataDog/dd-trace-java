package datadog.trace.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class DDTraceCoreInfo {

  public static final String JAVA_VERSION = System.getProperty("java.version", "unknown");
  public static final String JAVA_VM_NAME = System.getProperty("java.vm.name", "unknown");
  public static final String JAVA_VM_VENDOR = System.getProperty("java.vm.vendor", "unknown");

  public static final String VERSION;

  static {
    String v;
    try (final BufferedReader br =
        new BufferedReader(
            new InputStreamReader(
                DDTraceCoreInfo.class.getResourceAsStream("/dd-trace-core.version"), UTF_8))) {
      final StringBuilder sb = new StringBuilder();

      for (int c = br.read(); c != -1; c = br.read()) {
        sb.append((char) c);
      }

      v = sb.toString().trim();
    } catch (final Exception e) {
      v = "unknown";
    }
    VERSION = v;
  }

  @SuppressForbidden
  public static void main(final String... args) {
    System.out.println(VERSION);
  }
}
