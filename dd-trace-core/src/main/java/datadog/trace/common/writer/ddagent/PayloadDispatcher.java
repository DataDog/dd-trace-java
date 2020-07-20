package datadog.trace.common.writer.ddagent;

import datadog.trace.core.DDSpan;
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer;
import datadog.trace.core.serialization.msgpack.Packer;
import java.nio.ByteBuffer;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PayloadDispatcher implements ByteBufferConsumer {

  static final int DEFAULT_BUFFER_SIZE = 2 << 20; // 2MB

  private final DDAgentApi api;
  private int representativeCount;
  private TraceMapper traceMapper;
  private Packer packer;
  private final Monitor monitor;

  PayloadDispatcher(DDAgentApi api, Monitor monitor) {
    this.api = api;
    this.monitor = monitor;
  }

  void flush() {
    if (null != packer) {
      packer.flush();
    }
  }

  void addTrace(List<DDSpan> trace) {
    selectTraceMapper();
    // the call below is blocking and will trigger IO if a flush is necessary
    // there are alternative approaches to avoid blocking here, such as
    // introducing an unbound queue and another thread to do the IO
    // however, we can't block the application threads from here.
    if (null != traceMapper) {
      packer.format(trace, traceMapper);
    } else { // if the mapper is null, then there's no agent running, so we should drop
      log.debug("dropping {} traces because no agent was detected", 1);
    }
    ++representativeCount;
  }

  private void selectTraceMapper() {
    if (null == traceMapper) {
      this.traceMapper = api.selectTraceMapper();
      if (null == packer) {
        this.packer = new Packer(this, ByteBuffer.allocate(DEFAULT_BUFFER_SIZE));
      }
    }
  }

  @Override
  public void accept(int messageCount, ByteBuffer buffer) {
    // the packer calls this when the buffer is full,
    // or when the packer is flushed at a heartbeat
    if (messageCount > 0) {
      final int sizeInBytes = buffer.limit() - buffer.position();
      monitor.onSerialize(sizeInBytes);
      Payload payload =
          traceMapper
              .newPayload()
              .withRepresentativeCount(representativeCount)
              .withBody(messageCount, buffer);
      DDAgentApi.Response response = api.sendSerializedTraces(payload);
      traceMapper.reset();
      if (response.success()) {
        if (log.isDebugEnabled()) {
          log.debug("Successfully sent {} traces to the API", messageCount);
        }
        monitor.onSend(representativeCount, sizeInBytes, response);
      } else {
        if (log.isDebugEnabled()) {
          log.debug(
              "Failed to send {} traces (representing {}) of size {} bytes to the API",
              messageCount,
              representativeCount,
              sizeInBytes);
        }
        monitor.onFailedSend(representativeCount, sizeInBytes, response);
      }
      this.representativeCount = 0;
    }
  }
}
