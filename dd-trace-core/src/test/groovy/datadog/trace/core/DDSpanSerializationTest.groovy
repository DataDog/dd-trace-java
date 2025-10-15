package datadog.trace.core

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED

import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.ProcessTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.core.test.DDCoreSpecification
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.buffer.ArrayBufferInput
import org.msgpack.value.ValueType

import java.nio.ByteBuffer

class DDSpanSerializationTest extends DDCoreSpecification {

  def setupSpec() {
    //disable process tags since will generate noise on the meta
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()
  }

  def cleanupSpec() {
    //disable process tags since will generate noise on the meta
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
    ProcessTags.reset()
  }

  def "serialize trace with id #value as int"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def traceId = DDTraceId.from(value)
    def spanId = DDSpanId.from(value)
    def context = createContext(spanType, tracer, traceId, spanId)
    def span = DDSpan.create("test", 0, context, null)
    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    packer.format(Collections.singletonList(span), new TraceMapperV0_4())
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount
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
          MessageFormat next = unpacker.nextFormat
          assert next.valueType == ValueType.INTEGER
          if (next == MessageFormat.UINT64) {
            assert traceId == DDTraceId.from("${unpacker.unpackBigInteger()}")
          } else {
            assert traceId == DDTraceId.from(unpacker.unpackLong())
          }
          break
        case "span_id":
          MessageFormat next = unpacker.nextFormat
          assert next.valueType == ValueType.INTEGER
          if (next == MessageFormat.UINT64) {
            assert spanId == DDSpanId.from("${unpacker.unpackBigInteger()}")
          } else {
            assert spanId == unpacker.unpackLong()
          }
          break
        default:
          unpacker.unpackValue()
      }
    }

    cleanup:
    tracer.close()

    where:
    value                                                | spanType
    "0"                                                  | null
    "1"                                                  | "some-type"
    "8223372036854775807"                                | null
    "${BigInteger.valueOf(Long.MAX_VALUE).subtract(1G)}" | "some-type"
    "${BigInteger.valueOf(Long.MAX_VALUE).add(1G)}"      | null
    "${2G.pow(64).subtract(1G)}"                         | "some-type"
  }

  def "serialize trace with id #value as int v0.5"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def traceId = DDTraceId.from(value)
    def spanId = DDSpanId.from(value)
    def context = createContext(spanType, tracer, traceId, spanId)
    def span = DDSpan.create("test", 0, context, null)
    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    def traceMapper = new TraceMapperV0_5()
    packer.format(Collections.singletonList(span), traceMapper)
    packer.flush()
    def dictionaryUnpacker = MessagePack.newDefaultUnpacker(traceMapper.dictionary.slice())
    String[] dictionary = new String[traceMapper.encoding.size()]
    for (int i = 0; i < dictionary.length; ++i) {
      dictionary[i] = dictionaryUnpacker.unpackString()
    }
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount

    int spanCount = unpacker.unpackArrayHeader()
    int size = unpacker.unpackArrayHeader()

    expect:
    traceCount == 1
    spanCount == 1
    size == 12
    for (int i = 0; i < size; i++) {
      switch (i) {
        case 3:
          MessageFormat next = unpacker.nextFormat
          assert next.valueType == ValueType.INTEGER
          if (next == MessageFormat.UINT64) {
            assert traceId == DDTraceId.from("${unpacker.unpackBigInteger()}")
          } else {
            assert traceId == DDTraceId.from(unpacker.unpackLong())
          }
          break
        case 4:
          MessageFormat next = unpacker.nextFormat
          assert next.valueType == ValueType.INTEGER
          if (next == MessageFormat.UINT64) {
            assert spanId == DDSpanId.from("${unpacker.unpackBigInteger()}")
          } else {
            assert spanId == unpacker.unpackLong()
          }
          break
        default:
          unpacker.unpackValue()
      }
    }

    cleanup:
    tracer.close()

    where:
    value                                                    | spanType
    "0"                                                      | null
    "1"                                                      | "some-type"
    "8223372036854775807"                                    | null
    "${BigInteger.valueOf(Long.MAX_VALUE).subtract(1G)}"     | "some-type"
    "${BigInteger.valueOf(Long.MAX_VALUE).add(1G)}"          | null
    "${2G.pow(64).subtract(1G)}"                             | "some-type"
  }

  def "serialize trace with baggage and tags correctly v0.4"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def context = new DDSpanContext(
      DDTraceId.ONE,
      1,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      baggage,
      false,
      null,
      tags.size(),
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null,
      injectBaggage)
    context.setAllTags(tags)
    def span = DDSpan.create("test", 0, context, null)
    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    packer.format(Collections.singletonList(span), new TraceMapperV0_4())
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount
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

    cleanup:
    tracer.close()

    where:
    baggage       | tags          | expected                    | injectBaggage
    [:]           | [:]           | [:]                         | true
    [foo: "bbar"] | [:]           | [foo: "bbar"]               | true
    [foo: "bbar"] | [bar: "tfoo"] | [foo: "bbar", bar: "tfoo"]  | true
    [foo: "bbar"] | [foo: "tbar"] | [foo: "tbar"]               | true
    [:]           | [:]           | [:]                         | false
    [foo: "bbar"] | [:]           | [:]                         | false
    [foo: "bbar"] | [bar: "tfoo"] | [bar: "tfoo"]               | false
    [foo: "bbar"] | [foo: "tbar"] | [foo: "tbar"]               | false
  }

  def "serialize trace with baggage and tags correctly v0.5"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def context = new DDSpanContext(
      DDTraceId.ONE,
      1,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      baggage,
      false,
      null,
      tags.size(),
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null,
      injectBaggage)
    context.setAllTags(tags)
    def span = DDSpan.create("test", 0, context, null)
    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    def mapper = new TraceMapperV0_5()
    packer.format(Collections.singletonList(span), mapper)
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount
    int spanCount = unpacker.unpackArrayHeader()
    int size = unpacker.unpackArrayHeader()
    def dictionaryUnpacker = MessagePack.newDefaultUnpacker(mapper.dictionary.slice())
    String[] dictionary = new String[mapper.encoding.size()]
    for (int i = 0; i < dictionary.length; ++i) {
      dictionary[i] = dictionaryUnpacker.unpackString()
    }

    expect:
    traceCount == 1
    spanCount == 1
    size == 12
    for (int i = 0; i < 9; ++i) {
      unpacker.skipValue()
    }

    int packedSize = unpacker.unpackMapHeader()
    Map<String, String> unpackedMeta = [:]
    for (int j = 0; j < packedSize; j++) {
      def k = dictionary[unpacker.unpackInt()]
      def v = dictionary[unpacker.unpackInt()]
      if (k != "thread.name" && k != "thread.id") {
        unpackedMeta.put(k, v)
      }
    }
    assert unpackedMeta == expected

    cleanup:
    tracer.close()

    where:
    baggage       | tags          | expected                    | injectBaggage
    [:]           | [:]           | [:]                         | true
    [foo: "bbar"] | [:]           | [foo: "bbar"]               | true
    [foo: "bbar"] | [bar: "tfoo"] | [foo: "bbar", bar: "tfoo"]  | true
    [foo: "bbar"] | [foo: "tbar"] | [foo: "tbar"]               | true
    [:]           | [:]           | [:]                         | false
    [foo: "bbar"] | [:]           | [:]                         | false
    [foo: "bbar"] | [bar: "tfoo"] | [bar: "tfoo"]               | false
    [foo: "bbar"] | [foo: "tbar"] | [foo: "tbar"]               | false
  }

  def "serialize trace with flat map tag v0.4"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def context = new DDSpanContext(
      DDTraceId.ONE,
      1,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      null,
      false,
      null,
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null)
    context.setTag('key1', 'value1')
    context.setTag('key2', [
      'sub1': 'v1',
      'sub2': 'v2'
    ])
    def span = DDSpan.create("test", 0, context, null)

    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    packer.format(Collections.singletonList(span), new TraceMapperV0_4())
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount
    int spanCount = unpacker.unpackArrayHeader()
    int size = unpacker.unpackMapHeader()

    def expectedMeta = ['key1': 'value1', 'key2.sub1': 'v1', 'key2.sub2': 'v2']

    expect:
    traceCount == 1
    spanCount == 1

    for (int i = 0; i < size; i++) {
      String key = unpacker.unpackString()

      switch (key) {
        case "meta":
          int packedSize = unpacker.unpackMapHeader()
          Map<String, String> unpackedMeta = [:]
          for (int j = 0; j < packedSize; j++) {
            def k = unpacker.unpackString()
            def v = unpacker.unpackString()
            if (k != "thread.name" && k != "thread.id") {
              unpackedMeta.put(k, v)
            }
          }
          assert unpackedMeta == expectedMeta
          break
        default:
          unpacker.unpackValue()
      }
    }

    cleanup:
    tracer.close()
  }

  def "serialize trace with flat map tag v0.5"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def context = new DDSpanContext(
      DDTraceId.ONE,
      1,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      null,
      false,
      null,
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null)
    context.setTag('key1', 'value1')
    context.setTag('key2', [
      'sub1': 'v1',
      'sub2': 'v2'
    ])
    def span = DDSpan.create("test", 0, context, null)

    CaptureBuffer capture = new CaptureBuffer()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, capture))
    def mapper = new TraceMapperV0_5()
    packer.format(Collections.singletonList(span), mapper)
    packer.flush()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(capture.bytes))
    int traceCount = capture.messageCount
    int spanCount = unpacker.unpackArrayHeader()
    int size = unpacker.unpackArrayHeader()
    def dictionaryUnpacker = MessagePack.newDefaultUnpacker(mapper.dictionary.slice())
    String[] dictionary = new String[mapper.encoding.size()]
    for (int i = 0; i < dictionary.length; ++i) {
      dictionary[i] = dictionaryUnpacker.unpackString()
    }

    def expectedMeta = ['key1': 'value1', 'key2.sub1': 'v1', 'key2.sub2': 'v2']

    expect:
    traceCount == 1
    spanCount == 1
    size == 12
    for (int i = 0; i < 9; ++i) {
      unpacker.skipValue()
    }

    int packedSize = unpacker.unpackMapHeader()
    Map<String, String> unpackedMeta = [:]
    for (int j = 0; j < packedSize; j++) {
      def k = dictionary[unpacker.unpackInt()]
      def v = dictionary[unpacker.unpackInt()]
      if (k != "thread.name" && k != "thread.id") {
        unpackedMeta.put(k, v)
      }
    }
    assert unpackedMeta == expectedMeta

    cleanup:
    tracer.close()
  }

  private class CaptureBuffer implements ByteBufferConsumer {

    private byte[] bytes
    int messageCount

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      this.messageCount = messageCount
      this.bytes = new byte[buffer.limit() - buffer.position()]
      buffer.get(bytes)
    }
  }

  def createContext(String spanType, CoreTracer tracer, DDTraceId traceId, long spanId) {
    DDSpanContext ctx = new DDSpanContext(
      traceId,
      spanId,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      ["a-baggage": "value"],
      false,
      spanType,
      1,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null)
    ctx.setAllTags(["k1": "v1"])
    return ctx
  }
}
