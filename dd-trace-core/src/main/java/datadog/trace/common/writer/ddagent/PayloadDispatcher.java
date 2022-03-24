package datadog.trace.common.writer.ddagent;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.monitor.Monitoring;
import datadog.communication.monitor.Recording;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.WritableFormatter;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.nio.ByteBuffer;
import java.util.List;
import org.jctools.counters.CountersFactory;
import org.jctools.counters.FixedSizeStripedLongCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PayloadDispatcher implements ByteBufferConsumer {

  private static final Logger log = LoggerFactory.getLogger(PayloadDispatcher.class);

  private final DDAgentApi api;
  private final DDAgentFeaturesDiscovery featuresDiscovery;
  private final HealthMetrics healthMetrics;
  private final Monitoring monitoring;

  private Recording batchTimer;
  private TraceMapper traceMapper;
  private WritableFormatter packer;

  private final FixedSizeStripedLongCounter droppedSpanCount =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter droppedTraceCount =
      CountersFactory.createFixedSizeStripedCounter(8);

  public PayloadDispatcher(
      DDAgentFeaturesDiscovery featuresDiscovery,
      DDAgentApi api,
      HealthMetrics healthMetrics,
      Monitoring monitoring) {
    this.featuresDiscovery = featuresDiscovery;
    this.api = api;
    this.healthMetrics = healthMetrics;
    this.monitoring = monitoring;
  }

  void flush() {
    if (null != packer) {
      packer.flush();
    }
  }

  public void onDroppedTrace(int spanCount) {
    droppedSpanCount.inc(spanCount);
    droppedTraceCount.inc();
  }

  void addTrace(List<? extends CoreSpan<?>> trace) {
    selectTraceMapper();
    // the call below is blocking and will trigger IO if a flush is necessary
    // there are alternative approaches to avoid blocking here, such as
    // introducing an unbound queue and another thread to do the IO
    // however, we can't block the application threads from here.
    if (null == traceMapper || !packer.format(trace, traceMapper)) {
      healthMetrics.onFailedPublish(trace.get(0).samplingPriority());
    }
  }

  private void selectTraceMapper() {
    if (null == traceMapper) {
      if (featuresDiscovery.getTraceEndpoint() == null) {
        featuresDiscovery.discover();
      }
      String tracesUrl = featuresDiscovery.getTraceEndpoint();
      if (DDAgentFeaturesDiscovery.V5_ENDPOINT.equalsIgnoreCase(tracesUrl)) {
        this.traceMapper = new TraceMapperV0_5();
      } else if (null != tracesUrl) {
        this.traceMapper = new TraceMapperV0_4();
      }
      if (null != traceMapper && null == packer) {
        this.batchTimer =
            monitoring.newTimer(
                "tracer.trace.buffer.fill.time", "endpoint:" + traceMapper.endpoint());
        this.packer = new MsgPackWriter(new FlushingBuffer(traceMapper.messageBufferSize(), this));
        batchTimer.start();
      }
    }
  }

  Payload newPayload(int messageCount, ByteBuffer buffer) {
    return traceMapper
        .newPayload()
        .withBody(messageCount, buffer)
        .withDroppedSpans(droppedSpanCount.getAndReset())
        .withDroppedTraces(droppedTraceCount.getAndReset());
  }

  @Override
  public void accept(int messageCount, ByteBuffer buffer) {
    // the packer calls this when the buffer is full,
    // or when the packer is flushed at a heartbeat
    if (messageCount > 0) {
      batchTimer.reset();
      Payload payload = newPayload(messageCount, buffer);
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
