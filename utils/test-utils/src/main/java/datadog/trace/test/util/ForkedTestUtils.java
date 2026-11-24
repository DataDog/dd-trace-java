package datadog.trace.test.util;

import datadog.environment.OperatingSystem;
import java.util.ArrayList;
import java.util.List;

public class ForkedTestUtils {
  public static String getMaxMemoryArgumentForFork() {
    return "-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "1024M");
  }

  public static String getMinMemoryArgumentForFork() {
    return "-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M");
  }

  /** JVM options for child JVMs that tests launch directly with java.home/bin/java. */
  public static List<String> childJvmArguments() {
    List<String> arguments = new ArrayList<>();
    arguments.add("-XX:ErrorFile=/tmp/hs_err_pid%p.log");
    // Disable CDS to avoid SIGSEGVs on Linux arm64.
    if (OperatingSystem.isLinux() && OperatingSystem.architecture().isArm64()) {
      arguments.add("-Xshare:off");
    }
    return arguments;
  }
}
