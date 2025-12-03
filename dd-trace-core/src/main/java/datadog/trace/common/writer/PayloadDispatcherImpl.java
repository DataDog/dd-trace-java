package datadog.trace.common.writer;

import datadog.communication.monitor.Monitoring;
import datadog.communication.monitor.Recording;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.WritableFormatter;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PayloadDispatcherImpl implements ByteBufferConsumer, PayloadDispatcher {

  private static final Logger log = LoggerFactory.getLogger(PayloadDispatcherImpl.class);

  private final RemoteApi api;
  private final RemoteMapperDiscovery mapperDiscovery;
  private final HealthMetrics healthMetrics;
  private final Monitoring monitoring;

  private Recording batchTimer;
  private RemoteMapper mapper;
  private WritableFormatter packer;

  private final LongAdder droppedSpanCount = new LongAdder();
  private final LongAdder droppedTraceCount = new LongAdder();

  public PayloadDispatcherImpl(
      RemoteMapperDiscovery mapperDiscovery,
      RemoteApi api,
      HealthMetrics healthMetrics,
      Monitoring monitoring) {
    this.mapperDiscovery = mapperDiscovery;
    this.api = api;
    this.healthMetrics = healthMetrics;
    this.monitoring = monitoring;
  }

  @Override
  public void flush() {
    if (null != packer) {
      packer.flush();
    }
  }

  @Override
  public Collection<RemoteApi> getApis() {
    return Collections.singleton(api);
  }

  @Override
  public void onDroppedTrace(int spanCount) {
    droppedSpanCount.add(spanCount);
    droppedTraceCount.increment();
  }

  @Override
  public void addTrace(List<? extends CoreSpan<?>> trace) {
    selectMapper();
    // the call below is blocking and will trigger IO if a flush is necessary
    // there are alternative approaches to avoid blocking here, such as
    // introducing an unbound queue and another thread to do the IO
    // however, we can't block the application threads from here.
    if (null == mapper || !packer.format(trace, mapper)) {
      healthMetrics.onFailedPublish(
          trace.isEmpty() ? 0 : trace.get(0).samplingPriority(), trace.size());
    }
  }

  private void selectMapper() {
    if (null == mapper) {
      if (mapperDiscovery.getMapper() == null) {
        mapperDiscovery.discover();
      }

      mapper = mapperDiscovery.getMapper();
      if (null != mapper && null == packer) {
        batchTimer =
            monitoring.newTimer("tracer.trace.buffer.fill.time", "endpoint:" + mapper.endpoint());
        packer = new MsgPackWriter(new FlushingBuffer(mapper.messageBufferSize(), this));
        batchTimer.start();
      }
    }
  }

  Payload newPayload(int messageCount, ByteBuffer buffer) {
    return mapper
        .newPayload()
        .withBody(messageCount, buffer)
        .withDroppedSpans(droppedSpanCount.sumThenReset())
        .withDroppedTraces(droppedTraceCount.sumThenReset());
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
      RemoteApi.Response response = api.sendSerializedTraces(payload);
      mapper.reset();

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
