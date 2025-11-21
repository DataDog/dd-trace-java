package datadog.trace.civisibility

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.ReadContext
import com.jayway.jsonpath.WriteContext
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.civisibility.config.LibraryCapability
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.core.DDSpan
import freemarker.core.Environment
import freemarker.core.InvalidReferenceException
import freemarker.template.Template
import freemarker.template.TemplateException
import freemarker.template.TemplateExceptionHandler
import org.opentest4j.AssertionFailedError
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.w3c.dom.Document
import org.xmlunit.builder.DiffBuilder
import org.xmlunit.builder.Input
import org.xmlunit.diff.Diff
import org.xmlunit.util.Convert

import javax.xml.parsers.DocumentBuilderFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.util.regex.Pattern
import java.util.stream.Collectors

import static org.junit.jupiter.api.Assertions.assertEquals

abstract class CiVisibilityTestUtils {

  static final List<DynamicPath> EVENT_DYNAMIC_PATHS = [
    path("content.trace_id"),
    path("content.span_id"),
    path("content.parent_id"),
    path("content.test_session_id"),
    path("content.test_module_id"),
    path("content.test_suite_id"),
    path("content.metrics.process_id"),
    path("content.meta.['os.architecture']"),
    path("content.meta.['os.platform']"),
    path("content.meta.['os.version']"),
    path("content.meta.['runtime.name']"),
    path("content.meta.['runtime.vendor']"),
    path("content.meta.['runtime.version']"),
    path("content.meta.['ci.workspace_path']"),
    path("content.meta.['error.message']"),
    path("content.meta.library_version"),
    path("content.meta.runtime-id"),
    path("content.meta.['_dd.tracer_host']"),
    // Different events might or might not have the same start or duration.
    // Regardless, the values of these fields should be treated as different
    path("content.start", false),
    path("content.duration", false),
    path("content.metrics.['_dd.host.vcpu_count']", false),
    path("content.meta.['_dd.p.tid']", false),
    path("content.meta.['error.stack']", false),
  ]

  // ignored tags on assertion and fixture build
  static final List<String> IGNORED_TAGS = LibraryCapability.values().toList().stream().map(c -> "content.meta.['${c.asTag()}']").collect(Collectors.toList()) +
  ["content.meta.['_dd.integration']"]

  static final List<DynamicPath> COVERAGE_DYNAMIC_PATHS = [path("test_session_id"), path("test_suite_id"), path("span_id"),]

  private static final Comparator<Map<?,?>> EVENT_RESOURCE_COMPARATOR = Comparator.<Map<?,?>, String> comparing((Map m) -> {
    def content = (Map) m.get("content")
    return content.get("resource")
  }).thenComparing(Comparator.<Map<?,?>, String> comparing((Map m) -> {
    // module and session have the same resource name in headless mode
    return m.get("type")
  }).reversed())

  /**
   * Use this method to generate expected data templates
   */
  static void generateTemplates(String baseTemplatesPath, List<Map<?, ?>> events, List<Map<?, ?>> coverages, Collection<String> additionalDynamicPaths, List<String> ignoredTags = []) {
    if (!ignoredTags.empty) {
      events = removeTags(events, ignoredTags)
    }
    events.sort(EVENT_RESOURCE_COMPARATOR)

    def templateGenerator = new TemplateGenerator(new LabelGenerator())
    def compiledAdditionalReplacements = compile(additionalDynamicPaths)

    Files.createDirectories(Paths.get(baseTemplatesPath))
    Files.write(Paths.get(baseTemplatesPath, "events.ftl"), templateGenerator.generateTemplate(events, EVENT_DYNAMIC_PATHS + compiledAdditionalReplacements).bytes)
    Files.write(Paths.get(baseTemplatesPath, "coverages.ftl"), templateGenerator.generateTemplate(coverages, COVERAGE_DYNAMIC_PATHS + compiledAdditionalReplacements).bytes)
  }

  static void assertData(String baseTemplatesPath, List<CoverageReport> reports, Map<String, String> replacements) {
    def expectedReportEvent = getFreemarkerTemplate(baseTemplatesPath + "/coverage_report_event.ftl", replacements)
    def actualReportEvent = JSON_MAPPER.writeValueAsString(reports[0].event)

    compareJson(expectedReportEvent, actualReportEvent)

    def expectedReport = getFreemarkerTemplate(baseTemplatesPath + "/coverage_report.ftl", replacements)
    def actualReport = reports[0].report

    if (expectedReport.contains("<?xml")) {
      compareXml(expectedReport, actualReport)
    } else {
      assertEquals(expectedReport, actualReport)
    }
  }

  private static void compareXml(String expectedXml, String actualXml) {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance()
    dbf.setValidating(false)
    dbf.setFeature("http://xml.org/sax/features/validation", false)
    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false)
    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

    Document expected = Convert.toDocument(Input.fromString(expectedXml).build(), dbf)
    Document actual = Convert.toDocument(Input.fromString(actualXml).build(), dbf)

    Diff diff = DiffBuilder.compare(Input.fromDocument(actual))
    .withTest(Input.fromDocument(expected))
    .ignoreComments()
    .ignoreWhitespace()
    .checkForSimilar()
    .build()

    if (diff.hasDifferences()) {
      throw new AssertionError("XML mismatch: " + diff.toString())
    }
  }

  static Map<String, String> assertData(String baseTemplatesPath, List<Map<?, ?>> events, List<Map<?, ?>> coverages, Map<String, String> additionalReplacements, List<String> ignoredTags, List<String> additionalDynamicPaths = []) {
    events.sort(EVENT_RESOURCE_COMPARATOR)

    def labelGenerator = new LabelGenerator()
    def templateGenerator = new TemplateGenerator(labelGenerator)

    def replacementMap
    replacementMap = templateGenerator.generateReplacementMap(events, EVENT_DYNAMIC_PATHS + compile(additionalDynamicPaths))
    replacementMap = templateGenerator.generateReplacementMap(coverages, COVERAGE_DYNAMIC_PATHS)

    for (Map.Entry<String, String> e : additionalReplacements.entrySet()) {
      replacementMap.put(labelGenerator.forKey(e.key), "\"$e.value\"")
    }

    // ignore provided tags
    events = removeTags(events, ignoredTags)

    def expectedEvents = getFreemarkerTemplate(baseTemplatesPath + "/events.ftl", replacementMap, events)
    def actualEvents = JSON_MAPPER.writeValueAsString(events)

    compareJson(expectedEvents, actualEvents)

    def expectedCoverages = getFreemarkerTemplate(baseTemplatesPath + "/coverages.ftl", replacementMap, coverages)
    def actualCoverages = JSON_MAPPER.writeValueAsString(coverages)
    compareJson(expectedCoverages, actualCoverages)

    return replacementMap
  }

  private static void compareJson(String expectedJson, String actualJson) {
    def environment = System.getenv()
    def ciRun = environment.get("GITHUB_ACTION") != null || environment.get("GITLAB_CI") != null
    def comparisonMode = ciRun ? JSONCompareMode.LENIENT : JSONCompareMode.NON_EXTENSIBLE

    try {
      JSONAssert.assertEquals(expectedJson, actualJson, comparisonMode)
    } catch (AssertionError e) {
      if (ciRun) {
        // When running in CI the assertion error message does not contain the actual diff,
        // so we print the events to the console to help debug the issue
        println "Expected JSON: $expectedJson"
        println "Actual JSON: $actualJson"
      }
      throw new AssertionFailedError("Expected and actual JSON mismatch", expectedJson, actualJson, e)
    }
  }

  static boolean assertTestsOrder(List<Map<?, ?>> events, List<TestFQN> expectedOrder) {
    def identifiers = getTestIdentifiers(events)
    if (identifiers != expectedOrder) {
      throw new AssertionError("Expected order: $expectedOrder, but got: $identifiers")
    }
    return true
  }

  static List<TestFQN> getTestIdentifiers(List<Map<?,?>> events) {
    events.sort(Comparator.comparing {
      it['content']['start'] as Long
    })
    def testIdentifiers = []
    for (Map event : events) {
      if (event['content']['meta']['test.name']) {
        testIdentifiers.add(new TestFQN(event['content']['meta']['test.suite'] as String, event['content']['meta']['test.name'] as String))
      }
    }
    return testIdentifiers
  }

  static List<Map<?, ?>> removeTags(List<Map<?, ?>> events, List<String> tags) {
    def filteredEvents = []

    for (Map<?, ?> event : events) {
      ReadContext ctx = JsonPath.parse(event, JSON_PATH_CONFIG)
      for (String tag : tags) {
        ctx.delete(path(tag).path)
      }
      filteredEvents.add(ctx.json())
    }

    return filteredEvents
  }

  // Will sort traces in the following order: TEST -> SUITE -> MODULE -> SESSION
  static class SortTracesByType implements Comparator<List<DDSpan>> {
    @Override
    int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return Integer.compare(rootSpanTypeToVal(o1), rootSpanTypeToVal(o2))
    }

    int rootSpanTypeToVal(List<DDSpan> trace) {
      assert !trace.isEmpty()
      def spanType = trace.get(0).getSpanType()
      switch (spanType) {
        case DDSpanTypes.TEST:
        return 0
        case DDSpanTypes.TEST_SUITE_END:
        return 1
        case DDSpanTypes.TEST_MODULE_END:
        return 2
        case DDSpanTypes.TEST_SESSION_END:
        return 3
        default:
        return 4
      }
    }
  }

  static final Configuration JSON_PATH_CONFIG = Configuration.builder()
  .options(Option.SUPPRESS_EXCEPTIONS)
  .build()

  static final ObjectMapper JSON_MAPPER = new ObjectMapper() { {
      enable(SerializationFeature.INDENT_OUTPUT)
      enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    }
  }

  static final TemplateExceptionHandler SUPPRESS_EXCEPTION_HANDLER = new TemplateExceptionHandler() {
    @Override
    void handleTemplateException(TemplateException e, Environment environment, Writer writer) throws TemplateException {
      if (e instanceof InvalidReferenceException) {
        writer.write('"<VALUE_MISSING>"')
      } else {
        throw e
      }
    }
  }

  static final freemarker.template.Configuration FREEMARKER = new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_30) { {
      setClassLoaderForTemplateLoading(CiVisibilityTestUtils.classLoader, "")
      setDefaultEncoding("UTF-8")
      setTemplateExceptionHandler(SUPPRESS_EXCEPTION_HANDLER)
      setLogTemplateExceptions(false)
      setWrapUncheckedExceptions(true)
      setFallbackOnNullLoopVariable(false)
      setNumberFormat("0.######")
    }
  }

  static String getFreemarkerTemplate(String templatePath, Map<String, Object> replacements, List<Map<?, ?>> replacementsSource = []) {
    try {
      Template coveragesTemplate = FREEMARKER.getTemplate(templatePath)
      StringWriter coveragesOut = new StringWriter()
      coveragesTemplate.process(replacements, coveragesOut)
      return coveragesOut.toString()
    } catch (Exception e) {
      throw new RuntimeException("Could not get Freemarker template " + templatePath + "; replacements map: " + replacements + "; replacements source: " + replacementsSource, e)
    }
  }

  private static final class TemplateGenerator {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\"(\\\$\\{.*?\\})\"")

    private final Map<String, String> uniqueValues = new HashMap<>()
    private final Map<String, String> nonUniqueValues = new HashMap<>()
    private final LabelGenerator label

    TemplateGenerator(LabelGenerator label) {
      this.label = label
    }

    String generateTemplate(Collection<Map<?, ?>> objects, List<DynamicPath> dynamicPaths) {
      for (Map<?, ?> object : objects) {
        WriteContext ctx = JsonPath.parse(object, JSON_PATH_CONFIG)
        for (DynamicPath dynamicPath : dynamicPaths) {
          ctx.map(dynamicPath.path, (currentValue, config) -> {
            if (dynamicPath.unique) {
              return uniqueValues.computeIfAbsent(currentValue, (k) -> label.forTemplateKey(dynamicPath.rawPath))
            }

            return label.forTemplateKey(dynamicPath.rawPath)
          })
        }
      }
      return JSON_MAPPER
      .writeValueAsString(objects)
      .replaceAll(PLACEHOLDER_PATTERN, "\$1") // remove quotes around placeholders
    }

    Map<String, String> generateReplacementMap(Collection<Map<?, ?>> objects, List<DynamicPath> dynamicPaths) {
      for (Map<?, ?> object : objects) {
        ReadContext ctx = JsonPath.parse(object, JSON_PATH_CONFIG)
        for (DynamicPath dynamicPath : dynamicPaths) {
          def value = ctx.read(dynamicPath.path)
          if (value != null) {
            if (value instanceof String) {
              value = '"' + // restore quotes around string values
              value.replace('"', '\\"') + // escape quotes inside string values
              '"' // restore quotes around string values
            }
            if (dynamicPath.unique) {
              uniqueValues.computeIfAbsent(value, (k) -> label.forKey(dynamicPath.rawPath))
            } else {
              nonUniqueValues.put(label.forKey(dynamicPath.rawPath), value)
            }
          }
        }
      }
      return invert(uniqueValues) + nonUniqueValues
    }
  }

  private static final class LabelGenerator {
    private static final Pattern ERASED_CHARS = Pattern.compile("[\\[\\]']")
    private static final Pattern REPLACED_CHARS = Pattern.compile("[.-]")

    private final Map<String, Integer> usageCounters = new HashMap<>()

    String forTemplateKey(String key) {
      return "\${" + forKey(key) + "}"
    }

    String forKey(String key) {
      def usages = usageCounters.merge(key, 1, Integer::sum)
      def sanitizedKey = key.replaceAll(ERASED_CHARS, "").replaceAll(REPLACED_CHARS, "_")
      return sanitizedKey + (usages == 1 ? "" : "_${usages}")
    }
  }

  private static <K, V> Map<V, K> invert(Map<K, V> map) {
    Map<V, K> inverted = new HashMap(map.size())
    for (Map.Entry<K, V> e : map.entrySet()) {
      inverted.put(e.value, e.key)
    }
    return inverted
  }

  private static List<DynamicPath> compile(Iterable<String> rawPaths) {
    def compiledPaths = []
    for (String rawPath : rawPaths) {
      compiledPaths += path(rawPath)
    }
    return compiledPaths
  }

  private static DynamicPath path(String rawPath, boolean unique = true) {
    return new DynamicPath(rawPath, JsonPath.compile(rawPath), unique)
  }

  private static final class DynamicPath {
    private final String rawPath
    private final JsonPath path
    // if true, same values are replaced with same placeholders;
    // otherwise every path gets its own placeholder, even if its value is non-unique
    private final boolean unique

    DynamicPath(String rawPath, JsonPath path, boolean unique) {
      this.rawPath = rawPath
      this.path = path
      this.unique = unique
    }
  }

  static final class CoverageReport {
    final Map<String, Object> event
    final String report

    CoverageReport(Map<String, Object> event, String report) {
      this.event = event
      this.report = report
    }
  }
}
