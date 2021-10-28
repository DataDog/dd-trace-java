package datadog.trace.common.writer

import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.core.DDSpan
import datadog.trace.core.test.DDCoreSpecification
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

import java.nio.ByteBuffer

class TraceMapperTest extends DDCoreSpecification {

  def "test trace mapper v0.5"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    DDSpan span = (DDSpan) tracer.buildSpan(null).withTag("service.name", "my-service")
      .withTag("elasticsearch.version", "7.0").start()
    span.setBaggageItem("baggage", "item")
    span.context().setDataTop("mydata", "[1,2,3]")
    def trace = [span]

    when:
    TraceMapper traceMapper = new TraceMapperV0_5()
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer()
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink))
    packer.format(trace, traceMapper)
    packer.flush()

    then:
    sink.captured != null
    ByteBuffer dictionaryBytes = traceMapper.dictionary.slice()
    Map<String, String> meta = new HashMap<>()

    MessageUnpacker dictionaryUnpacker = MessagePack.newDefaultUnpacker(dictionaryBytes)
    int dictionaryLength = traceMapper.encoding.size()
    String[] dictionary = new String[dictionaryLength]
    for (int i = 0; i < dictionary.length; ++i) {
      dictionary[i] = dictionaryUnpacker.unpackString()
    }
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(sink.captured)
    int traceCount = unpacker.unpackArrayHeader()
    traceCount == 1
    for (int i = 0; i < traceCount; ++i) {
      int arrayLength = unpacker.unpackArrayHeader()
      arrayLength == 12
      String serviceName = dictionary[unpacker.unpackInt()]
      serviceName == "my-service"
      String operationName = dictionary[unpacker.unpackInt()] // operation name null
      operationName == null
      String resourceName = dictionary[unpacker.unpackInt()]
      resourceName != null
      long traceId = unpacker.unpackLong()
      traceId == 1
      long spanId = unpacker.unpackLong()
      spanId == 1
      long parentId = unpacker.unpackLong()
      parentId == 0
      long start = unpacker.unpackLong()
      start > 0
      long duration = unpacker.unpackLong()
      duration >= 0
      int error = unpacker.unpackInt()
      error == 0
      int metaHeader = unpacker.unpackMapHeader()
      for (int j = 0; j < metaHeader; ++j) {
        String key = dictionary[unpacker.unpackInt()]
        key != null
        String value = dictionary[unpacker.unpackInt()]
        value != null
        meta.put(key, value)
      }
      int metricsHeader = unpacker.unpackMapHeader()
      for (int j = 0; j < metricsHeader; ++j) {
        String key = dictionary[unpacker.unpackInt()]
        key != null
        unpacker.skipValue()
      }
      String type = dictionary[unpacker.unpackInt()]
      type != null

      meta.findResult {it.getKey().contains('.mydata.') ? it.getValue() : null } == '[1,2,3]'
    }

    cleanup:
    tracer.close()
  }

  static class CapturingByteBufferConsumer implements ByteBufferConsumer {

    ByteBuffer captured

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      captured = buffer
    }
  }
}
