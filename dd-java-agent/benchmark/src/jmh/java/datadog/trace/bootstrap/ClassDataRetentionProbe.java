package datadog.trace.bootstrap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Reports class bytes retained by packed chunks after defining the discovered common set. */
public final class ClassDataRetentionProbe {
  private ClassDataRetentionProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected: <benchmark-dir> <layout>");
    }
    File benchmarkDir = new File(args[0]);
    List<String> commonClasses =
        Files.readAllLines(
            new File(benchmarkDir, "common-classes.txt").toPath(), StandardCharsets.UTF_8);
    DatadogClassLoader loader =
        new DatadogClassLoader(
            new File(benchmarkDir, args[1] + ".jar").toURI().toURL(), platformClassLoader());
    try {
      for (String className : commonClasses) {
        loader.loadClass(className);
      }
      System.out.printf(
          "%s retained_packed_class_bytes=%d%n", args[1], loader.retainedPackedClassBytes());
    } finally {
      loader.close();
    }
  }

  private static ClassLoader platformClassLoader() {
    ClassLoader system = ClassLoader.getSystemClassLoader();
    ClassLoader parent = system.getParent();
    return parent == null ? system : parent;
  }
}
