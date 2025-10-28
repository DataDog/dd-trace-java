package com.datadog.appsec.util

import com.datadog.appsec.ddwaf.WAFModule
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class BodyParserSpecification extends Specification {

  void 'test body parser per media type'() {
    when:
    final result = BodyParser.forMediaType(media)

    then:
    (result != null) == nonNull

    where:
    media              | nonNull
    'application/json' | true
    'application/xml'  | false
  }

  void 'test parse simple JSON object'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def json = '{"name":"John","age":30}'
    def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof Map
    result.name == 'John'
    result.age == 30.0
    !state.objectTooDeep
    !state.listMapTooLarge
    !state.stringTooLong
  }

  void 'test parse simple JSON array'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def json = '[1,2,3,"test"]'
    def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof List
    result.size() == 4
    result[0] == 1.0
    result[1] == 2.0
    result[2] == 3.0
    result[3] == 'test'
    !state.objectTooDeep
    !state.listMapTooLarge
    !state.stringTooLong
  }

  void 'test parse JSON with various data types'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def json = '{"string":"hello","number":42,"boolean":true,"null":null,"array":[1,2],"object":{"nested":"value"}}'
    def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof Map
    result.string == 'hello'
    result.number == 42.0
    result.boolean == true
    result.null == null
    result.array instanceof List
    result.array.size() == 2
    result.object instanceof Map
    result.object.nested == 'value'
  }

  void 'test parse nested JSON object'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def json = '{"level1":{"level2":{"level3":"deep"}}}'
    def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof Map
    result.level1 instanceof Map
    result.level1.level2 instanceof Map
    result.level1.level2.level3 == 'deep'
    !state.objectTooDeep
  }

  void 'test parse empty JSON object'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def json = '{}'
    def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof Map
    result.isEmpty()
  }

  void 'test parse empty JSON array'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def json = '[]'
    def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof List
    result.isEmpty()
  }

  void 'test parse JSON with special characters'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def json = '{"unicode":"\\u0048\\u0065\\u006c\\u006c\\u006f","escaped":"line1\\nline2\\ttab"}'
    def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof Map
    result.unicode == 'Hello'
    result.escaped == 'line1\nline2\ttab'
  }

  void 'test IOException wrapped in RuntimeException'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def inputStream = new InputStream() {
        @Override
        int read() throws IOException {
          throw new IOException("Simulated IO error")
        }
      }

    when:
    parser.parse(state, inputStream)

    then:
    RuntimeException ex = thrown()
    ex.cause instanceof IOException
    ex.cause.message == "Simulated IO error"
  }

  void 'test depth limit exceeded - objectTooDeep flag set'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def deepJson = generateDeepNestedJson(WAFModule.MAX_DEPTH + 5)
    def inputStream = new ByteArrayInputStream(deepJson.getBytes(StandardCharsets.UTF_8))

    when:
    parser.parse(state, inputStream)

    then:
    state.objectTooDeep
  }

  void 'test string length limit exceeded - stringTooLong flag set'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def longString = "a" * (WAFModule.MAX_STRING_SIZE + 10)
    def json = "{\"longString\":\"${longString}\"}"
    def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof Map
    result.longString.length() <= WAFModule.MAX_STRING_SIZE
    state.stringTooLong
  }

  void 'test elements limit exceeded in object - listMapTooLarge flag set'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def largeObjectJson = generateLargeObjectJson(WAFModule.MAX_ELEMENTS + 10)
    def inputStream = new ByteArrayInputStream(largeObjectJson.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof Map
    result.size() <= WAFModule.MAX_ELEMENTS
    state.listMapTooLarge
  }

  void 'test elements limit exceeded in array - listMapTooLarge flag set'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def largeArrayJson = generateLargeArrayJson(WAFModule.MAX_ELEMENTS + 10)
    def inputStream = new ByteArrayInputStream(largeArrayJson.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof List
    result.size() <= WAFModule.MAX_ELEMENTS
    state.listMapTooLarge
  }

  void 'test mixed nested structure with limits'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def complexJson = '{"array":[1,2,3],"object":{"nested":{"deep":"value"}},"string":"normal"}'
    def inputStream = new ByteArrayInputStream(complexJson.getBytes(StandardCharsets.UTF_8))

    when:
    def result = parser.parse(state, inputStream)

    then:
    result instanceof Map
    result.array instanceof List
    result.object instanceof Map
    result.string == 'normal'
    !state.objectTooDeep
    !state.listMapTooLarge
    !state.stringTooLong
  }

  void 'test invalid JSON throws JsonDataException'() {
    given:
    def parser = BodyParser.forJson()
    def state = new BodyParser.State()
    def invalidJson = '{"invalid":}'
    def inputStream = new ByteArrayInputStream(invalidJson.getBytes(StandardCharsets.UTF_8))

    when:
    parser.parse(state, inputStream)

    then:
    RuntimeException ex = thrown()
    ex.cause instanceof Exception
  }

  private static String generateDeepNestedJson(int depth) {
    def sb = new StringBuilder()
    for (int i = 0; i < depth; i++) {
      sb.append('{"level').append(i).append('":')
    }
    sb.append('"deep"')
    for (int i = 0; i < depth; i++) {
      sb.append('}')
    }
    return sb.toString()
  }

  private static String generateLargeObjectJson(int size) {
    def sb = new StringBuilder()
    sb.append('{')
    for (int i = 0; i < size; i++) {
      if (i > 0) {
        sb.append(',')
      }
      sb.append('"key').append(i).append('":"value').append(i).append('"')
    }
    sb.append('}')
    return sb.toString()
  }

  private static String generateLargeArrayJson(int size) {
    def sb = new StringBuilder()
    sb.append('[')
    for (int i = 0; i < size; i++) {
      if (i > 0) {
        sb.append(',')
      }
      sb.append(i)
    }
    sb.append(']')
    return sb.toString()
  }
}
