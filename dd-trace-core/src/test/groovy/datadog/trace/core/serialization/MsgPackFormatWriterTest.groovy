package datadog.trace.core.serialization

import datadog.trace.util.test.DDSpecification
import org.msgpack.core.MessagePack
import org.msgpack.core.buffer.ArrayBufferOutput

import java.nio.charset.StandardCharsets

class MsgPackFormatWriterTest extends DDSpecification {

  def "serialize strings"() {
    setup:
    def writer = MsgpackFormatWriter.MSGPACK_WRITER
    def buffer = new ArrayBufferOutput()
    def packer = MessagePack.newDefaultPacker(buffer)


    when:
    writer.writeString(key, value, packer)
    packer.close()

    then:
    def unpacker = MessagePack.newDefaultUnpacker(buffer.toByteArray())
    unpacker.unpackString() == new String(key, StandardCharsets.UTF_8)
    unpacker.tryUnpackNil() || unpacker.unpackString() == value

    where:
    key                                    | value
    "key".getBytes(StandardCharsets.UTF_8) | null
    "key".getBytes(StandardCharsets.UTF_8) | "foo"
    "key".getBytes(StandardCharsets.UTF_8) | ""
  }
}
