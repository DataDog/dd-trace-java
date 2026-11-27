// Copyright 2026 Datadog, Inc.
package com.datadoghq.profiler;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads {@link TaskBlockBridge} next to a {@code JavaProfiler} that lacks the TaskBlock API or its
 * natives. Each test uses its own class loader so the bridge's per-JVM state starts fresh.
 */
class TaskBlockBridgeCompatibilityTest {
  private static final String JAVA_PROFILER = "com.datadoghq.profiler.JavaProfiler";

  // Every TaskBlock hook resolves, but parkEnter reaches a native that no library provides.
  private static final String UNLINKED_NATIVE_PROFILER =
      "package com.datadoghq.profiler;\n"
          + "public final class JavaProfiler {\n"
          + "  boolean parkEnter() { return parkEnter0(); }\n"
          + "  void parkExit(long blocker, long unblockingSpanId) {}\n"
          + "  public long beginTaskBlock() { return 1L; }\n"
          + "  public boolean endTaskBlock(long t, long b, long s) { return true; }\n"
          + "  private static native boolean parkEnter0();\n"
          + "}\n";

  @TempDir Path tempDir;

  @Test
  void releasedArtifactWithoutTaskBlockApiIsReportedUnsupportedOnce() throws Exception {
    String legacyJar = System.getProperty("test.ddprof.legacy.jar");
    assertNotNull(legacyJar, "test.ddprof.legacy.jar must name a released ddprof artifact");
    try (URLClassLoader loader = isolatedLoader(Paths.get(legacyJar))) {
      // The released artifact predates the TaskBlock API: its park hook returns void.
      assertEquals(
          void.class,
          loader.loadClass(JAVA_PROFILER).getDeclaredMethod("parkEnter").getReturnType());
      List<String> reports = new ArrayList<>();
      Object first = newBridge(loader, null, reports);
      Object second = newBridge(loader, null, reports);

      assertEquals(false, invoke(first, "hasParkSupport"));
      assertEquals(false, invoke(first, "hasSynchronousTaskBlockSupport"));
      invoke(first, "reportIfUnsupported");
      invoke(second, "reportIfUnsupported");
      assertEquals(singletonList(TaskBlockBridge.MISSING_HOOKS), reports);
      assertEquals(false, invoke(first, "parkEnter"));
      assertEquals(0L, invoke(first, "beginTaskBlock"));
    }
  }

  @Test
  void hookWithoutNativeImplementationDisablesEveryHookAndReportsOnce() throws Exception {
    Path classes = compileUnlinkedNativeProfiler();
    try (URLClassLoader loader = isolatedLoader(classes)) {
      Object profiler = loader.loadClass(JAVA_PROFILER).getDeclaredConstructor().newInstance();
      List<String> reports = new ArrayList<>();
      Object bridge = newBridge(loader, profiler, reports);

      invoke(bridge, "reportIfUnsupported");
      assertTrue(reports.isEmpty(), "All hook methods resolve, so nothing is reported yet");
      assertEquals(true, invoke(bridge, "hasSynchronousTaskBlockSupport"));

      assertEquals(false, invoke(bridge, "parkEnter"));
      assertEquals(singletonList(TaskBlockBridge.MISSING_NATIVE), reports);
      assertEquals(false, invoke(bridge, "hasParkSupport"));
      assertEquals(false, invoke(bridge, "hasSynchronousTaskBlockSupport"));
      // The stub would return 1; 0 proves the disabled bridge no longer calls into the profiler.
      assertEquals(0L, invoke(bridge, "beginTaskBlock"));
      assertEquals(false, invoke(bridge, "parkEnter"));
      assertEquals(singletonList(TaskBlockBridge.MISSING_NATIVE), reports);
    }
  }

  @Test
  void failingReporterDoesNotReachTheCaller() throws Exception {
    Path classes = compileUnlinkedNativeProfiler();
    try (URLClassLoader loader = isolatedLoader(classes)) {
      Object profiler = loader.loadClass(JAVA_PROFILER).getDeclaredConstructor().newInstance();
      Consumer<String> reporter =
          reason -> {
            throw new IllegalStateException("reporter failed");
          };
      Object bridge = newBridge(loader, profiler, reporter);

      assertEquals(false, invoke(bridge, "parkEnter"));
      assertEquals(false, invoke(bridge, "hasParkSupport"));
    }
  }

  private Path compileUnlinkedNativeProfiler() throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assumeTrue(compiler != null, "Compiling the JavaProfiler stub requires a JDK");
    Path source = tempDir.resolve("src/com/datadoghq/profiler/JavaProfiler.java");
    Files.createDirectories(source.getParent());
    Files.write(source, UNLINKED_NATIVE_PROFILER.getBytes(UTF_8));
    Path classes = Files.createDirectories(tempDir.resolve("classes"));
    assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()));
    return classes;
  }

  /** Defines the bridge in the same loader and package as the given {@code JavaProfiler}. */
  private static URLClassLoader isolatedLoader(Path profilerClasses) throws Exception {
    URL bridgeClasses = TaskBlockBridge.class.getProtectionDomain().getCodeSource().getLocation();
    return new URLClassLoader(new URL[] {profilerClasses.toUri().toURL(), bridgeClasses}, null);
  }

  private static Object newBridge(ClassLoader loader, Object profiler, List<String> reports)
      throws Exception {
    return newBridge(loader, profiler, (Consumer<String>) reports::add);
  }

  private static Object newBridge(ClassLoader loader, Object profiler, Consumer<String> reporter)
      throws Exception {
    return loader
        .loadClass(TaskBlockBridge.class.getName())
        .getConstructor(loader.loadClass(JAVA_PROFILER), Consumer.class)
        .newInstance(profiler, reporter);
  }

  private static Object invoke(Object bridge, String method) throws Exception {
    Method target = bridge.getClass().getMethod(method);
    return target.invoke(bridge);
  }
}
