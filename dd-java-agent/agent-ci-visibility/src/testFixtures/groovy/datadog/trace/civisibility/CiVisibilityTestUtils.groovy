package datadog.trace.civisibility

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.ReadContext
import com.jayway.jsonpath.WriteContext
import freemarker.template.Template
import freemarker.template.TemplateExceptionHandler
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

import java.nio.file.Files
import java.nio.file.Paths
import java.util.regex.Pattern

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
    path("content.meta.['error.stack']"),
    path("content.meta.library_version"),
    path("content.meta.runtime-id"),
    // Different events might or might not have the same start or duration.
    // Regardless, the values of these fields should be treated as different
    path("content.start", false),
    path("content.duration", false),
    path("content.meta.['_dd.p.tid']", false),
  ]

  static final List<DynamicPath> COVERAGE_DYNAMIC_PATHS = [path("test_session_id"), path("test_suite_id"), path("span_id"),]

  private static final Comparator<Map<?,?>> EVENT_RESOURCE_COMPARATOR = Comparator.comparing((Map m) -> {
    def content = (Map) m.get("content")
    return content.get("resource")
  })

  /**
   * Use this method to generate expected data templates
   */
  static void generateTemplates(String baseTemplatesPath, List<Map<?, ?>> events, List<Map<?, ?>> coverages, Map<String, String> additionalReplacements) {
    events.sort(EVENT_RESOURCE_COMPARATOR)

    def templateGenerator = new TemplateGenerator(new LabelGenerator())
    def compiledAdditionalReplacements = compile(additionalReplacements.keySet())

    Files.createDirectories(Paths.get(baseTemplatesPath))
    Files.write(Paths.get(baseTemplatesPath, "events.ftl"), templateGenerator.generateTemplate(events, EVENT_DYNAMIC_PATHS + compiledAdditionalReplacements).bytes)
    Files.write(Paths.get(baseTemplatesPath, "coverages.ftl"), templateGenerator.generateTemplate(coverages, COVERAGE_DYNAMIC_PATHS + compiledAdditionalReplacements).bytes)
  }

  static void assertData(String baseTemplatesPath, List<Map<?, ?>> events, List<Map<?, ?>> coverages, Map<String, String> additionalReplacements) {
    events.sort(EVENT_RESOURCE_COMPARATOR)

    def labelGenerator = new LabelGenerator()
    def templateGenerator = new TemplateGenerator(labelGenerator)

    def replacementMap
    replacementMap = templateGenerator.generateReplacementMap(events, EVENT_DYNAMIC_PATHS)
    replacementMap = templateGenerator.generateReplacementMap(coverages, COVERAGE_DYNAMIC_PATHS)

    for (Map.Entry<String, String> e : additionalReplacements.entrySet()) {
      replacementMap.put(labelGenerator.forKey(e.key), "\"$e.value\"")
    }

    def expectedEvents = getFreemarkerTemplate(baseTemplatesPath + "/events.ftl", replacementMap)
    def actualEvents = JSON_MAPPER.writeValueAsString(events)
    try {
      JSONAssert.assertEquals(expectedEvents, actualEvents, JSONCompareMode.LENIENT)
    } catch (AssertionError e) {
      throw new org.opentest4j.AssertionFailedError("Events mismatch", expectedEvents, actualEvents, e)
    }

    def expectedCoverages = getFreemarkerTemplate(baseTemplatesPath + "/coverages.ftl", replacementMap)
    def actualCoverages = JSON_MAPPER.writeValueAsString(coverages)
    try {
      JSONAssert.assertEquals(expectedCoverages, actualCoverages, JSONCompareMode.LENIENT)
    } catch (AssertionError e) {
      throw new org.opentest4j.AssertionFailedError("Coverages mismatch", expectedCoverages, actualCoverages, e)
    }
  }

  static final Configuration JSON_PATH_CONFIG = Configuration.builder()
  .options(Option.SUPPRESS_EXCEPTIONS)
  .build()

  static final ObjectMapper JSON_MAPPER = new ObjectMapper() { {
      enable(SerializationFeature.INDENT_OUTPUT)
    }
  }

  static final freemarker.template.Configuration FREEMARKER = new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_30) { {
      setClassLoaderForTemplateLoading(CiVisibilityTestUtils.classLoader, "")
      setDefaultEncoding("UTF-8")
      setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER)
      setLogTemplateExceptions(true)
      setWrapUncheckedExceptions(true)
      setFallbackOnNullLoopVariable(false)
      setNumberFormat("0.######")
    }
  }

  private static String getFreemarkerTemplate(String templatePath, Map<String, Object> replacements) {
    Template coveragesTemplate = FREEMARKER.getTemplate(templatePath)
    StringWriter coveragesOut = new StringWriter()
    coveragesTemplate.process(replacements, coveragesOut)
    return coveragesOut.toString()
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
            } else {
              return label.forTemplateKey(dynamicPath.rawPath)
            }
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
}
