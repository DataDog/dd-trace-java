package datadog.trace.civisibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ReadContext;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.core.DDSpan;
import freemarker.core.Environment;
import freemarker.core.InvalidReferenceException;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Assertions;
import org.opentest4j.AssertionFailedError;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.w3c.dom.Document;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;
import org.xmlunit.util.Convert;

public abstract class CiVisibilityTestUtils {

  public static final List<DynamicPath> EVENT_DYNAMIC_PATHS =
      Collections.unmodifiableList(
          Arrays.asList(
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
              // Different events might or might not have the same start or duration. Regardless,
              // the values of these fields should be treated as different.
              path("content.start", false),
              path("content.duration", false),
              path("content.metrics.['_dd.host.vcpu_count']", false),
              path("content.meta.['_dd.p.tid']", false),
              path("content.meta.['error.stack']", false)));

  // ignored tags on assertion and fixture build
  public static final List<String> IGNORED_TAGS;

  static {
    List<String> ignored =
        Arrays.stream(LibraryCapability.values())
            .map(c -> "content.meta.['" + c.asTag() + "']")
            .collect(Collectors.toList());
    ignored.add("content.meta.['_dd.integration']");
    ignored.add("content.meta.['_dd.svc_src']");
    IGNORED_TAGS = Collections.unmodifiableList(ignored);
  }

  public static final List<DynamicPath> COVERAGE_DYNAMIC_PATHS =
      Collections.unmodifiableList(
          Arrays.asList(path("test_session_id"), path("test_suite_id"), path("span_id")));

  private static final Comparator<Map<?, ?>> EVENT_RESOURCE_COMPARATOR =
      Comparator.<Map<?, ?>, String>comparing(
              m -> {
                Map<?, ?> content = (Map<?, ?>) m.get("content");
                return (String) content.get("resource");
              })
          .thenComparing(
              Comparator.<Map<?, ?>, String>comparing(
                      // module and session have the same resource name in headless mode
                      m -> (String) m.get("type"))
                  .reversed());

  /** Use this method to generate expected data templates. */
  public static void generateTemplates(
      String baseTemplatesPath,
      List<? extends Map<?, ?>> events,
      List<? extends Map<?, ?>> coverages,
      Collection<String> additionalDynamicPaths) {
    generateTemplates(
        baseTemplatesPath, events, coverages, additionalDynamicPaths, Collections.emptyList());
  }

  public static void generateTemplates(
      String baseTemplatesPath,
      List<? extends Map<?, ?>> events,
      List<? extends Map<?, ?>> coverages,
      Collection<String> additionalDynamicPaths,
      List<String> ignoredTags) {
    generateTemplates(
        baseTemplatesPath,
        events,
        coverages,
        additionalDynamicPaths,
        ignoredTags,
        Collections.emptyList());
  }

  public static void generateTemplates(
      String baseTemplatesPath,
      List<? extends Map<?, ?>> events,
      List<? extends Map<?, ?>> coverages,
      Collection<String> additionalDynamicPaths,
      List<String> ignoredTags,
      Collection<String> additionalNonUniqueDynamicPaths) {
    List<Map<?, ?>> mutableEvents = new ArrayList<>(events);
    if (!ignoredTags.isEmpty()) {
      mutableEvents = removeTags(mutableEvents, ignoredTags);
    }
    mutableEvents.sort(EVENT_RESOURCE_COMPARATOR);

    TemplateGenerator templateGenerator = new TemplateGenerator(new LabelGenerator());
    List<DynamicPath> compiledAdditionalReplacements = compile(additionalDynamicPaths);
    compiledAdditionalReplacements.addAll(compileNonUnique(additionalNonUniqueDynamicPaths));

    try {
      Files.createDirectories(Paths.get(baseTemplatesPath));
      List<DynamicPath> eventPaths = new ArrayList<>(EVENT_DYNAMIC_PATHS);
      eventPaths.addAll(compiledAdditionalReplacements);
      Files.write(
          Paths.get(baseTemplatesPath, "events.ftl"),
          templateGenerator
              .generateTemplate(mutableEvents, eventPaths)
              .getBytes(StandardCharsets.UTF_8));

      List<DynamicPath> coveragePaths = new ArrayList<>(COVERAGE_DYNAMIC_PATHS);
      coveragePaths.addAll(compiledAdditionalReplacements);
      Files.write(
          Paths.get(baseTemplatesPath, "coverages.ftl"),
          templateGenerator
              .generateTemplate(coverages, coveragePaths)
              .getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void assertData(
      String baseTemplatesPath, List<CoverageReport> reports, Map<String, ?> replacements) {
    try {
      String expectedReportEvent =
          getFreemarkerTemplate(baseTemplatesPath + "/coverage_report_event.ftl", replacements);
      String actualReportEvent = JSON_MAPPER.writeValueAsString(reports.get(0).event);

      compareJson(expectedReportEvent, actualReportEvent);

      String expectedReport =
          getFreemarkerTemplate(baseTemplatesPath + "/coverage_report.ftl", replacements);
      String actualReport = reports.get(0).report;

      if (expectedReport.contains("<?xml")) {
        compareXml(expectedReport, actualReport);
      } else {
        Assertions.assertEquals(expectedReport, actualReport);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void compareXml(String expectedXml, String actualXml) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setValidating(false);
    dbf.setFeature("http://xml.org/sax/features/validation", false);
    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

    Document expected = Convert.toDocument(Input.fromString(expectedXml).build(), dbf);
    Document actual = Convert.toDocument(Input.fromString(actualXml).build(), dbf);

    Diff diff =
        DiffBuilder.compare(Input.fromDocument(actual))
            .withTest(Input.fromDocument(expected))
            .ignoreComments()
            .ignoreWhitespace()
            .checkForSimilar()
            .build();

    if (diff.hasDifferences()) {
      throw new AssertionError("XML mismatch: " + diff.toString());
    }
  }

  public static Map<String, String> assertData(
      String baseTemplatesPath,
      List<? extends Map<?, ?>> events,
      List<? extends Map<?, ?>> coverages,
      Map<String, ?> additionalReplacements,
      List<String> ignoredTags) {
    return assertData(
        baseTemplatesPath,
        events,
        coverages,
        additionalReplacements,
        ignoredTags,
        Collections.emptyList());
  }

  public static Map<String, String> assertData(
      String baseTemplatesPath,
      List<? extends Map<?, ?>> events,
      List<? extends Map<?, ?>> coverages,
      Map<String, ?> additionalReplacements,
      List<String> ignoredTags,
      List<String> additionalDynamicPaths) {
    return assertData(
        baseTemplatesPath,
        events,
        coverages,
        additionalReplacements,
        ignoredTags,
        additionalDynamicPaths,
        Collections.emptyList());
  }

  public static Map<String, String> assertData(
      String baseTemplatesPath,
      List<? extends Map<?, ?>> events,
      List<? extends Map<?, ?>> coverages,
      Map<String, ?> additionalReplacements,
      List<String> ignoredTags,
      List<String> additionalDynamicPaths,
      List<String> additionalNonUniqueDynamicPaths) {
    List<Map<?, ?>> mutableEvents = new ArrayList<>(events);
    mutableEvents.sort(EVENT_RESOURCE_COMPARATOR);

    LabelGenerator labelGenerator = new LabelGenerator();
    TemplateGenerator templateGenerator = new TemplateGenerator(labelGenerator);

    List<DynamicPath> eventPaths = new ArrayList<>(EVENT_DYNAMIC_PATHS);
    eventPaths.addAll(compile(additionalDynamicPaths));
    eventPaths.addAll(compileNonUnique(additionalNonUniqueDynamicPaths));
    templateGenerator.generateReplacementMap(mutableEvents, eventPaths);
    Map<String, String> replacementMap =
        templateGenerator.generateReplacementMap(coverages, COVERAGE_DYNAMIC_PATHS);

    // Tolerate Groovy callers passing GString values: convert each value to String via
    // String.valueOf before storing it in the replacement map.
    for (Map.Entry<String, ?> e : additionalReplacements.entrySet()) {
      replacementMap.put(
          labelGenerator.forKey(e.getKey()), "\"" + String.valueOf(e.getValue()) + "\"");
    }

    // ignore provided tags
    mutableEvents = removeTags(mutableEvents, ignoredTags);

    try {
      String expectedEvents =
          getFreemarkerTemplate(baseTemplatesPath + "/events.ftl", replacementMap, mutableEvents);
      String actualEvents = JSON_MAPPER.writeValueAsString(mutableEvents);

      compareJson(expectedEvents, actualEvents);

      String expectedCoverages =
          getFreemarkerTemplate(baseTemplatesPath + "/coverages.ftl", replacementMap, coverages);
      String actualCoverages = JSON_MAPPER.writeValueAsString(coverages);
      compareJson(expectedCoverages, actualCoverages);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return replacementMap;
  }

  private static void compareJson(String expectedJson, String actualJson) {
    Map<String, String> environment = System.getenv();
    boolean ciRun =
        environment.get("GITHUB_ACTION") != null || environment.get("GITLAB_CI") != null;
    JSONCompareMode comparisonMode =
        ciRun ? JSONCompareMode.LENIENT : JSONCompareMode.NON_EXTENSIBLE;

    try {
      JSONAssert.assertEquals(expectedJson, actualJson, comparisonMode);
    } catch (org.json.JSONException jsonException) {
      throw new RuntimeException(jsonException);
    } catch (AssertionError e) {
      if (ciRun) {
        // When running in CI the assertion error message does not contain the actual diff,
        // so we print the events to the console to help debug the issue.
        System.out.println("Expected JSON: " + expectedJson);
        System.out.println("Actual JSON: " + actualJson);
      }
      throw new AssertionFailedError(
          "Expected and actual JSON mismatch", expectedJson, actualJson, e);
    }
  }

  public static boolean assertTestsOrder(
      List<? extends Map<?, ?>> events, List<TestFQN> expectedOrder) {
    List<TestFQN> identifiers = getTestIdentifiers(events);
    if (!identifiers.equals(expectedOrder)) {
      throw new AssertionError("Expected order: " + expectedOrder + ", but got: " + identifiers);
    }
    return true;
  }

  public static List<TestFQN> getTestIdentifiers(List<? extends Map<?, ?>> events) {
    List<Map<?, ?>> sorted = new ArrayList<>(events);
    sorted.sort(
        Comparator.comparing(
            it -> ((Number) ((Map<?, ?>) it.get("content")).get("start")).longValue()));
    List<TestFQN> testIdentifiers = new ArrayList<>();
    for (Map<?, ?> event : sorted) {
      Map<?, ?> content = (Map<?, ?>) event.get("content");
      Map<?, ?> meta = (Map<?, ?>) content.get("meta");
      Object testName = meta.get("test.name");
      if (testName != null) {
        testIdentifiers.add(new TestFQN((String) meta.get("test.suite"), (String) testName));
      }
    }
    return testIdentifiers;
  }

  public static List<Map<?, ?>> removeTags(List<? extends Map<?, ?>> events, List<String> tags) {
    List<Map<?, ?>> filteredEvents = new ArrayList<>();
    for (Map<?, ?> event : events) {
      DocumentContext ctx = JsonPath.parse(event, JSON_PATH_CONFIG);
      for (String tag : tags) {
        ctx.delete(path(tag).path);
      }
      filteredEvents.add(ctx.json());
    }
    return filteredEvents;
  }

  // Sort traces in the following order: TEST -> SUITE -> MODULE -> SESSION
  public static class SortTracesByType implements Comparator<List<DDSpan>>, Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return Integer.compare(rootSpanTypeToVal(o1), rootSpanTypeToVal(o2));
    }

    public int rootSpanTypeToVal(List<DDSpan> trace) {
      assert !trace.isEmpty();
      CharSequence spanType = trace.get(0).getSpanType();
      if (spanType == null) {
        return 4;
      }
      if (DDSpanTypes.TEST.contentEquals(spanType)) {
        return 0;
      }
      if (DDSpanTypes.TEST_SUITE_END.contentEquals(spanType)) {
        return 1;
      }
      if (DDSpanTypes.TEST_MODULE_END.contentEquals(spanType)) {
        return 2;
      }
      if (DDSpanTypes.TEST_SESSION_END.contentEquals(spanType)) {
        return 3;
      }
      return 4;
    }
  }

  public static final Configuration JSON_PATH_CONFIG =
      Configuration.builder().options(Option.SUPPRESS_EXCEPTIONS).build();

  public static final ObjectMapper JSON_MAPPER = createJsonMapper();

  private static ObjectMapper createJsonMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(SerializationFeature.INDENT_OUTPUT);
    mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    return mapper;
  }

  public static final TemplateExceptionHandler SUPPRESS_EXCEPTION_HANDLER =
      new TemplateExceptionHandler() {
        @Override
        public void handleTemplateException(
            TemplateException e, Environment environment, Writer writer) throws TemplateException {
          if (e instanceof InvalidReferenceException) {
            try {
              writer.write("\"<VALUE_MISSING>\"");
            } catch (java.io.IOException ioe) {
              throw new TemplateException(ioe, environment);
            }
          } else {
            throw e;
          }
        }
      };

  public static final freemarker.template.Configuration FREEMARKER = createFreemarker();

  private static freemarker.template.Configuration createFreemarker() {
    freemarker.template.Configuration config =
        new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_30);
    config.setClassLoaderForTemplateLoading(CiVisibilityTestUtils.class.getClassLoader(), "");
    config.setDefaultEncoding("UTF-8");
    config.setTemplateExceptionHandler(SUPPRESS_EXCEPTION_HANDLER);
    config.setLogTemplateExceptions(false);
    config.setWrapUncheckedExceptions(true);
    config.setFallbackOnNullLoopVariable(false);
    config.setNumberFormat("0.######");
    return config;
  }

  public static String getFreemarkerTemplate(String templatePath, Map<String, ?> replacements) {
    return getFreemarkerTemplate(templatePath, replacements, Collections.emptyList());
  }

  public static String getFreemarkerTemplate(
      String templatePath,
      Map<String, ?> replacements,
      List<? extends Map<?, ?>> replacementsSource) {
    try {
      Template template = FREEMARKER.getTemplate(templatePath);
      StringWriter out = new StringWriter();
      template.process(replacements, out);
      return out.toString();
    } catch (Exception e) {
      throw new RuntimeException(
          "Could not get Freemarker template "
              + templatePath
              + "; replacements map: "
              + replacements
              + "; replacements source: "
              + replacementsSource,
          e);
    }
  }

  private static final class TemplateGenerator {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\"(\\$\\{.*?\\})\"");

    private final Map<String, String> uniqueValues = new HashMap<>();
    private final Map<String, String> nonUniqueValues = new HashMap<>();
    private final LabelGenerator label;

    TemplateGenerator(LabelGenerator label) {
      this.label = label;
    }

    String generateTemplate(Collection<? extends Map<?, ?>> objects, List<DynamicPath> dynamicPaths)
        throws Exception {
      for (Map<?, ?> object : objects) {
        DocumentContext ctx = JsonPath.parse(object, JSON_PATH_CONFIG);
        for (DynamicPath dynamicPath : dynamicPaths) {
          ctx.map(
              dynamicPath.path,
              (currentValue, config) -> {
                if (dynamicPath.unique) {
                  return uniqueValues.computeIfAbsent(
                      String.valueOf(currentValue), k -> label.forTemplateKey(dynamicPath.rawPath));
                }
                return label.forTemplateKey(dynamicPath.rawPath);
              });
        }
      }
      // remove quotes around placeholders
      return PLACEHOLDER_PATTERN.matcher(JSON_MAPPER.writeValueAsString(objects)).replaceAll("$1");
    }

    Map<String, String> generateReplacementMap(
        Collection<? extends Map<?, ?>> objects, List<DynamicPath> dynamicPaths) {
      for (Map<?, ?> object : objects) {
        ReadContext ctx = JsonPath.parse(object, JSON_PATH_CONFIG);
        for (DynamicPath dynamicPath : dynamicPaths) {
          Object value = ctx.read(dynamicPath.path);
          if (value != null) {
            String stringValue;
            if (value instanceof String) {
              stringValue = "\"" + ((String) value).replace("\"", "\\\"") + "\"";
            } else {
              stringValue = String.valueOf(value);
            }
            if (dynamicPath.unique) {
              uniqueValues.computeIfAbsent(stringValue, k -> label.forKey(dynamicPath.rawPath));
            } else {
              nonUniqueValues.put(label.forKey(dynamicPath.rawPath), stringValue);
            }
          }
        }
      }
      Map<String, String> result = new LinkedHashMap<>(invert(uniqueValues));
      result.putAll(nonUniqueValues);
      return result;
    }
  }

  private static final class LabelGenerator {
    private static final Pattern ERASED_CHARS = Pattern.compile("[\\[\\]']");
    private static final Pattern REPLACED_CHARS = Pattern.compile("[.-]");

    private final Map<String, Integer> usageCounters = new HashMap<>();

    String forTemplateKey(String key) {
      return "${" + forKey(key) + "}";
    }

    String forKey(String key) {
      int usages = usageCounters.merge(key, 1, Integer::sum);
      String sanitizedKey = ERASED_CHARS.matcher(key).replaceAll("");
      sanitizedKey = REPLACED_CHARS.matcher(sanitizedKey).replaceAll("_");
      return sanitizedKey + (usages == 1 ? "" : "_" + usages);
    }
  }

  private static <K, V> Map<V, K> invert(Map<K, V> map) {
    Map<V, K> inverted = new HashMap<>(map.size());
    for (Map.Entry<K, V> e : map.entrySet()) {
      inverted.put(e.getValue(), e.getKey());
    }
    return inverted;
  }

  private static List<DynamicPath> compile(Iterable<String> rawPaths) {
    List<DynamicPath> compiledPaths = new ArrayList<>();
    for (String rawPath : rawPaths) {
      compiledPaths.add(path(rawPath));
    }
    return compiledPaths;
  }

  private static List<DynamicPath> compileNonUnique(Iterable<String> rawPaths) {
    List<DynamicPath> compiledPaths = new ArrayList<>();
    for (String rawPath : rawPaths) {
      compiledPaths.add(path(rawPath, false));
    }
    return compiledPaths;
  }

  private static DynamicPath path(String rawPath) {
    return path(rawPath, true);
  }

  private static DynamicPath path(String rawPath, boolean unique) {
    return new DynamicPath(rawPath, JsonPath.compile(rawPath), unique);
  }

  public static final class DynamicPath {
    private final String rawPath;
    private final JsonPath path;
    // if true, same values are replaced with same placeholders;
    // otherwise every path gets its own placeholder, even if its value is non-unique
    private final boolean unique;

    DynamicPath(String rawPath, JsonPath path, boolean unique) {
      this.rawPath = rawPath;
      this.path = path;
      this.unique = unique;
    }
  }

  public static final class CoverageReport {
    public final Map<String, Object> event;
    public final String report;

    public CoverageReport(Map<String, Object> event, String report) {
      this.event = event;
      this.report = report;
    }
  }
}
