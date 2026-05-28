package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.agentShadowJar;
import static datadog.smoketest.SmokeTestUtils.buildDirectory;
import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.SmokeTestUtils.javaPath;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;
import static org.openjdk.jmc.common.unit.UnitLookup.PLAIN_TEXT;

import datadog.trace.api.config.ProfilingConfig;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

/**
 * End-to-end smoke test for the {@code nio-channel} instrumentation: forks a JVM with the agent
 * attached, runs a blocking {@code ServerSocketChannel.accept()} call under an active span, and
 * asserts that {@code datadog.TaskBlock} JFR events are emitted carrying that span's context.
 */
@DisabledOnJ9
final class NioChannelTaskBlockProfilingTest {

  private static final byte[] JFR_MAGIC = new byte[] {'F', 'L', 'R', 0};
  private static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);
  private static final IAttribute<String> OPERATION =
      attr("_dd.trace.operation", "_dd.trace.operation", "_dd.trace.operation", PLAIN_TEXT);
  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(),
          "reports",
          "testProcess." + NioChannelTaskBlockProfilingTest.class.getName());

  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    Files.createDirectories(LOG_FILE_BASE);
    logFilePath =
        LOG_FILE_BASE.resolve(
            testInfo.getTestMethod().map(method -> method.getName()).orElse("nioChannel")
                + ".log");
    dumpDir = Files.createTempDirectory("dd-profiler-niochannel-");
  }

  @AfterEach
  void tearDown() throws IOException {
    if (dumpDir != null && Files.exists(dumpDir)) {
      Files.walk(dumpDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  @Test
  @DisplayName("ServerSocketChannel.accept() under an active span emits TaskBlock events")
  void blockingAcceptEmitsTaskBlock() throws Exception {
    Process targetProcess = createProcessBuilder().start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();
    assertTrue(
        stats.activeSpanTaskBlocks > 0,
        "Expected datadog.TaskBlock events from traced blocking ServerSocketChannel.accept()");
    assertTrue(
        stats.hasExpectedOperation,
        "Expected TaskBlock events tagged with the niochannel.accept span operation name");
    assertFalse(
        logHasInstrumentationError(),
        "nio-channel instrumentation produced classloading or rewrite errors in the forked log");
  }

  private ProcessBuilder createProcessBuilder() {
    String templateOverride =
        NioChannelTaskBlockProfilingTest.class
            .getClassLoader()
            .getResource("overrides.jfp")
            .getFile();
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                javaPath(),
                "-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "1024M"),
                "-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M"),
                "-javaagent:" + agentShadowJar(),
                "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
                "-Ddd.service.name=smoke-test-niochannel-taskblock",
                "-Ddd.env=smoketest",
                "-Ddd.version=99",
                "-Ddd.profiling.enabled=true",
                "-Ddd.profiling.ddprof.enabled=true",
                "-Ddd." + ProfilingConfig.PROFILING_AUXILIARY_TYPE + "=async",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED + "=true",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL + "=10ms",
                "-Ddd.profiling.agentless=false",
                "-Ddd.profiling.start-delay=0",
                "-Ddd." + ProfilingConfig.PROFILING_START_FORCE_FIRST + "=true",
                "-Ddd.profiling.upload.period=1",
                "-Ddd.profiling.hotspots.enabled=true",
                "-Ddd." + ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED + "=true",
                "-Ddd.profiling.debug.dump_path=" + dumpDir,
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK + "=true",
                "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+UnlockCommercialFeatures",
                "-XX:+FlightRecorder",
                "-Ddd." + ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE + "=" + templateOverride,
                "-cp",
                System.getProperty("java.class.path"),
                NioChannelTaskBlockForkedApp.class.getName()));
    if (System.getenv("TEST_LIBASYNC") != null) {
      command.add(
          command.size() - 3,
          "-Ddd."
              + ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH
              + "="
              + System.getenv("TEST_LIBASYNC"));
    }
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(new File(buildDirectory()));
    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFilePath.toFile()));
    return processBuilder;
  }

  private JfrStats loadStats() throws Exception {
    JfrStats stats = new JfrStats();
    List<Path> jfrFiles;
    try (java.util.stream.Stream<Path> files = Files.walk(dumpDir)) {
      jfrFiles =
          files
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".jfr"))
              .collect(Collectors.toList());
    }
    int loaded = 0;
    for (Path jfrFile : jfrFiles) {
      IItemCollection events = tryLoadEvents(jfrFile);
      if (events != null) {
        stats.add(events);
        loaded++;
      }
    }
    if (loaded == 0 && !jfrFiles.isEmpty()) {
      throw new RuntimeException(
          "No JFR file in " + dumpDir + " could be parsed (tried " + jfrFiles.size() + " files)");
    }
    return stats;
  }

  private IItemCollection tryLoadEvents(Path path) {
    try {
      return JfrLoaderToolkit.loadEvents(path.toFile());
    } catch (Exception ignored) {
      // fall through
    }
    try {
      Path extracted = extractLastJfrStream(path);
      if (!extracted.equals(path)) {
        return JfrLoaderToolkit.loadEvents(extracted.toFile());
      }
    } catch (Exception ignored) {
      // fall through
    }
    return null;
  }

  private Path extractLastJfrStream(Path path) throws IOException {
    byte[] data = Files.readAllBytes(path);
    int lastMagic = lastIndexOf(data, JFR_MAGIC);
    if (lastMagic <= 0) {
      return path;
    }
    Path extracted = dumpDir.resolve(path.getFileName() + ".ddprof.jfr");
    Files.write(extracted, Arrays.copyOfRange(data, lastMagic, data.length));
    return extracted;
  }

  private static int lastIndexOf(byte[] data, byte[] needle) {
    for (int i = data.length - needle.length; i >= 0; i--) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (data[i + j] != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return i;
      }
    }
    return -1;
  }

  private boolean logHasInstrumentationError() throws IOException {
    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    return log.contains("NoClassDefFoundError")
        || log.contains("Failed to handle exception in instrumentation for java.nio.channels");
  }

  private static final class JfrStats {
    long activeSpanTaskBlocks;
    boolean hasExpectedOperation;

    void add(IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> span = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<String, IItem> op = OPERATION.getAccessor(items.getType());
        if (span == null) {
          continue;
        }
        for (IItem item : items) {
          long spanId = span.getMember(item).longValue();
          String operation = op != null ? op.getMember(item) : null;
          if (spanId == 0L) {
            continue;
          }
          if ("niochannel.accept".equals(operation)) {
            activeSpanTaskBlocks++;
            hasExpectedOperation = true;
          }
        }
      }
    }
  }

  /**
   * Forked application that opens a {@code ServerSocketChannel} and calls {@code accept()} under
   * an active span. A daemon client thread connects after a short delay so that {@code accept()}
   * blocks for at least 50 ms, ensuring the 1 ms TaskBlock threshold is exceeded.
   */
  public static final class NioChannelTaskBlockForkedApp {
    private static final long ACCEPT_DELAY_MILLIS = 100L;

    public static void main(String[] args) throws Exception {
      Tracer tracer = GlobalTracer.get();

      try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
        serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        // Daemon client thread: connects after a delay so accept() blocks long enough to
        // exceed the 1 ms TaskBlock minimum duration gate.
        Thread clientThread =
            new Thread(
                () -> {
                  try {
                    Thread.sleep(ACCEPT_DELAY_MILLIS);
                    SocketChannel client =
                        SocketChannel.open(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                    client.close();
                  } catch (Exception ignored) {
                  }
                });
        clientThread.setDaemon(true);
        clientThread.start();

        // Accept under an active span: produces one TaskBlock interval of ~ACCEPT_DELAY_MILLIS ms.
        Span span = tracer.buildSpan("niochannel.accept").start();
        try (Scope scope = tracer.activateSpan(span)) {
          SocketChannel accepted = serverChannel.accept();
          if (accepted != null) {
            accepted.close();
          }
        } finally {
          span.finish();
        }

        clientThread.join(5_000L);
      }

      // Wait for the profiler to flush its JFR buffers.
      Thread.sleep(1500);
    }
  }
}
