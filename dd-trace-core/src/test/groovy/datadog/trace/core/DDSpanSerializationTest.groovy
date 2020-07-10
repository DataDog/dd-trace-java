package datadog.trace.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.trace.api.DDId
import datadog.trace.api.DDTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.LoggingWriter
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

  def "serialize trace with sampling #samplingPriority"() throws Exception {
    setup:
    def jsonAdapter = new Moshi.Builder().build().adapter(Types.newParameterizedType(List, Map))

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

    def actualTree = jsonAdapter.fromJson(LoggingWriter.TRACE_ADAPTER.toJson(Collections.singletonList(span)))
    def expectedTree = jsonAdapter.fromJson(jsonAdapter.toJson(Collections.singletonList(expected)))
    expect:
    actualTree == expectedTree

    where:
    samplingPriority              | spanType
    PrioritySampling.SAMPLER_KEEP | null
    PrioritySampling.UNSET        | "some-type"
  }

  def "serialize trace with id #value as int"() {
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


  private class CaptureBuffer implements ByteBufferConsumer {

    private byte[] bytes

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      this.bytes = new byte[buffer.limit() - buffer.position()]
      buffer.get(bytes)
    }
  }
}
