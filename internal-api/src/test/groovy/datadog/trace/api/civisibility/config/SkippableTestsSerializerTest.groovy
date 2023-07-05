package datadog.trace.api.civisibility.config


import spock.lang.Specification

import java.util.stream.Collectors

class SkippableTestsSerializerTest extends Specification {

  def "test serialization: #tests"() {
    given:
    def testsList = tests.stream().map { t -> new SkippableTest(t[0], t[1], t[2], null) }.collect(Collectors.toList())

    when:
    def serializedTests = SkippableTestsSerializer.serialize(testsList)
    def deserializedTests = SkippableTestsSerializer.deserialize(serializedTests)

    then:
    deserializedTests == testsList

    where:
    tests << [
      // single test
      [["suite", "name", null]],
      [["suite", "name", "parameters"]],
      [["suite", "name", "parameters${SkippableTestsSerializer.ESCAPE_CHARACTER}"]],
      [["suite", "name", "{\"metadata\":{\"test_name\":\"test display name with #a #b #c\"}}"]],
      [["suite", "name${SkippableTestsSerializer.FIELD_DELIMITER}second part of the name", null]],
      [["suite", "name${SkippableTestsSerializer.RECORD_DELIMITER}second part of the name", null]],
      [["suite", "name${SkippableTestsSerializer.ESCAPE_CHARACTER}second part of the name", null]],
      [
        [
          "suite",
          "name${SkippableTestsSerializer.ESCAPE_CHARACTER}${SkippableTestsSerializer.FIELD_DELIMITER}second part of the name",
          null
        ]
      ],
      [
        [
          "suite",
          "name${SkippableTestsSerializer.ESCAPE_CHARACTER}${SkippableTestsSerializer.RECORD_DELIMITER}second part of the name",
          null
        ]
      ],
      [["suite", "name${SkippableTestsSerializer.FIELD_DELIMITER}second part of the name", "parameters"]],
      [["suite", "name${SkippableTestsSerializer.RECORD_DELIMITER}second part of the name", "parameters"]],
      [["suite", "name${SkippableTestsSerializer.ESCAPE_CHARACTER}second part of the name", "parameters"]],
      [
        [
          "suite",
          "name${SkippableTestsSerializer.ESCAPE_CHARACTER}${SkippableTestsSerializer.FIELD_DELIMITER}second part of the name",
          "parameters"
        ]
      ],
      [
        [
          "suite",
          "name${SkippableTestsSerializer.ESCAPE_CHARACTER}${SkippableTestsSerializer.RECORD_DELIMITER}second part of the name",
          "parameters"
        ]
      ],
      [
        [
          "suite",
          "name${SkippableTestsSerializer.ESCAPE_CHARACTER}${SkippableTestsSerializer.ESCAPE_CHARACTER}${SkippableTestsSerializer.RECORD_DELIMITER}second part of the name",
          "parameters"
        ]
      ],
      [
        [
          "suite${SkippableTestsSerializer.ESCAPE_CHARACTER}",
          "name${SkippableTestsSerializer.FIELD_DELIMITER}",
          "parameters"
        ]
      ],
      [
        [
          "suite${SkippableTestsSerializer.ESCAPE_CHARACTER}${SkippableTestsSerializer.ESCAPE_CHARACTER}",
          "name${SkippableTestsSerializer.FIELD_DELIMITER}",
          "parameters"
        ]
      ],
      // multiple tests
      [["suite", "name", "parameters"], ["a", "b", "c"]],
      [["suite", "name", null], ["a", "b", "c"]],
      [["suite", "name", null], ["a", "b", null]],
      [["suite", "name", "parameters${SkippableTestsSerializer.ESCAPE_CHARACTER}"], ["a", "b", "c"]],
      [
        [
          "suite",
          "name",
          "parameters${SkippableTestsSerializer.ESCAPE_CHARACTER}${SkippableTestsSerializer.ESCAPE_CHARACTER}"
        ],
        ["a", "b", "c"]
      ],
      [["suite", "name", "parameters${SkippableTestsSerializer.FIELD_DELIMITER}"], ["a", "b", "c"]],
      [["suite", "name", "parameters${SkippableTestsSerializer.RECORD_DELIMITER}"], ["a", "b", "c"]],
      [["suite", "name", null], ["${SkippableTestsSerializer.ESCAPE_CHARACTER}a", "b", "c"]],
      [["suite", "name", null], ["${SkippableTestsSerializer.FIELD_DELIMITER}a", "b", "c"]],
      [["suite", "name", null], ["${SkippableTestsSerializer.RECORD_DELIMITER}a", "b", "c"]],
      [
        ["suite", "name", null],
        [
          "${SkippableTestsSerializer.ESCAPE_CHARACTER}${SkippableTestsSerializer.RECORD_DELIMITER}a",
          "b",
          "c"
        ]
      ],
      [["suite", "name", "parameters"], ["a", "b", null]],
    ]
  }
}
