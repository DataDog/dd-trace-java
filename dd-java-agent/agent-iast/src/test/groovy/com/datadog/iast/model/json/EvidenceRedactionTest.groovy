package com.datadog.iast.model.json

import com.datadog.iast.model.*
import com.squareup.moshi.*
import datadog.trace.api.config.IastConfig
import datadog.trace.api.iast.SourceTypes
import datadog.trace.test.util.DDSpecification
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import spock.lang.Shared

import javax.annotation.Nonnull
import java.lang.reflect.Modifier
import java.util.regex.Matcher
import java.util.regex.Pattern

class EvidenceRedactionTest extends DDSpecification {

  @Override
  void setup() {
    injectSysConfig(IastConfig.IAST_REDACTION_ENABLED, 'true')
  }

  @Shared
  private final JsonSlurper json = new JsonSlurper()

  @Shared
  private final YamlSlurper yaml = new YamlSlurper()

  @Shared
  private JsonAdapter<List<Source>> sourcesParser

  @Shared
  private JsonAdapter<List<Vulnerability>> vulnerabilitiesParser

  void setupSpec() {
    final moshi = new Moshi.Builder()
      .add(new TestVulnerabilityAdapter())
      .add(new TestSourceIndexAdapter())
      .add(new TestSourceTypeStringAdapter())
      .build()
    sourcesParser = moshi.adapter(Types.newParameterizedType(List, Source))
    vulnerabilitiesParser = moshi.adapter(Types.newParameterizedType(List, Vulnerability))
  }

  void 'test #suite'() {
    given:
    final type = suite.type == Type.SOURCES ? Types.newParameterizedType(List, Source) : VulnerabilityBatch
    final adapter = VulnerabilityEncoding.MOSHI.adapter(type)

    when:
    final redacted = adapter.toJson(suite.input)

    then:
    final received = JsonOutput.prettyPrint(redacted)
    final description = suite.description
    final expected = suite.expected
    JSONAssert.assertEquals(description, expected, received, JSONCompareMode.LENIENT)

    where:
    suite << readTestSuite('redaction/evidence-redaction-suite.yml')
  }

  private Iterable<TestSuite> readTestSuite(final String fileName) {
    final file = ClassLoader.getSystemResource(fileName)
    final tests = yaml.parse(file.openStream())
    final version = tests.version as Number
    final suite = tests.suite as List<Map>
    return suite.collectMany { item ->
      final result = []
      if (item['parameters']) {
        final parameterList = combine((item.parameters as Map<String, List<String>>).entrySet().toList())
        result.addAll(parameterList.collect { params ->
          newTestSuite([
            version    : version,
            type       : item.type,
            description: replaceAll(item.description as String, params),
            context    : replaceAll(item.context as String, params),
            input      : replaceAll(item.input as String, params),
            expected   : replaceAll(item.expected as String, params),
          ])
        })
      } else {
        result << newTestSuite(item + [version: version])
      }
      return result
    } as Iterable<TestSuite>
  }

  private TestSuite newTestSuite(Map parameters) {
    final suite = new TestSuite(
      version: parameters.version as Number,
      type: Enum.valueOf(Type, parameters.type as String),
      description: parameters.description,
      context: parameters.context
      )
    try {
      suite.expected = parameters.expected as String
      final String input = parameters.input as String
      switch (suite.type) {
        case Type.SOURCES:
          suite.input = sourcesParser.fromJson(input)
          break
        default:
          final batch = new VulnerabilityBatch(vulnerabilities: vulnerabilitiesParser.fromJson(input))
          if (suite.context != null) {
            final context = json.parseText(suite.context) as Map<String, String>
            batch.vulnerabilities.evidence.context.each { context.each(it.&put) }
          }
          suite.input = batch
          break
      }
      return suite
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse test $suite.description", e)
    }
  }

  private List<Map<String, String>> combine(final List<Map.Entry<String, List<String>>> parameters) {
    final result = []
    if (parameters.empty) {
      result.add([])
      return result
    }
    final entry = parameters.first()
    final combined = combine(parameters.subList(1, parameters.size()))
    entry.value.each { item ->
      combined.forEach { values ->
        final current = [(entry.key): item]
        current.putAll(values)
        result.add(current)
      }
    }
    return result
  }

  private String replaceAll(final String pattern, final Map<String, String> parameters) {
    if (pattern == null) {
      return null
    }
    return parameters.entrySet().inject(pattern) { result, entry ->
      return result.replaceAll(Pattern.quote(entry.key), Matcher.quoteReplacement(entry.value))
    }
  }

  private static enum Type {
    SOURCES,
    VULNERABILITIES
  }

  private static class TestSuite {
    Number version
    Type type
    String description
    String context
    Object input
    String expected

    @Override
    String toString() {
      return "IAST redaction test suite v${version}: ${description}"
    }
  }

  static class TestVulnerabilityAdapter {
    @FromJson
    VulnerabilityType fromJson(@Nonnull final JsonReader reader) throws IOException {
      final type = reader.nextString()
      return VulnerabilityType."$type"
    }

    @ToJson
    void toJson(@Nonnull final JsonWriter writer, @Nonnull final VulnerabilityType type) throws IOException {
      throw new UnsupportedOperationException()
    }
  }

  static class TestSourceIndexAdapter {
    @FromJson
    @SourceIndex
    Source fromJson(@Nonnull final JsonReader reader, final JsonAdapter<Source> adapter) throws IOException {
      return adapter.fromJson(reader)
    }

    @ToJson
    void toJson(@Nonnull final JsonWriter writer, @Nonnull @SourceIndex final Source type) throws IOException {
      throw new UnsupportedOperationException()
    }
  }

  static class TestSourceTypeStringAdapter {
    @FromJson
    @SourceTypeString
    byte fromJson(@Nonnull final JsonReader reader) throws IOException {
      final value = reader.nextString()
      final count = SourceTypes.getDeclaredFields().findAll {
        Modifier.isStatic(it.modifiers)
      }.size()
      for (byte i = 0; i < count; i++) {
        if (SourceTypes.toString(i) == value) {
          return i
        }
      }
      return SourceTypes.NONE
    }

    @ToJson
    void toJson(@Nonnull final JsonWriter writer, @Nonnull @SourceTypeString final byte type) throws IOException {
      throw new UnsupportedOperationException()
    }
  }
}
