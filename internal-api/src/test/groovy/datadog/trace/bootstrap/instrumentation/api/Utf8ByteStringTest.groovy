package datadog.trace.bootstrap.instrumentation.api


import datadog.trace.util.test.DDSpecification

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
      def utf8Bytes = utf8String.getUtf8Bytes()
      utf8Bytes == bytes
      // check that we get back the same byte array
      utf8String.getUtf8Bytes().is(utf8Bytes)
      utf8String.equals(utf8String)
      utf8String == UTF8BytesString.create(str)
      !utf8String.equals(null)
      utf8String != str
      utf8String != UTF8BytesString.create("somethingcompletelydifferent")
    }

    where:
    str << [null, "foo", "bar", "alongerstring"]
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
    chars << [null, "foo", new StringBuffer("bar"), new StringBuffer("someotherlongstring"), UTF8BytesString.create("utf8string")]
  }
}
