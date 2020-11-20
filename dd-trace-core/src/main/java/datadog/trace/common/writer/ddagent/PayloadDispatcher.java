package datadog.trace.common.writer.ddagent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.Monitoring;
import datadog.trace.core.monitor.Recording;
import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.WritableFormatter;
import datadog.trace.core.serialization.msgpack.MsgPackWriter;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PayloadDispatcher implements ByteBufferConsumer {

  private final AtomicInteger droppedCount = new AtomicInteger();
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

  public void onTraceDropped() {
    droppedCount.incrementAndGet();
  }

  void addTrace(List<? extends AgentSpan<?>> trace) {
    selectTraceMapper();
    // the call below is blocking and will trigger IO if a flush is necessary
    // there are alternative approaches to avoid blocking here, such as
    // introducing an unbound queue and another thread to do the IO
    // however, we can't block the application threads from here.
    if (null != traceMapper) {
      packer.format(trace, traceMapper);
    } else { // if the mapper is null, then there's no agent running, so we should drop
      onTraceDropped();
      log.debug("dropping {} traces because no agent was detected", 1);
    }
  }

  private void selectTraceMapper() {
    if (null == traceMapper) {
      this.traceMapper = api.selectTraceMapper();
      if (null != traceMapper && null == packer) {
        this.batchTimer =
            monitoring.newTimer(
                "tracer.trace.buffer.fill.time", "endpoint:" + traceMapper.endpoint());
        this.packer = new MsgPackWriter(this, ByteBuffer.allocate(traceMapper.messageBufferSize()));
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
      final int representativeCount = this.droppedCount.getAndSet(0) + messageCount;
      Payload payload =
          traceMapper
              .newPayload()
              .withRepresentativeCount(representativeCount)
              .withBody(messageCount, buffer);
      final int sizeInBytes = payload.sizeInBytes();
      healthMetrics.onSerialize(sizeInBytes);
      DDAgentApi.Response response = api.sendSerializedTraces(payload);
      traceMapper.reset();
      if (response.success()) {
        if (log.isDebugEnabled()) {
          log.debug("Successfully sent {} traces to the API", messageCount);
        }
        healthMetrics.onSend(representativeCount, sizeInBytes, response);
      } else {
        if (log.isDebugEnabled()) {
          log.debug(
              "Failed to send {} traces (representing {}) of size {} bytes to the API",
              messageCount,
              representativeCount,
              sizeInBytes);
        }
        healthMetrics.onFailedSend(representativeCount, sizeInBytes, response);
      }
    }
  }
}
