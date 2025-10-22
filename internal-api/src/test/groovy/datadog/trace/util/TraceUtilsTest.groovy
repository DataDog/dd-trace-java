package datadog.trace.util

import datadog.trace.test.util.DDSpecification

class TraceUtilsTest extends DDSpecification {

  def "test normalize service"() {
    when:
    String normalized = TraceUtils.normalizeServiceName(service)

    then:
    normalized == expected

    where:
    service                                                                                                                                | expected
    null                                                                                                                                   | TraceUtils.DEFAULT_SERVICE_NAME
    ""                                                                                                                                     | TraceUtils.DEFAULT_SERVICE_NAME
    "good"                                                                                                                                 | "good"
    "bad\$service"                                                                                                                         | "bad_service"
    "Too\$Long\$.Too\$Long\$.Too\$Long\$.Too\$Long\$.Too\$Long\$.Too\$Long\$.Too\$Long\$.Too\$Long\$.Too\$Long\$.Too\$Long\$.Too\$Long\$." | "too_long_.too_long_.too_long_.too_long_.too_long_.too_long_.too_long_.too_long_.too_long_.too_long_."
  }

  def "test normalize operation name"() {
    when:
    String normalized = TraceUtils.normalizeOperationName(name)

    then:
    normalized == expected

    where:
    name                                                                                                             | expected
    null                                                                                                             | TraceUtils.DEFAULT_OPERATION_NAME
    ""                                                                                                               | TraceUtils.DEFAULT_OPERATION_NAME
    "good"                                                                                                           | "good"
    "bad-name"                                                                                                       | "bad_name"
    "Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-.Too-Long-." | "Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long.Too_Long."
    "pylons.controller"                                                                                              | "pylons.controller"
    "trace-api.request"                                                                                              | "trace_api.request"
    "/"                                                                                                              | "unnamed_operation"
    "{çà]test"                                                                                                       | "test"
    "l___."                                                                                                          | "l."
    "a___b"                                                                                                          | "a_b"
    "a___"                                                                                                           | "a"
    "🐨🐶 繋"                                                                                                          | "unnamed_operation"
  }


  def "test normalize tag"() {
    when:
    String normalized = TraceUtils.normalizeTag(tag)

    then:
    normalized == expected

    where:
    tag                                                | expected
    null                                               | ""
    ""                                                 | ""
    "ok"                                               | "ok"
    " "                                                | ""
    "#test_starting_hash"                              | "test_starting_hash"
    "TestCAPSandSuch"                                  | "testcapsandsuch"
    "Test Conversion Of Weird !@#\$%^&**() Characters" | "test_conversion_of_weird_characters"
    "\$#weird_starting"                                | "weird_starting"
    "allowed:c0l0ns"                                   | "allowed:c0l0ns"
    "1love"                                            | "love"
    "ünicöde"                                          | "ünicöde"
    "ünicöde:metäl"                                    | "ünicöde:metäl"
    "Data🐨dog🐶 繋がっ⛰てて"                            | "data_dog_繋がっ_てて"
    " spaces   "                                       | "spaces"
    " #hashtag!@#spaces #__<>#  "                      | "hashtag_spaces"
    ":testing"                                         | ":testing"
    "_foo"                                             | "foo"
    ":::test"                                          | ":::test"
    "contiguous_____underscores"                       | "contiguous_underscores"
    "foo_"                                             | "foo"
    "\u017Fodd_\u017Fcase\u017F"                       | "\u017Fodd_\u017Fcase\u017F"
    "™Ö™Ö™™Ö™"                                         | "ö_ö_ö"
    "AlsO:ök"                                          | "also:ök"
    ":still_ok"                                        | ":still_ok"
    "___trim"                                          | "trim"
    "12.:trim@"                                        | ":trim"
    "12.:trim@@"                                       | ":trim"
    "fun:ky__tag/1"                                    | "fun:ky_tag/1"
    "fun:ky@tag/2"                                     | "fun:ky_tag/2"
    "fun:ky@@@tag/3"                                   | "fun:ky_tag/3"
    "tag:1/2.3"                                        | "tag:1/2.3"
    "---fun:k####y_ta@#g/1_@@#"                        | "fun:k_y_ta_g/1"
    "AlsO:œ#@ö))œk"                                    | "also:œ_ö_œk"
  }

  def "test normalize tag value"() {
    when:
    String normalized = TraceUtils.normalizeTagValue(tag)

    then:
    normalized == expected

    where:
    tag                                                | expected
    null                                               | ""
    ""                                                 | ""
    "ok"                                               | "ok"
    " "                                                | ""
    "TestCAPSandSuch"                                  | "testcapsandsuch"
    "Test Conversion Of Weird !@#\$%^&**() Characters" | "test_conversion_of_weird_characters"
    "1.55.0-SNAPSHOT"                                  | "1.55.0-snapshot"
    "a,b"                                              | "a_b"
  }


  def "test normalize span type"() {
    when:
    String normalized = TraceUtils.normalizeSpanType(spanType)

    then:
    normalized == expected

    where:
    spanType                                                                                                           | expected
    null                                                                                                               | null
    ""                                                                                                                 | ""
    "ok"                                                                                                               | "ok"
    "VeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLong" | "VeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVeryLongVery"
  }

  def "test normalize env"() {
    when:
    String normalized = TraceUtils.normalizeEnv(env)

    then:
    normalized == expected

    where:
    env              | expected
    null             | TraceUtils.DEFAULT_ENV
    ""               | TraceUtils.DEFAULT_ENV
    "ok"             | "ok"
    repeat("a", 300) | repeat("a", 200)
  }

  def "test is valid http status code"() {
    when:
    boolean isValid = TraceUtils.isValidStatusCode(httpStatusCode)

    then:
    isValid == expected

    where:
    httpStatusCode | expected
    100            | true
    404            | true
    600            | false
  }

  def repeat(String str, int length) {
    StringBuilder sb = new StringBuilder(length)
    for (int i = 0; i < length; i++) {
      sb.append(str)
    }
    return sb.toString()
  }
}
