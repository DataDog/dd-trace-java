package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

class TraceMapperTest extends DDCoreSpecification {

  @Test
  void testTraceMapperV05() throws Exception {
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span =
          (DDSpan)
              tracer
                  .buildSpan(null)
                  .withTag("service.name", "my-service")
                  .withTag("elasticsearch.version", "7.0")
                  .start();
      span.setBaggageItem("baggage", "item");
      span.context().setDataTop("mydata", "[1,2,3]");
      span.finish();
      List<DDSpan> trace = new ArrayList<>();
      trace.add(span);

      TraceMapperV0_5 traceMapper = new TraceMapperV0_5();
      CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink));
      packer.format((List) trace, traceMapper);
      packer.flush();

      assertNotNull(sink.captured);
      ByteBuffer dictionaryBytes = traceMapper.getDictionary();
      Map<String, String> meta = new HashMap<>();

      MessageUnpacker dictionaryUnpacker = MessagePack.newDefaultUnpacker(dictionaryBytes);
      int dictionaryLength = traceMapper.getEncodingSize();
      String[] dictionary = new String[dictionaryLength];
      for (int i = 0; i < dictionary.length; ++i) {
        dictionary[i] = dictionaryUnpacker.unpackString();
      }
      MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(sink.captured);
      int traceCount = unpacker.unpackArrayHeader();
      assertEquals(1, traceCount);
      for (int i = 0; i < traceCount; ++i) {
        int arrayLength = unpacker.unpackArrayHeader();
        assertEquals(12, arrayLength);
        String serviceName = dictionary[unpacker.unpackInt()];
        assertEquals("my-service", serviceName);
        String operationName = dictionary[unpacker.unpackInt()]; // operation name null
        assertTrue(operationName == null || operationName.isEmpty());
        String resourceName = dictionary[unpacker.unpackInt()];
        assertNotNull(resourceName);
        long traceId = unpacker.unpackLong();
        assertTrue(traceId > 0);
        long spanId = unpacker.unpackLong();
        assertTrue(spanId > 0);
        long parentId = unpacker.unpackLong();
        assertEquals(0, parentId);
        long start = unpacker.unpackLong();
        assertTrue(start > 0);
        long duration = unpacker.unpackLong();
        assertTrue(duration >= 0);
        int error = unpacker.unpackInt();
        assertEquals(0, error);
        int metaHeader = unpacker.unpackMapHeader();
        for (int j = 0; j < metaHeader; ++j) {
          String key = dictionary[unpacker.unpackInt()];
          assertNotNull(key);
          String value = dictionary[unpacker.unpackInt()];
          assertNotNull(value);
          meta.put(key, value);
        }
        int metricsHeader = unpacker.unpackMapHeader();
        for (int j = 0; j < metricsHeader; ++j) {
          String key = dictionary[unpacker.unpackInt()];
          assertNotNull(key);
          unpacker.skipValue();
        }
        String type = dictionary[unpacker.unpackInt()];
        assertNotNull(type);

        String mydataValue = null;
        for (Map.Entry<String, String> entry : meta.entrySet()) {
          if (entry.getKey().contains(".mydata.")) {
            mydataValue = entry.getValue();
            break;
          }
        }
        assertEquals("[1,2,3]", mydataValue);
      }
    } finally {
      tracer.close();
    }
  }

  static class CapturingByteBufferConsumer implements ByteBufferConsumer {

    ByteBuffer captured;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      captured = buffer;
    }
  }
}
