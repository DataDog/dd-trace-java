# Retry Marker Plan

## Goal

For any retried test, all attempts **except the last** get `dd_tags[test.final_status] = skip`
via the existing `addFinalStatusProperty` mechanism in `JUnitReport.java`. The last attempt keeps
its natural outcome (`pass` or `fail`).

## Approach

`RetryMarkerListener` (JUnit Platform `TestExecutionListener`) runs inside the test JVM. It tracks
retries by `TestIdentifier.getUniqueId()` — immune to display-name instability — and writes
`TEST-retried-{classname}.xml` alongside the standard Gradle JUnit XML. `ResultCollector` reads
these marker files and calls `report.tagRetriedTests(keys)` **before**
`report.normalizeStableTestNames()` so that names in the marker file match the XML before
normalization rewrites them. Marker files must be called before normalization because
`normalizeStableTestNames()` collapses distinct unstable names (e.g. `localhost:12345` and
`localhost:23456` both become `localhost:PORT`), which would cause `tagRetriedTests` to incorrectly
skip genuinely distinct tests if matching happened after normalization. The marker-file approach is
safe because only actually-retried tests (tracked by unique ID) enter the marker file.

Each retry round is a separate `TestPlan`; the listener accumulates counts across all rounds and
overwrites the marker file after each round, so the final write is correct. Forked tests
(`forkEvery = 1`) each run in their own JVM; per-class files avoid write races.

## Call order in `ResultCollector.collect()`

```
applyRetryMarkers(dir, report)   ← BEFORE normalization
normalizeStableTestNames()
tagSyntheticFailures()
tagFinalStatuses()
```

## Files

### 1. NEW `utils/junit-utils/src/main/java/datadog/trace/junit/utils/retry/RetryMarkerListener.java`

```java
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

  static final String OUTPUT_DIR_PROP = "dd.test.results.dir";

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
    var retriedByClass = retriedTestsByClass();
    if (retriedByClass.isEmpty()) return;
    var outputDir = Paths.get(outputDirProp);
    try {
      Files.createDirectories(outputDir);
      for (var entry : retriedByClass.entrySet()) {
        writeMarkerFile(outputDir, entry.getKey(), entry.getValue());
      }
    } catch (Exception ex) {
      System.err.println("[RetryMarkerListener] Failed to write retry markers: " + ex.getMessage());
    }
  }

  private Map<String, Set<String>> retriedTestsByClass() {
    var byClass = new LinkedHashMap<String, Set<String>>();
    for (var entry : executionCounts.entrySet()) {
      if (entry.getValue() <= 1) continue;
      var id = identifiers.get(entry.getKey());
      byClass.computeIfAbsent(classNameOf(id), k -> new LinkedHashSet<>()).add(id.getDisplayName());
    }
    return byClass;
  }

  private static void writeMarkerFile(Path outputDir, String className, Set<String> testNames)
      throws Exception {
    var file = outputDir.resolve("TEST-retried-" + className + ".xml");
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(writer);
      xml.writeStartDocument("UTF-8", "1.0");
      xml.writeStartElement("testsuite");
      xml.writeAttribute("name", className);
      for (var testName : testNames) {
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
```

### 2. NEW `utils/junit-utils/src/main/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener`

```
datadog.trace.junit.utils.retry.RetryMarkerListener
```

### 3. EDIT `utils/junit-utils/build.gradle.kts`

Add one line to `dependencies`:
```kotlin
compileOnly(libs.junit-platform-launcher)
```

### 4. EDIT `buildSrc/src/main/kotlin/dd-trace-java.configure-tests.gradle.kts`

Inside the first `tasks.withType<Test>().configureEach` block, after the `java.util.prefs.userRoot` line:
```kotlin
systemProperty("dd.test.results.dir", reports.junitXml.outputLocation.get().asFile.absolutePath)
```

### 5. EDIT `.gitlab/collect-result/JUnitReport.java`

Add imports:
```java
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
```

Add two methods after `tagFinalStatuses()`:

```java
  Set<String> testcaseKeys() {
    var keys = new java.util.LinkedHashSet<String>();
    for (var testcase : testcases()) {
      keys.add(testcase.getAttribute("classname") + "#" + testcase.getAttribute("name"));
    }
    return keys;
  }

  // Tags all <testcase> elements except the last for each retried key as skip.
  // Must be called before tagFinalStatuses() so hasFinalStatusProperty() skips tagged entries.
  void tagRetriedTests(Set<String> retriedTestKeys) {
    if (retriedTestKeys.isEmpty()) return;
    var testcasesByKey = new LinkedHashMap<String, List<Element>>();
    for (var testcase : testcases()) {
      var key = testcase.getAttribute("classname") + "#" + testcase.getAttribute("name");
      if (retriedTestKeys.contains(key)) {
        testcasesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(testcase);
      }
    }
    for (var attempts : testcasesByKey.values()) {
      for (var i = 0; i < attempts.size() - 1; i++) {
        addFinalStatusProperty(attempts.get(i), "skip", MissingPropertiesPlacement.FIRST_CHILD);
      }
    }
  }
```

### 6. EDIT `.gitlab/collect-result/ResultCollector.java`

**a) `collect(Path sourceXml)`** — skip marker files; apply markers before normalization:

```java
  private void collect(Path sourceXml) throws Exception {
    if (fileName(sourceXml).startsWith("TEST-retried-")) return;

    var aggregatedName = aggregatedFileName(sourceXml);
    var targetXml = resultsDir.resolve(aggregatedName);
    System.out.print("- " + toUnixString(sourceXml) + " as " + aggregatedName);

    var sourceFile = sourceFileResolver.resolve(sourceXml);
    var report = JUnitReport.parse(sourceXml);
    var reportChangedBeforeFinalStatus = report.addFileAttribute(sourceFile);
    applyRetryMarkers(sourceXml.getParent(), report);  // before normalizeStableTestNames
    reportChangedBeforeFinalStatus |= report.normalizeStableTestNames();
    report.tagSyntheticFailures();
    report.tagFinalStatuses();
    report.write(targetXml);

    if (reportChangedBeforeFinalStatus) {
      System.out.print(" (non-stable test names detected)");
    }
    System.out.println();
  }
```

**b) Add `applyRetryMarkers`** after `collect()`:

```java
  private static void applyRetryMarkers(Path dir, JUnitReport report) {
    if (dir == null) return;
    try (var paths = Files.list(dir)) {
      paths
          .filter(p -> fileName(p).startsWith("TEST-retried-") && fileName(p).endsWith(".xml"))
          .forEach(markerFile -> {
            try {
              report.tagRetriedTests(JUnitReport.parse(markerFile).testcaseKeys());
            } catch (Exception e) {
              System.err.println(
                  "[ResultCollector] Failed to apply retry markers from "
                      + markerFile.getFileName() + ": " + e.getMessage());
            }
          });
    } catch (IOException e) {
      System.err.println(
          "[ResultCollector] Failed to scan for retry markers in " + dir + ": " + e.getMessage());
    }
  }
```

## Examples

**Flaky (retried → passed):**
```
XML:  <testcase name="t()"><failure/></testcase>   attempt 1
      <testcase name="t()"/>                        attempt 2

applyRetryMarkers → tagRetriedTests({"C#t()"}):
  attempt 1 → skip (tagged), attempt 2 → untagged

tagFinalStatuses:
  attempt 1 → skip ✓   attempt 2 → pass ✓
```

**Always failing (retried → still fails):**
```
XML:  <testcase name="t()"><failure/></testcase>   attempt 1
      <testcase name="t()"><failure/></testcase>   attempt 2
      <testcase name="t()"><failure/></testcase>   attempt 3

applyRetryMarkers → tagRetriedTests({"C#t()"}):
  attempts 1–2 → skip (tagged), attempt 3 → untagged

tagFinalStatuses:
  attempts 1–2 → skip ✓   attempt 3 → fail ✓
```
