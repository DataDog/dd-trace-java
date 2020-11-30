package datadog.trace.bootstrap.instrumentation.api


import datadog.trace.test.util.DDSpecification

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class Utf8ByteStringTest extends DDSpecification {
  def "wrap String and produce the right bytes and String"() {
    when:
    final utf8String = constant
      ? UTF8BytesString.createConstant((String) str)
      : UTF8BytesString.create((String) str)

    then:
    if (utf8String != null) {
      utf8String.toString() == str
      utf8String.hashCode() == str.hashCode()
      def bytes = str.getBytes(StandardCharsets.UTF_8)
      def utf8Bytes = ByteBuffer.allocate(bytes.length)
      utf8String.transferTo(utf8Bytes)
      utf8Bytes.array() == bytes
      // check that we get back the same byte array
      !utf8String.equals(null)
      utf8String != str
      utf8String != UTF8BytesString.create("somethingcompletelydifferent")
    }

    where:
    str                                                           | constant
    null                                                          | false
    "foo"                                                         | false
    "bar"                                                         | false
    "alongerstring"                                               | false
    null                                                          | true
    "foo"                                                         | true
    "bar"                                                         | true
    "alongerstring"                                               | true
  }

  def "wrap bytes and produce the right String and bytes"() {
    when:
    final utf8String = UTF8BytesString.create(bytes)

    then:
    if (utf8String != null) {
      utf8String.toString() == str
      utf8String.hashCode() == str.hashCode()
      def utf8Bytes = ByteBuffer.allocate(bytes.length)
      utf8String.transferTo(utf8Bytes)
      utf8Bytes.array() == bytes
      !utf8String.equals(null)
      utf8String != str
      utf8String != UTF8BytesString.create("somethingcompletelydifferent")
    }

    where:
    str             | bytes
    null            | null
    "foo"           | [0x66, 0x6f, 0x6f] as byte[]
    "bar"           | [0x62, 0x61, 0x72] as byte[]
    "alongerstring" | [0x61, 0x6c, 0x6f, 0x6e, 0x67, 0x65, 0x72, 0x73, 0x74, 0x72, 0x69, 0x6e, 0x67] as byte[]
  }

  def "behave like a proper CharSequence"() {
    when:
    final utf8String = constant ? UTF8BytesString.createConstant((CharSequence) chars)
      : UTF8BytesString.create((CharSequence) chars)

    then:
    if (utf8String != null) {
      utf8String.toString() == chars.toString()
      utf8String.length() == chars.length()
      for (int i = 0; i < chars.length(); i++) {
        utf8String.charAt(i) == chars.charAt(i)
      }
      utf8String.subSequence(1, chars.length()).toString() == chars.subSequence(1, chars.length()).toString()
    }

    where:
    chars                                                         | constant
    null                                                          | true
    "foo"                                                         | true
    new StringBuffer("bar")                                       | true
    new StringBuffer("someotherlongstring")                       | true
    UTF8BytesString.create("utf8string")                          | true
    null                                                          | false
    "foo"                                                         | false
    new StringBuffer("bar")                                       | false
    new StringBuffer("someotherlongstring")                       | false
    UTF8BytesString.create("utf8string")                          | false
  }
}
