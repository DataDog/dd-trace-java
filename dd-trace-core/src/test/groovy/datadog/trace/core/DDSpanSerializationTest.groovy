package datadog.trace.core

import com.squareup.moshi.Moshi
import datadog.trace.api.DDId
import datadog.trace.api.DDTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.util.test.DDSpecification
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.buffer.ArrayBufferInput
import org.msgpack.core.buffer.ArrayBufferOutput
import org.msgpack.value.ValueType

import static datadog.trace.core.serialization.JsonFormatWriter.SPAN_ADAPTER
import static datadog.trace.core.serialization.MsgpackFormatWriter.MSGPACK_WRITER

class DDSpanSerializationTest extends DDSpecification {

  def "serialize spans with sampling #samplingPriority"() throws Exception {
    setup:
    def jsonAdapter = new Moshi.Builder().build().adapter(Map)

    final Map<String, Number> metrics = ["_sampling_priority_v1": 1]
    if (samplingPriority == PrioritySampling.UNSET) {  // RateByServiceSampler sets priority
      metrics.put("_dd.agent_psr", 1.0d)
    }

    Map<String, Object> expected = [
      service  : "service",
      name     : "operation",
      resource : "operation",
      trace_id : 1l,
      span_id  : 2l,
      parent_id: 0l,
      start    : 100000,
      duration : 33000,
      type     : spanType,
      error    : 0,
      metrics  : metrics,
      meta     : [
        "a-baggage"         : "value",
        "k1"                : "v1",
        (DDTags.THREAD_NAME): Thread.currentThread().getName(),
        (DDTags.THREAD_ID)  : String.valueOf(Thread.currentThread().getId()),
      ],
    ]

    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    final DDSpanContext context =
      new DDSpanContext(
        DDId.from(1),
        DDId.from(2),
        DDId.ZERO,
        "service",
        "operation",
        null,
        samplingPriority,
        null,
        ["a-baggage": "value"],
        false,
        spanType,
        ["k1": "v1"],
        PendingTrace.create(tracer, DDId.ONE),
        tracer,
        [:])

    DDSpan span = DDSpan.create(100L, context)

    span.finish(133L)

    def actualTree = jsonAdapter.fromJson(SPAN_ADAPTER.toJson(span))
    def expectedTree = jsonAdapter.fromJson(jsonAdapter.toJson(expected))
    expect:
    actualTree == expectedTree

    where:
    samplingPriority              | spanType
    PrioritySampling.SAMPLER_KEEP | null
    PrioritySampling.UNSET        | "some-type"
  }

  def "serialize trace/span with id #value as int"() {
    setup:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
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
    def buffer = new ArrayBufferOutput()
    def packer = MessagePack.newDefaultPacker(buffer)
    MSGPACK_WRITER.writeDDSpan(span, packer)
    packer.flush()
    byte[] bytes = buffer.toByteArray()
    def unpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(bytes))
    int size = unpacker.unpackMapHeader()

    expect:
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
}
