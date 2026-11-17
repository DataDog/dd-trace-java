package datadog.trace.common.writer;

import datadog.communication.serialization.ChunkedBufferConsumer;
import datadog.communication.serialization.ChunkedStreamingBuffer;
import datadog.communication.serialization.WritableFormatter;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.Monitoring;
import datadog.metrics.api.Recording;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PayloadDispatcherImpl implements ChunkedBufferConsumer, PayloadDispatcher {

  private static final Logger log = LoggerFactory.getLogger(PayloadDispatcherImpl.class);

  /**
   * How long the writer must have had nothing to send before an idle flush starts releasing the
   * payload buffer's surplus chunks, so traffic that pauses between bursts keeps reusing them.
   */
  static final long IDLE_TRIM_DELAY_NANOS = TimeUnit.SECONDS.toNanos(2);

  private final RemoteApi api;
  private final RemoteMapperDiscovery mapperDiscovery;
  private final HealthMetrics healthMetrics;
  private final Monitoring monitoring;
  private final TimeSource timeSource;

  private Recording batchTimer;
  private RemoteMapper mapper;
  private ChunkedStreamingBuffer buffer;
  private WritableFormatter packer;
  // when the last payload was handed over, or the buffer created: the start of any idle period
  private long lastPayloadTicks;

  /**
   * Whether the buffer holds traces not yet handed over. The mapper is only re-selected between
   * payloads; if discovery moves mid-payload, the API sends that payload by its own protocol.
   */
  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification = "confined to the one thread that encodes traces, which also runs the flushes")
  private boolean payloadStarted;

  private final LongAdder droppedSpanCount = new LongAdder();
  private final LongAdder droppedTraceCount = new LongAdder();

  public PayloadDispatcherImpl(
      RemoteMapperDiscovery mapperDiscovery,
      RemoteApi api,
      HealthMetrics healthMetrics,
      Monitoring monitoring) {
    this(mapperDiscovery, api, healthMetrics, monitoring, SystemTimeSource.INSTANCE);
  }

  PayloadDispatcherImpl(
      RemoteMapperDiscovery mapperDiscovery,
      RemoteApi api,
      HealthMetrics healthMetrics,
      Monitoring monitoring,
      TimeSource timeSource) {
    this.mapperDiscovery = mapperDiscovery;
    this.api = api;
    this.healthMetrics = healthMetrics;
    this.monitoring = monitoring;
    this.timeSource = timeSource;
  }

  @Override
  public void flush() {
    if (null == packer) {
      return;
    }
    if (buffer.isDirty()) {
      packer.flush();
    } else if (timeSource.getNanoTicks() - lastPayloadTicks >= IDLE_TRIM_DELAY_NANOS) {
      // nothing sent for a while: let the chunks a burst left behind drain while the writer is idle
      buffer.trim();
    }
  }

  /** The chunks the payload buffer holds, or 0 before it's created. */
  @VisibleForTesting
  int bufferedChunks() {
    return null == buffer ? 0 : buffer.allocatedChunks();
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
    // the calls below are blocking and will trigger IO if a flush is necessary
    // there are alternative approaches to avoid blocking here, such as
    // introducing an unbound queue and another thread to do the IO
    // however, we can't block the application threads from here.
    boolean written;
    if (mapper == null) {
      written = false;
    } else if (!payloadStarted) {
      // an empty buffer: nothing to send on overflow, so the writer rejects a trace too large for
      // it
      written = packer.format(trace, mapper);
    } else {
      written = packer.formatIfRoom(trace, mapper);
      if (!written) {
        // The buffer is full: send it, then pick the mapper for the next payload before retrying
        // the
        // trace, so a protocol change takes effect even when no flush lands between traces.
        packer.flush();
        selectMapper();
        written = mapper != null && packer.formatAsRetry(trace, mapper);
      }
    }
    if (!written) {
      healthMetrics.onFailedPublish(
          trace.isEmpty() ? 0 : trace.get(0).samplingPriority(), trace.size());
    } else {
      payloadStarted = true;
    }
  }

  private void selectMapper() {
    if (mapper != null && !payloadStarted && mapperDiscovery.isOutdated()) {
      // The destination now takes another protocol: encode the next payload with a newly discovered
      // mapper. This happens between payloads, never inside the buffer's flush, so no trace is
      // stranded in the old mapper's buffer; the flush is normally a no-op.
      packer.flush();
      mapper = null;
      packer = null;
      mapperDiscovery.discover();
    }
    if (null == mapper) {
      if (mapperDiscovery.getMapper() == null) {
        mapperDiscovery.discover();
      }
      mapper = mapperDiscovery.getMapper();
    }
    if (null == packer && null != mapper) {
      batchTimer =
          monitoring.newTimer("tracer.trace.buffer.fill.time", "endpoint:" + mapper.endpoint());
      buffer = new ChunkedStreamingBuffer(mapper.messageBufferSize(), this);
      packer = new MsgPackWriter(buffer);
      lastPayloadTicks = timeSource.getNanoTicks();
      batchTimer.start();
    }
  }

  Payload newPayload(int messageCount, List<ByteBuffer> chunks) {
    return mapper
        .newPayload()
        .withBody(messageCount, chunks)
        .withDroppedSpans(droppedSpanCount.sumThenReset())
        .withDroppedTraces(droppedTraceCount.sumThenReset());
  }

  @Override
  public void accept(int messageCount, List<ByteBuffer> chunks) {
    // the packer calls this when the buffer is full,
    // or when the packer is flushed at a heartbeat
    if (messageCount > 0) {
      payloadStarted = false;
      lastPayloadTicks = timeSource.getNanoTicks();
      batchTimer.reset();
      Payload payload = newPayload(messageCount, chunks);
      final int sizeInBytes = payload.sizeInBytes();
      healthMetrics.onSerialize(sizeInBytes);
      RemoteApi.Response response = api.sendSerializedTraces(payload);
      mapper.reset();
      if (response.success()) {
        if (log.isDebugEnabled()) {
          log.debug(
              "Successfully sent {} traces of size {} bytes to the API {}",
              messageCount,
              sizeInBytes,
              mapper.endpoint());
        }
        healthMetrics.onSend(messageCount, sizeInBytes, response);
      } else {
        if (log.isDebugEnabled()) {
          log.debug(
              "Failed to send {} traces of size {} bytes to the API {} status {} response {}",
              messageCount,
              sizeInBytes,
              mapper.endpoint(),
              response.status(),
              response.response());
        }
        healthMetrics.onFailedSend(messageCount, sizeInBytes, response);
      }
    }
  }
}
