package datadog.trace.civisibility.writer.ddintake

import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.common.writer.common.TraceGenerator
import datadog.trace.test.util.DDSpecification

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

import static datadog.trace.common.writer.common.TraceGenerator.generateRandomTraces

class CiTestCycleMapperV1PayloadTest extends DDSpecification {

  def "test traces written correctly"() {
    setup:
    List<List<TraceGenerator.PojoSpan>> traces = generateRandomTraces(traceCount, lowCardinality)
    CiTestCycleMapperV1 mapper = new CiTestCycleMapperV1()
    PayloadVerifier verifier = new PayloadVerifier(traces, mapper)
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(bufferSize, verifier))
    when:
    boolean tracesFitInBuffer = true
    for (List<TraceGenerator.PojoSpan> trace : traces) {
      if (!packer.format(trace, mapper)) {
        verifier.skipLargeTrace()
        tracesFitInBuffer = false
      }
    }
    packer.flush()
    then:
    if (tracesFitInBuffer) {
      verifier.verifyTracesConsumed()
    }
    where:
    bufferSize | traceCount | lowCardinality
    20 << 10   | 0          | true
    20 << 10   | 1          | true
    30 << 10   | 1          | true
    30 << 10   | 2          | true
    20 << 10   | 0          | false
    20 << 10   | 1          | false
    30 << 10   | 1          | false
    30 << 10   | 2          | false
    100 << 10  | 0          | true
    100 << 10  | 1          | true
    100 << 10  | 10         | true
    100 << 10  | 100        | true
    100 << 10  | 1000       | true
    100 << 10  | 0          | false
    100 << 10  | 1          | false
    100 << 10  | 10         | false
    100 << 10  | 100        | false
    100 << 10  | 1000       | false
  }


  private static final class PayloadVerifier implements ByteBufferConsumer, WritableByteChannel {

    private final List<List<TraceGenerator.PojoSpan>> expectedTraces
    private final CiTestCycleMapperV1 mapper
    private ByteBuffer captured = ByteBuffer.allocate(200 << 10)

    private int position = 0

    private PayloadVerifier(List<List<TraceGenerator.PojoSpan>> traces, CiTestCycleMapperV1 mapper) {
      this.expectedTraces = traces
      this.mapper = mapper
    }

    void skipLargeTrace() {
      ++position
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {

    }

    @Override
    int write(ByteBuffer src) throws IOException {
      return 0
    }

    @Override
    boolean isOpen() {
      return false
    }

    @Override
    void close() throws IOException {

    }
  }

}
