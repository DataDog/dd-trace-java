package datadog.trace.bootstrap.instrumentation.api


import datadog.trace.test.util.DDSpecification

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class Utf8ByteStringTest extends DDSpecification {
  def "wrap String and produce the right bytes and String"() {
    when:
    final utf8String = UTF8BytesString.create((String) str)

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
    str                                                           | _
    null                                                          | _
    "foo"                                                         | _
    "bar"                                                         | _
    "alongerstring"                                               | _
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
    "alongerstring" | [
      0x61,
      0x6c,
      0x6f,
      0x6e,
      0x67,
      0x65,
      0x72,
      0x73,
      0x74,
      0x72,
      0x69,
      0x6e,
      0x67
    ] as byte[]
  }

  def "behave like a proper CharSequence"() {
    when:
    final utf8String = UTF8BytesString.create((CharSequence) chars)

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
    chars                                                         | _
    null                                                          | _
    "foo"                                                         | _
    new StringBuffer("bar")                                       | _
    new StringBuffer("someotherlongstring")                       | _
    UTF8BytesString.create("utf8string")                          | _
  }

  def "wrap byte buffer and produce the right String and bytes"() {
    when:
    final utf8String = UTF8BytesString.create(bytes != null ? ByteBuffer.wrap(bytes) : (ByteBuffer)null)

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
    "alongerstring" | [
      0x61,
      0x6c,
      0x6f,
      0x6e,
      0x67,
      0x65,
      0x72,
      0x73,
      0x74,
      0x72,
      0x69,
      0x6e,
      0x67
    ] as byte[]
  }
}
