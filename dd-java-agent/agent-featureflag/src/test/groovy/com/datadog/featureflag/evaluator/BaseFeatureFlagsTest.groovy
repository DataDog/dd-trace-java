package com.datadog.featureflag.evaluator


import com.datadog.featureflag.ufc.v1.ServerConfiguration
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.trace.test.util.DDSpecification
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Paths
import okio.Okio
import spock.lang.Shared

abstract class BaseFeatureFlagsTest extends DDSpecification {

  @Shared
  protected static ServerConfiguration configuration

  void setupSpec() {
    configuration = parseConfiguration()
  }

  protected static ServerConfiguration parseConfiguration() {
    final flags = new JsonSlurper().parse(resourceFor('data/flags-v1.json'))
    final config = JsonOutput.toJson(flags.data.attributes)
    final deserializer = new Moshi.Builder().build().adapter(ServerConfiguration)
    return deserializer.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(config.bytes))))
  }

  protected static URL resourceFor(String url) {
    return Thread.currentThread().getContextClassLoader().getResource(url)
  }

  protected static List<?> testCases() {
    final folder = resourceFor('data/tests')
    final uri = folder.toURI()
    final testsPath = Paths.get(uri)
    final files = Files.list(testsPath)
    .filter(path -> path.toString().endsWith('.json'))
    final result = []
    final moshi = new Moshi.Builder().build().adapter(Types.newParameterizedType(List, TestCase))
    files.each {
      path ->
      final testCases = moshi.fromJson(Okio.buffer(Okio.source(path.toFile())))
      testCases.eachWithIndex {
        testCase, index ->
        result.add([name: path.fileName, index: index, testCase: testCase])
      }
    }
    return result
  }

  protected static class TestCase {
    String flag
    String variationType
    Object defaultValue
    String targetingKey
    Map<String, ?> attributes
    TestResult result


    @Override
    String toString() {
      return "{" +
      "flag:'" + flag + '\'' +
      ", variationType:'" + variationType + '\'' +
      ", targetingKey:'" + targetingKey + '\'' +
      ", defaultValue:" + defaultValue +
      '}'
    }
  }

  protected static class TestResult {
    Object value
    String variant
    Map<String, ?> flagMetadata
  }
}
