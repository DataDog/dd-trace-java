package datadog.trace.core


import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer
import datadog.trace.core.serialization.msgpack.Packer
import datadog.trace.util.test.DDSpecification
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.buffer.ArrayBufferInput
import org.msgpack.value.ValueType

import java.nio.ByteBuffer

class DDSpanSerializationTest extends DDSpecification {

  def "serialize trace with id #value as int"() {
    setup:
    def tracer = CoreTracer.builder().writer(new ListWriter()).build()
    def context = new DDSpanContext(
      value,
      value,
      DDId.ZERO,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      spanType,
      Collections.emptyMap(),
      PendingTrace.create(tracer, DDId.ONE),
      tracer,
      [:])
    def span = DDSpan.create(0, context)
    def buffer = ByteBuffer.allocate(1024)
    CaptureBuffer capture = new CaptureBuffer()
    def packer = new Packer(capture, buffer)
    packer.format(Collections.singletonList(span), new TraceMapper())
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = unpacker.unpackArrayHeader()
    int spanCount = unpacker.unpackArrayHeader()
    int size = unpacker.unpackMapHeader()

    expect:
    traceCount == 1
    spanCount == 1
    size == 12
    for (int i = 0; i < size; i++) {
      String key = unpacker.unpackString()

      switch (key) {
        case "trace_id":
        case "span_id":
          MessageFormat next = unpacker.nextFormat
          assert next.valueType == ValueType.INTEGER
          if (next == MessageFormat.UINT64) {
            assert value == DDId.from("${unpacker.unpackBigInteger()}")
          } else {
            assert value == DDId.from(unpacker.unpackLong())
          }
          break
        default:
          unpacker.unpackValue()
      }
    }

    where:
    value                                                           | spanType
    DDId.ZERO                                                       | null
    DDId.ONE                                                        | "some-type"
    DDId.from("8223372036854775807")                                | null
    DDId.from("${BigInteger.valueOf(Long.MAX_VALUE).subtract(1G)}") | "some-type"
    DDId.from("${BigInteger.valueOf(Long.MAX_VALUE).add(1G)}")      | null
    DDId.from("${2G.pow(64).subtract(1G)}")                         | "some-type"
  }

  def "serialize trace with baggage and tags correctly"() {
    setup:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    def context = new DDSpanContext(
      DDId.ONE,
      DDId.ONE,
      DDId.ZERO,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      baggage,
      false,
      null,
      tags,
      PendingTrace.create(tracer, DDId.ONE),
      tracer,
      [:])
    def span = DDSpan.create(0, context)
    def buffer = ByteBuffer.allocate(1024)
    CaptureBuffer capture = new CaptureBuffer()
    def packer = new Packer(capture, buffer)
    packer.format(Collections.singletonList(span), new TraceMapper())
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = unpacker.unpackArrayHeader()
    int spanCount = unpacker.unpackArrayHeader()
    int size = unpacker.unpackMapHeader()

    expect:
    traceCount == 1
    spanCount == 1
    size == 12
    for (int i = 0; i < size; i++) {
      String key = unpacker.unpackString()

      switch (key) {
        case "meta":
          int packedSize = unpacker.unpackMapHeader()
          int expectedSize = expected.size()
          // filter out "thread.name" and "thread.id"
          assert packedSize - 2 == expectedSize
          Map<String, String> unpackedMeta = [:]
          for (int j = 0; j < packedSize; j++) {
            def k = unpacker.unpackString()
            def v = unpacker.unpackString()
            if (k != "thread.name" && k != "thread.id") {
              unpackedMeta.put(k, v)
            }
          }
          assert unpackedMeta == expected
          break
        default:
          unpacker.unpackValue()
      }
    }

    where:
    baggage       | tags           | expected
    [:]           | [:]            | [:]
    [foo: "bbar"] | [:]            | [foo: "bbar"]
    [foo: "bbar"] | [bar: "tfoo"]  | [foo: "bbar", bar: "tfoo"]
    [foo: "bbar"] | [foo: "tbar"]  | [foo: "tbar"]
  }

  private class CaptureBuffer implements ByteBufferConsumer {

    private byte[] bytes

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      this.bytes = new byte[buffer.limit() - buffer.position()]
      buffer.get(bytes)
    }
  }
}
