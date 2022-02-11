package datadog.trace.core.datastreams;

import static datadog.trace.util.AgentThreadFactory.AgentThread.DATA_STREAMS_MONITORING;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.function.Consumer;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.OkHttpSink;
import datadog.trace.common.metrics.Sink;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultDataStreamsCheckpointer
    implements DataStreamsCheckpointer, AutoCloseable, Runnable, EventListener, Consumer<StatsPoint> {
  private static final Logger log = LoggerFactory.getLogger(DataStreamsCheckpointer.class);

  private static final long BUCKET_DURATION_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private static final StatsPoint REPORT = new StatsPoint(null, 0, 0, 0, 0, 0);
  private static final StatsPoint POISON_PILL = new StatsPoint( null, 0, 0, 0, 0, 0);

  private final Map<Long, StatsBucket> timeToBucket = new HashMap<>();
  private final BlockingQueue<StatsPoint> inbox = new MpscBlockingConsumerArrayQueue<>(1024);
  private final Thread thread;
  private final AgentTaskScheduler.Scheduled<DefaultDataStreamsCheckpointer> cancellation;
  private final DatastreamsPayloadWriter payloadWriter;

  public DefaultDataStreamsCheckpointer(
      Config config, SharedCommunicationObjects sharedCommunicationObjects) {

    Sink sink =
        new OkHttpSink(
            sharedCommunicationObjects.okHttpClient,
            "https://trace.agent." + config.getSite(),
            "/api/v0.1/pipeline_stats",
            SECONDS.toNanos(1),
            false,
            true,
            Collections.singletonMap("DD-API-KEY", config.getApiKey()));

    payloadWriter = new DatastreamsPayloadWriter(sink, config.getEnv());
    thread = newAgentThread(DATA_STREAMS_MONITORING, this);
    cancellation =
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            new ReportTask(),
            this,
            BUCKET_DURATION_MILLIS,
            BUCKET_DURATION_MILLIS,
            TimeUnit.MILLISECONDS);
    thread.start();
  }

  @Override
  public void setDataStreamCheckpoint(String edgeName, PathwayContextHolder holder) {
    log.debug("Adding checkpoint for: {}", edgeName);
    PathwayContext pathwayContext = holder.getOrCreatePathwayContext(this);
    pathwayContext.setCheckpoint(edgeName, this);
  }

  // With Java 8, this becomes unnecessary
  @Override
  public void accept(StatsPoint statsPoint) {
    inbox.offer(statsPoint);
  }

  @Override
  public void close() {
    if (null != cancellation) {
      cancellation.cancel();
    }

    inbox.offer(POISON_PILL);
    try {
      thread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
  }

  @Override
  public void run() {
    Thread currentThread = Thread.currentThread();
    while (!currentThread.isInterrupted()) {
      try {
        StatsPoint statsPoint = inbox.take();

        if (statsPoint == REPORT) {
          flush(System.currentTimeMillis());
        } else if (statsPoint == POISON_PILL) {
          flush(Long.MAX_VALUE);
          break;
        } else {
          Long bucket =
              statsPoint.getTimestampMillis()
                  - (statsPoint.getTimestampMillis() % BUCKET_DURATION_MILLIS);

          // FIXME computeIfAbsent() is not available because Java 7
          // No easy way to have Java 8 in core even though datastreams monitoring is 8+ from
          // DDSketch
          StatsBucket statsBucket = timeToBucket.get(bucket);
          if (statsBucket == null) {
            statsBucket = new StatsBucket(bucket, BUCKET_DURATION_MILLIS);
            timeToBucket.put(bucket, statsBucket);
          }

          statsBucket.addPoint(statsPoint);
        }
      } catch (InterruptedException e) {
        currentThread.interrupt();
      } catch (Exception e) {
        log.debug("Error monitoring data streams", e);
      }
    }
  }

  private void flush(long timestampMillis) {
    long cutoff = timestampMillis - BUCKET_DURATION_MILLIS;

    List<StatsBucket> includedGroups = new ArrayList<>();
    Iterator<Map.Entry<Long, StatsBucket>> mapIterator = timeToBucket.entrySet().iterator();

    while (mapIterator.hasNext()) {
      Map.Entry<Long, StatsBucket> entry = mapIterator.next();

      if (entry.getKey() < cutoff) {
        mapIterator.remove();
        includedGroups.add(entry.getValue());
      }
    }

    if (!includedGroups.isEmpty()) {
      log.debug("Flushing {} groups", includedGroups.size());
      payloadWriter.writePayload(includedGroups);
    }
  }

  @Override
  public void onEvent(EventType eventType, String message) {
    log.debug("Received event {} {}", eventType, message);
    // TODO implement downgrade logic
  }

  private static final class ReportTask
      implements AgentTaskScheduler.Task<DefaultDataStreamsCheckpointer> {
    @Override
    public void run(DefaultDataStreamsCheckpointer target) {
      target.inbox.offer(REPORT);
    }
  }
}
