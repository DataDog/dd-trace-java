import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

final class JUnitReport {
  private static final String FINAL_STATUS_PROPERTY = "dd_tags[test.final_status]";
  private static final Pattern HASH_CODE = Pattern.compile("@[0-9a-f]{5,}");
  private static final Pattern LOCALHOST_PORT = Pattern.compile("localhost:[0-9]{2,5}");
  private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY =
      newDocumentBuilderFactory();
  private static final TransformerFactory TRANSFORMER_FACTORY = newTransformerFactory();

  private final Document document;

  private JUnitReport(Document document) {
    this.document = document;
  }

  static JUnitReport parse(Path xmlFile) throws Exception {
    var document = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder().parse(xmlFile.toFile());
    return new JUnitReport(document);
  }

  boolean addFileAttribute(String sourceFile) {
    var changed = false;
    for (var testcase : testcases()) {
      if (testcase.hasAttribute("time")) {
        changed |= !sourceFile.equals(testcase.getAttribute("file"));
        testcase.setAttribute("file", sourceFile);
      }
    }
    return changed;
  }

  boolean normalizeStableTestNames() {
    var changed = false;
    for (var testcase : testcases()) {
      var attributes = testcase.getAttributes();
      for (var i = 0; i < attributes.getLength(); i++) {
        var attribute = attributes.item(i);
        var value = attribute.getNodeValue();
        var normalized =
            LOCALHOST_PORT
                .matcher(HASH_CODE.matcher(value).replaceAll("@HASHCODE"))
                .replaceAll("localhost:PORT");
        if (!value.equals(normalized)) {
          attribute.setNodeValue(normalized);
          changed = true;
        }
      }
    }
    return changed;
  }

  /// Tags framework-emitted synthetic testcases so Test Optimization does not treat them as
  /// real failures.
  ///
  /// **Criteria for new entries:**
  /// - Must be a name the framework/runner emits itself — never use a name that a user-authored test
  ///   could legitimately have.
  /// - Must link to the source that emits the literal, pinned to a release tag (not a commit
  ///   hash).
  ///
  /// **Frameworks/libraries to audit before adding a name:** JUnit 4, JUnit Platform engines
  /// (Vintage / Jupiter), Gradle's JUnit and JUnit Platform adapters, TestNG, Spock, Kotest,
  /// Maven Surefire/Failsafe. JUnit 5's main sources do not define these literals — Vintage
  /// forwards `"initializationError"` from JUnit 4 as-is, and `"executionError"` is
  /// Gradle-specific.
  ///
  /// **Do not add** generic English names such as `"test exception"`. Spock feature methods
  /// (`def "..."()`), JUnit 5 `@DisplayName`, and Kotest specs can all produce them, so the
  /// tagger would silently mask real pass/fail outcomes.
  void tagSyntheticFailures() {
    Map<String, List<Element>> initializationErrorsByClassname = new LinkedHashMap<>();
    for (var testcase : testcases()) {
      switch (testcase.getAttribute("name")) {
        // JUnit 4 ErrorReportingRunner — initializationError
        // https://github.com/junit-team/junit4/blob/r4.13.2/src/main/java/org/junit/internal/runners/ErrorReportingRunner.java#L83
        // Gradle JUnit Platform listener — reuses the same name for synthetic container failures
        // https://github.com/gradle/gradle/blob/v8.14.5/platforms/jvm/testing-junit-platform/src/main/java/org/gradle/api/internal/tasks/testing/junitplatform/JUnitPlatformTestExecutionListener.java#L248
        // https://github.com/gradle/gradle/blob/v9.5.0/platforms/jvm/testing-jvm-infrastructure/src/main/java/org/gradle/api/internal/tasks/testing/junitplatform/JUnitPlatformTestExecutionListener.java#L426
        case "initializationError" ->
            initializationErrorsByClassname
                .computeIfAbsent(testcase.getAttribute("classname"), ignored -> new ArrayList<>())
                .add(testcase);
        // Gradle JUnit Platform listener — executionError, used when descendants already started
        // https://github.com/gradle/gradle/blob/v8.14.5/platforms/jvm/testing-junit-platform/src/main/java/org/gradle/api/internal/tasks/testing/junitplatform/JUnitPlatformTestExecutionListener.java#L248
        // https://github.com/gradle/gradle/blob/v9.5.0/platforms/jvm/testing-jvm-infrastructure/src/main/java/org/gradle/api/internal/tasks/testing/junitplatform/JUnitPlatformTestExecutionListener.java#L426
        case "executionError" ->
            addFinalStatusProperty(testcase, "skip", MissingPropertiesPlacement.APPEND_TO_TESTCASE);
        default -> {}
      }
    }

    for (var group : initializationErrorsByClassname.values()) {
      for (var i = 0; i < group.size() - 1; i++) {
        addFinalStatusProperty(
            group.get(i), "skip", MissingPropertiesPlacement.APPEND_TO_TESTCASE);
      }
    }
  }

  /// Tags every attempt of a retried test except the final one with `skip`, so Test Optimization
  /// counts only the decisive (last) attempt and ignores intermediate retries.
  ///
  /// Gradle's Develocity test-retry plugin re-runs a failing test and appends each attempt to the
  /// same per-class JUnit report as another `<testcase>` with an identical `classname#name`. The
  /// plugin stops retrying once a test passes or retries are exhausted, so the last such element in
  /// document order is the decisive attempt and every earlier duplicate is tagged `skip`.
  ///
  /// **Must run before {@link #normalizeStableTestNames()}.** Keys are matched on raw names so two
  /// genuinely distinct tests whose names differ only in an unstable token (e.g. `localhost:12345`
  /// vs `localhost:23456`, which normalization collapses to `localhost:PORT`) are not mistaken for
  /// retries of each other.
  ///
  /// Tagging uses the same `addFinalStatusProperty` path as {@link #tagFinalStatuses()}, which then
  /// leaves the already-tagged earlier attempts untouched and assigns the natural pass/fail status
  /// only to the final attempt.
  void tagRetriedAttempts() {
    var attemptsByKey = new LinkedHashMap<String, List<Element>>();
    for (var testcase : testcases()) {
      var key = testcase.getAttribute("classname") + "#" + testcase.getAttribute("name");
      attemptsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(testcase);
    }
    for (var attempts : attemptsByKey.values()) {
      for (var i = 0; i < attempts.size() - 1; i++) {
        addFinalStatusProperty(attempts.get(i), "skip", MissingPropertiesPlacement.FIRST_CHILD);
      }
    }
  }

  void tagFinalStatuses() {
    for (var testcase : testcases()) {
      if (hasFinalStatusProperty(testcase)) {
        continue;
      }
      addFinalStatusProperty(
          testcase, finalStatus(testcase), MissingPropertiesPlacement.FIRST_CHILD);
    }
  }

  /// Marks every testcase as `test.final_status=skip` for jobs that tolerate failures.
  ///
  /// The flaky test jobs (`test_flaky`, `test_flaky_inst`) run with `CONTINUE_ON_FAILURE=true`, so
  /// their result never gates the pipeline: it runs to completion regardless of pass or fail.
  /// `test.final_status` records that CI impact rather than the raw outcome, so every test in these
  /// jobs is `skip` — a failure is a non-blocking failure and a pass is a non-blocking pass. This
  /// keeps flaky failures from creating false-positive notifications and skewing SLIs, while the
  /// real per-test outcome stays available in `test.status` (derived from the `<failure>`,
  /// `<error>`, and `<skipped>` children, which are left in place). Always-green tests that could
  /// leave the flaky pipeline are then found with `@test.status:pass @test.final_status:skip`.
  ///
  /// **Must run before {@link #tagFinalStatuses()}** so the natural pass/fail status is never
  /// assigned. Testcases already tagged by {@link #tagRetriedAttempts()} or
  /// {@link #tagSyntheticFailures()} are left untouched (already `skip`).
  void tagAllAsSkipped() {
    for (var testcase : testcases()) {
      if (hasFinalStatusProperty(testcase)) {
        continue;
      }
      addFinalStatusProperty(testcase, "skip", MissingPropertiesPlacement.FIRST_CHILD);
    }
  }

  void write(Path xmlFile) throws Exception {
    Files.createDirectories(xmlFile.getParent());
    var tmpFile = Files.createTempFile(xmlFile.getParent(), "collect-results-", ".xml");
    try (OutputStream output = Files.newOutputStream(tmpFile)) {
      var transformer = TRANSFORMER_FACTORY.newTransformer();
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.transform(new DOMSource(document), new StreamResult(output));
    } catch (Exception e) {
      Files.deleteIfExists(tmpFile);
      throw e;
    }
    Files.move(tmpFile, xmlFile, StandardCopyOption.REPLACE_EXISTING);
  }

  private static DocumentBuilderFactory newDocumentBuilderFactory() {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static TransformerFactory newTransformerFactory() {
    var factory = TransformerFactory.newInstance();
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    return factory;
  }

  private List<Element> testcases() {
    var testcases = document.getElementsByTagName("testcase");
    var elements = new ArrayList<Element>(testcases.getLength());
    for (var i = 0; i < testcases.getLength(); i++) {
      elements.add((Element) testcases.item(i));
    }
    return elements;
  }

  private boolean addFinalStatusProperty(
      Element testcase, String status, MissingPropertiesPlacement missingPropertiesPlacement) {
    var properties = firstChildElement(testcase, "properties");
    if (properties != null) {
      if (propertiesHasFinalStatusProperty(properties)) {
        return false;
      }
    } else {
      properties = document.createElement("properties");
      if (missingPropertiesPlacement == MissingPropertiesPlacement.FIRST_CHILD) {
        testcase.insertBefore(properties, testcase.getFirstChild());
      } else {
        testcase.appendChild(properties);
      }
    }

    var property = document.createElement("property");
    property.setAttribute("name", FINAL_STATUS_PROPERTY);
    property.setAttribute("value", status);
    properties.appendChild(property);
    return true;
  }

  private static boolean hasFinalStatusProperty(Element testcase) {
    var properties = firstChildElement(testcase, "properties");
    return properties != null && propertiesHasFinalStatusProperty(properties);
  }

  private static boolean propertiesHasFinalStatusProperty(Element properties) {
    var children = properties.getChildNodes();
    for (var i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element element
          && "property".equals(element.getTagName())
          && FINAL_STATUS_PROPERTY.equals(element.getAttribute("name"))) {
        return true;
      }
    }
    return false;
  }

  private static String finalStatus(Element testcase) {
    if (hasChildElement(testcase, "failure") || hasChildElement(testcase, "error")) {
      return "fail";
    }
    if (hasChildElement(testcase, "skipped")) {
      return "skip";
    }
    return "pass";
  }

  private static Element firstChildElement(Element parent, String tagName) {
    var children = parent.getChildNodes();
    for (var i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
        return element;
      }
    }
    return null;
  }

  private static boolean hasChildElement(Element parent, String tagName) {
    return firstChildElement(parent, tagName) != null;
  }

  private enum MissingPropertiesPlacement {
    APPEND_TO_TESTCASE,
    FIRST_CHILD
  }
}
