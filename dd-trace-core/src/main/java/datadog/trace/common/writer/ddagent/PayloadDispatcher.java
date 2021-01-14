package datadog.trace.common.writer.ddagent;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.Monitoring;
import datadog.trace.core.monitor.Recording;
import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.FlushingBuffer;
import datadog.trace.core.serialization.WritableFormatter;
import datadog.trace.core.serialization.msgpack.MsgPackWriter;
import java.nio.ByteBuffer;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PayloadDispatcher implements ByteBufferConsumer {

  private final DDAgentApi api;
  private final HealthMetrics healthMetrics;
  private final Monitoring monitoring;

  private Recording batchTimer;
  private TraceMapper traceMapper;
  private WritableFormatter packer;

  public PayloadDispatcher(DDAgentApi api, HealthMetrics healthMetrics, Monitoring monitoring) {
    this.api = api;
    this.healthMetrics = healthMetrics;
    this.monitoring = monitoring;
  }

  void flush() {
    if (null != packer) {
      packer.flush();
    }
  }

  void addTrace(List<? extends CoreSpan<?>> trace) {
    selectTraceMapper();
    // the call below is blocking and will trigger IO if a flush is necessary
    // there are alternative approaches to avoid blocking here, such as
    // introducing an unbound queue and another thread to do the IO
    // however, we can't block the application threads from here.
    if (null != traceMapper) {
      packer.format(trace, traceMapper);
    } else {
      healthMetrics.onFailedPublish(trace.get(0).samplingPriority());
    }
  }

  private void selectTraceMapper() {
    if (null == traceMapper) {
      this.traceMapper = api.selectTraceMapper();
      if (null != traceMapper && null == packer) {
        this.batchTimer =
            monitoring.newTimer(
                "tracer.trace.buffer.fill.time", "endpoint:" + traceMapper.endpoint());
        this.packer = new MsgPackWriter(new FlushingBuffer(traceMapper.messageBufferSize(), this));
        batchTimer.start();
      }
    }
  }

  @Override
  public void accept(int messageCount, ByteBuffer buffer) {
    // the packer calls this when the buffer is full,
    // or when the packer is flushed at a heartbeat
    if (messageCount > 0) {
      batchTimer.reset();
      Payload payload = traceMapper.newPayload().withBody(messageCount, buffer);
      final int sizeInBytes = payload.sizeInBytes();
      healthMetrics.onSerialize(sizeInBytes);
      DDAgentApi.Response response = api.sendSerializedTraces(payload);
      traceMapper.reset();
      if (response.success()) {
        if (log.isDebugEnabled()) {
          log.debug("Successfully sent {} traces to the API", messageCount);
        }
        healthMetrics.onSend(messageCount, sizeInBytes, response);
      } else {
        if (log.isDebugEnabled()) {
          log.debug(
              "Failed to send {} traces of size {} bytes to the API", messageCount, sizeInBytes);
        }
        healthMetrics.onFailedSend(messageCount, sizeInBytes, response);
      }
    }
  }
}
