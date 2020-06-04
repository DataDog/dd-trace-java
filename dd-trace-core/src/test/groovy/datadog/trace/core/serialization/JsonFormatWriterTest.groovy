package datadog.trace.core.serialization

import com.squareup.moshi.JsonWriter
import datadog.trace.api.DDId
import okio.Buffer
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class JsonFormatWriterTest extends Specification {

  def "serialize ids"() {
    setup:
    def writer = JsonFormatWriter.JSON_WRITER
    def buffer = new Buffer()
    def dest = JsonWriter.of(buffer)


    when:
    dest.beginObject()
    writer.writeId("key".getBytes(StandardCharsets.UTF_8), id, dest)
    dest.endObject()

    then:
    buffer.snapshot().utf8() == expected

    where:
    id        | expected
    DDId.ZERO | """{"key":0}"""
    DDId.ONE  | """{"key":1}"""
    DDId.MAX  | """{"key":18446744073709551615}"""
  }
}
