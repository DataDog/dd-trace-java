package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.OtelSpiCollector;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ExtensionFinderTest {

  private static final String AUTOCONFIGURE_PROPAGATOR =
      "io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider";
  private static final String AUTOCONFIGURE_RESOURCE =
      "io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider";
  private static final String AUTOCONFIGURE_SAMPLER =
      "io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider";
  private static final String AUTOCONFIGURE_EXPORTER =
      "io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider";
  private static final String JAVAAGENT_INSTRUMENTATION_MODULE =
      "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule";
  private static final String JAVAAGENT_AGENT_LISTENER =
      "io.opentelemetry.javaagent.extension.AgentListener";
  private static final String SHADED_AUTOCONFIGURE_SAMPLER =
      "io.opentelemetry.javaagent.shaded.io.opentelemetry.sdk.autoconfigure.spi.ConfigurableSamplerProvider";

  private final OtelSpiCollector collector = OtelSpiCollector.getInstance();

  @BeforeEach
  public void clearCollector() {
    collector.drain();
  }

  @Test
  public void singleOtelSpiIsReported(@TempDir Path tempDir) throws IOException {
    Path jarPath = buildJar(tempDir, "ext.jar", AUTOCONFIGURE_PROPAGATOR);

    try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
      ExtensionFinder.recordOtelSpiTelemetry(jar);
    }

    Collection<OtelSpiCollector.OtelSpiMetric> drained = collector.drain();
    assertEquals(1, drained.size());
    OtelSpiCollector.OtelSpiMetric metric = drained.iterator().next();
    assertEquals("otel.spi.detected", metric.metricName);
    assertTrue(metric.tags.contains("spi_class:" + AUTOCONFIGURE_PROPAGATOR));
    assertTrue(metric.tags.contains("source:extensions_path"));
  }

  @Test
  public void allFourAutoconfigureSpisAreReported(@TempDir Path tempDir) throws IOException {
    Path jarPath =
        buildJar(
            tempDir,
            "ext.jar",
            AUTOCONFIGURE_PROPAGATOR,
            AUTOCONFIGURE_RESOURCE,
            AUTOCONFIGURE_SAMPLER,
            AUTOCONFIGURE_EXPORTER);

    try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
      ExtensionFinder.recordOtelSpiTelemetry(jar);
    }

    assertEquals(
        new HashSet<>(
            java.util.Arrays.asList(
                AUTOCONFIGURE_PROPAGATOR,
                AUTOCONFIGURE_RESOURCE,
                AUTOCONFIGURE_SAMPLER,
                AUTOCONFIGURE_EXPORTER)),
        reportedFqns(collector.drain()));
  }

  @Test
  public void javaagentExtensionSpisAreReported(@TempDir Path tempDir) throws IOException {
    Path jarPath =
        buildJar(tempDir, "ext.jar", JAVAAGENT_INSTRUMENTATION_MODULE, JAVAAGENT_AGENT_LISTENER);

    try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
      ExtensionFinder.recordOtelSpiTelemetry(jar);
    }

    assertEquals(
        new HashSet<>(
            java.util.Arrays.asList(JAVAAGENT_INSTRUMENTATION_MODULE, JAVAAGENT_AGENT_LISTENER)),
        reportedFqns(collector.drain()));
  }

  @Test
  public void nonOtelSpiIsIgnored(@TempDir Path tempDir) throws IOException {
    Path jarPath =
        buildJar(
            tempDir,
            "ext.jar",
            "com.example.MyService",
            "org.springframework.context.ApplicationContextInitializer",
            "java.sql.Driver");

    try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
      ExtensionFinder.recordOtelSpiTelemetry(jar);
    }

    assertEquals(0, collector.drain().size());
  }

  @Test
  public void jarWithoutAnyServiceDescriptorsEmitsNothing(@TempDir Path tempDir)
      throws IOException {
    Path jarPath = tempDir.resolve("empty.jar");
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
      jos.putNextEntry(new JarEntry("README.txt"));
      jos.write("not an extension".getBytes());
      jos.closeEntry();
    }

    try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
      ExtensionFinder.recordOtelSpiTelemetry(jar);
    }

    assertEquals(0, collector.drain().size());
  }

  @Test
  public void mixedOtelAndNonOtelReportsOnlyOtel(@TempDir Path tempDir) throws IOException {
    Path jarPath =
        buildJar(
            tempDir,
            "ext.jar",
            AUTOCONFIGURE_PROPAGATOR,
            "com.example.MyService",
            JAVAAGENT_AGENT_LISTENER,
            "java.sql.Driver");

    try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
      ExtensionFinder.recordOtelSpiTelemetry(jar);
    }

    assertEquals(
        new HashSet<>(java.util.Arrays.asList(AUTOCONFIGURE_PROPAGATOR, JAVAAGENT_AGENT_LISTENER)),
        reportedFqns(collector.drain()));
  }

  private static Set<String> reportedFqns(Collection<OtelSpiCollector.OtelSpiMetric> drained) {
    Set<String> fqns = new HashSet<>();
    for (OtelSpiCollector.OtelSpiMetric metric : drained) {
      for (String tag : metric.tags) {
        if (tag.startsWith("spi_class:")) {
          fqns.add(tag.substring("spi_class:".length()));
        }
      }
    }
    return fqns;
  }

  /** Builds a jar with empty {@code META-INF/services/<fqn>} entries for each given FQN. */
  private static Path buildJar(Path dir, String name, String... serviceFqns) throws IOException {
    Path jarPath = dir.resolve(name);
    try (OutputStream out = Files.newOutputStream(jarPath);
        JarOutputStream jos = new JarOutputStream(out)) {
      for (String fqn : serviceFqns) {
        jos.putNextEntry(new JarEntry("META-INF/services/" + fqn));
        jos.closeEntry();
      }
    }
    return jarPath;
  }
}
