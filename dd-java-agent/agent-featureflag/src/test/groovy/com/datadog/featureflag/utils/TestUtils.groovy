package com.datadog.featureflag.utils

import com.datadog.featureflag.ufc.v1.ServerConfiguration
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Paths
import okio.Okio

class TestUtils {

  static String fetchConfiguration(String file) {
    final flags = new JsonSlurper().parse(resourceFor(file))
    return JsonOutput.toJson(flags.data.attributes)
  }

  static ServerConfiguration parseConfiguration(String file) {
    final config = fetchConfiguration(file)
    final deserializer = new Moshi.Builder().build().adapter(ServerConfiguration)
    return deserializer.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(config.bytes))))
  }

  static URL resourceFor(String url) {
    return Thread.currentThread().getContextClassLoader().getResource(url)
  }

  static List<EvaluationTest> testCases() {
    final folder = resourceFor('data/tests')
    final uri = folder.toURI()
    final testsPath = Paths.get(uri)
    final files = Files.list(testsPath)
    .filter(path -> path.toString().endsWith('.json'))
    final result = []
    final moshi = new Moshi.Builder().build().adapter(Types.newParameterizedType(List, EvaluationTest))
    files.each {
      path ->
      final testCases = moshi.fromJson(Okio.buffer(Okio.source(path.toFile()))) as List<EvaluationTest>
      testCases.eachWithIndex {
        testCase, index ->
        testCase.fileName = path.fileName.toString()
        testCase.index = index
      }
      result.addAll(testCases)
    }
    return result
  }

  static class EvaluationTest {
    String fileName
    int index
    String flag
    String variationType
    Object defaultValue
    String targetingKey
    Map<String, ?> attributes
    EvaluationResult result


    @Override
    String toString() {
      return "{" +
      "fileName:'" + fileName + '\'' +
      ", index:'" + index + '\'' +
      ", flag:'" + flag + '\'' +
      ", variationType:'" + variationType + '\'' +
      ", targetingKey:'" + targetingKey + '\'' +
      ", defaultValue:" + defaultValue +
      '}'
    }
  }

  static class EvaluationResult {
    Object value
    String variant
    Map<String, ?> flagMetadata
  }
}
