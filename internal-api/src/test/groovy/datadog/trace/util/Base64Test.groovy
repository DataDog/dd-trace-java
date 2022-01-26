package datadog.trace.util

import spock.lang.Specification

import java.nio.charset.StandardCharsets

class Base64Test extends Specification {

  def "tests base64 encode/decode"() {
    when:
    def decoder = new Base64Decoder()
    def charset = StandardCharsets.UTF_8
    def encoderNoPadding = new Base64Encoder(false)
    def encoderWithPadding = new Base64Encoder(true)

    then:
    def originalBytes = originalString.getBytes(charset)
    def encoder = padding ? encoderWithPadding : encoderNoPadding
    def encodedBytes = encoder.encode(originalBytes)
    new String(encodedBytes, charset) == encodedString
    new String(decoder.decode(encodedBytes), charset) == originalString

    where:
    originalString      | encodedString             | padding
    ""                  | ""                        | true
    ""                  | ""                        | false
    "a"                 | "YQ=="                    | true
    "a"                 | "YQ"                      | false
    "ab"                | "YWI="                    | true
    "ab"                | "YWI"                     | false
    "abc1Z"             | "YWJjMVo="                | true
    "abcd"              | "YWJjZA=="                | true
    "abcde"             | "YWJjZGU="                | true
    "abcde"             | "YWJjZGU"                 | false
    "abcdef"            | "YWJjZGVm"                | true
    "abcdef"            | "YWJjZGVm"                | false
    "abcdefg"           | "YWJjZGVmZw=="            | true
    "abcdefg"           | "YWJjZGVmZw"              | false
    "some_service"      | "c29tZV9zZXJ2aWNl"        | true
    "some_service"      | "c29tZV9zZXJ2aWNl"        | false
    "Another-Service"   | "QW5vdGhlci1TZXJ2aWNl"    | true
    "Another-Service"   | "QW5vdGhlci1TZXJ2aWNl"    | false
    "service-b"         | "c2VydmljZS1i"            | true
    "service-b"         | "c2VydmljZS1i"            | false
    "öôò"               | "w7bDtMOy"                | true
    "öôò"               | "w7bDtMOy"                | false
    "abc4A"             | "YWJjNEE"                 | false
    "abcd000"           | "YWJjZDAwMA"              | false
    "abcd000"           | "YWJjZDAwMA=="            | true
    "mcnulty-web"       | "bWNudWx0eS13ZWI"         | false
    "trace-stats-query" | "dHJhY2Utc3RhdHMtcXVlcnk" | false
  }
}
