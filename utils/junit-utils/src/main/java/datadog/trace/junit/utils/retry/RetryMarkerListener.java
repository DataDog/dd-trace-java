package datadog.trace.junit.utils.retry;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class RetryMarkerListener implements TestExecutionListener {

  // Not in the `dd.` namespace on purpose: that prefix is the agent's product-config namespace and
  // is policed by DDSpecification's strict system-property hygiene check. Keep in sync with the
  // `systemProperty(...)` set in dd-trace-java.configure-tests.gradle.kts.
  static final String OUTPUT_DIR_PROP = "datadog.test.results.dir";

  private final Map<String, Integer> executionCounts = new ConcurrentHashMap<>();
  private final Map<String, TestIdentifier> identifiers = new ConcurrentHashMap<>();

  @Override
  public void executionFinished(TestIdentifier id, TestExecutionResult result) {
    if (!id.isTest()) return;
    executionCounts.merge(id.getUniqueId(), 1, Integer::sum);
    identifiers.put(id.getUniqueId(), id);
  }

  // Called once per retry round; overwrites marker files so the last round wins.
  @Override
  public void testPlanExecutionFinished(TestPlan plan) {
    String outputDirProp = System.getProperty(OUTPUT_DIR_PROP);
    if (outputDirProp == null) return;
    Map<String, Set<String>> retriedByClass = retriedTestsByClass();
    if (retriedByClass.isEmpty()) return;
    Path outputDir = Paths.get(outputDirProp);
    try {
      Files.createDirectories(outputDir);
      for (Map.Entry<String, Set<String>> entry : retriedByClass.entrySet()) {
        writeMarkerFile(outputDir, entry.getKey(), entry.getValue());
      }
    } catch (Exception ex) {
      // Best-effort: a failed marker write only means a retried attempt may not be tagged as
      // `skip`;
      // never fail the test run over it. System.out/err is banned here by forbiddenApis, and the
      // JUnit Platform launcher has no logger on this path, so swallow rather than log.
    }
  }

  private Map<String, Set<String>> retriedTestsByClass() {
    Map<String, Set<String>> byClass = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : executionCounts.entrySet()) {
      if (entry.getValue() <= 1) continue;
      TestIdentifier id = identifiers.get(entry.getKey());
      byClass.computeIfAbsent(classNameOf(id), k -> new LinkedHashSet<>()).add(id.getDisplayName());
    }
    return byClass;
  }

  private static void writeMarkerFile(Path outputDir, String className, Set<String> testNames)
      throws Exception {
    Path file = outputDir.resolve("TEST-retried-" + className + ".xml");
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(writer);
      xml.writeStartDocument("UTF-8", "1.0");
      xml.writeStartElement("testsuite");
      xml.writeAttribute("name", className);
      for (String testName : testNames) {
        xml.writeEmptyElement("testcase");
        xml.writeAttribute("name", testName);
        xml.writeAttribute("classname", className);
      }
      xml.writeEndElement();
      xml.writeEndDocument();
      xml.flush();
    }
  }

  private static String classNameOf(TestIdentifier id) {
    TestSource src = id.getSource().orElse(null);
    if (src instanceof MethodSource) return ((MethodSource) src).getClassName();
    if (src instanceof ClassSource) return ((ClassSource) src).getClassName();
    return id.getDisplayName();
  }
}
