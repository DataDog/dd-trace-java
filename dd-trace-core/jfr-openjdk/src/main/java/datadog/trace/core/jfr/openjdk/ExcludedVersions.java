package datadog.trace.core.jfr.openjdk;

import datadog.trace.core.DDTraceCoreInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ExcludedVersions {

  private static final String VERSION = DDTraceCoreInfo.JAVA_VERSION.split("\\.")[0];
  private static final Set<String> EXCLUDED_VERSIONS;

  static {
    final Set<String> excludedVersions = new HashSet<>();
    // Java 9 and 10 throw seg fault on MacOS if events are used in premain.
    // Since these versions are not LTS we just disable profiling events for them.
    excludedVersions.add("9");
    excludedVersions.add("10");
    EXCLUDED_VERSIONS = Collections.unmodifiableSet(excludedVersions);
  }

  public static void checkVersionExclusion() throws ClassNotFoundException {
    if (EXCLUDED_VERSIONS.contains(VERSION)) {
      throw new ClassNotFoundException("Excluded java version: " + DDTraceCoreInfo.JAVA_VERSION);
    }
  }
}
