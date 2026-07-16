package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.common.writer.ddagent.TraceMapperTestBridge;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

class TraceMapperTest extends DDCoreJavaSpecification {

  @Test
  void testTraceMapperV05() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("datadog", null)
                .withTag("service.name", "my-service")
                .withTag("elasticsearch.version", "7.0")
                .start();
    span.setBaggageItem("baggage", "item");
    span.spanContext().setDataTop("mydata", "[1,2,3]");
    List<DDSpan> trace = Collections.singletonList(span);

    TraceMapperV0_5 traceMapper = new TraceMapperV0_5();
    CapturingByteBufferConsumer sink = new CapturingByteBufferConsumer();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1024, sink));
    packer.format(trace, traceMapper);
    packer.flush();

    // only top-level statements in Spock then-blocks are power assertions;
    // expressions inside for-loops are not, so we only assert the critical outcomes
    assertNotNull(sink.captured);
    GrowableBuffer dictionaryBuffer = TraceMapperTestBridge.getDictionary(traceMapper);
    ByteBuffer dictionaryBytes = dictionaryBuffer.slice();
    Map<String, String> meta = new HashMap<>();

    MessageUnpacker dictionaryUnpacker = MessagePack.newDefaultUnpacker(dictionaryBytes);
    int dictionaryLength = TraceMapperTestBridge.getEncoding(traceMapper).size();
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
      String operationName = dictionary[unpacker.unpackInt()];
      assertTrue(operationName.isEmpty());
      String resourceName = dictionary[unpacker.unpackInt()];
      assertTrue(resourceName.isEmpty());
      long traceId = unpacker.unpackLong();
      assertTrue(traceId > 0);
      long spanId = unpacker.unpackLong();
      assertTrue(spanId > 0);
      long parentId = unpacker.unpackLong();
      assertEquals(0, parentId);
      long start = unpacker.unpackLong();
      assertTrue(start > 0);
      long duration = unpacker.unpackLong();
      assertEquals(-start, duration);
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

      // find the meta entry whose key contains ".mydata." and verify its value
      String myDataValue = null;
      for (Map.Entry<String, String> entry : meta.entrySet()) {
        if (entry.getKey().contains(".mydata.")) {
          myDataValue = entry.getValue();
          break;
        }
      }
      assertEquals("[1,2,3]", myDataValue);
    }

    tracer.close();
  }

  static class CapturingByteBufferConsumer implements ByteBufferConsumer {

    ByteBuffer captured;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      captured = buffer;
    }
  }
}
