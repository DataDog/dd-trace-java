package com.datadog.debugger.sink;

import static com.datadog.debugger.uploader.BatchUploader.APPLICATION_JSON;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.agent.ProbeStatus.Builder;
import com.datadog.debugger.agent.ProbeStatus.Status;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.ExceptionHelper;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.http.client.HttpUrl;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collects probe status messages that needs to be sent to the backend */
public class ProbeStatusSink {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProbeStatusSink.class);
  private static final JsonAdapter<ProbeStatus> PROBE_STATUS_ADAPTER =
      MoshiHelper.createMoshiProbeStatus().adapter(ProbeStatus.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;
  public static final BatchUploader.RetryPolicy RETRY_POLICY = new BatchUploader.RetryPolicy(10);

  private final BatchUploader diagnosticUploader;
  private final Builder messageBuilder;
  private final Map<String, TimedMessage> probeStatuses = new ConcurrentHashMap<>();
  private final ArrayBlockingQueue<ProbeStatus> queue;
  private final Duration interval;
  private final int batchSize;
  private final RatelimitedLogger ratelimitedLogger =
      new RatelimitedLogger(LOGGER, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES);
  private final boolean isInstrumentTheWorld;
  private final boolean useMultiPart;

  public ProbeStatusSink(Config config, String diagnosticsEndpoint, boolean useMultiPart) {
    this(
        config,
        new BatchUploader("Diagnostics", config, diagnosticsEndpoint, RETRY_POLICY),
        useMultiPart);
  }

  ProbeStatusSink(Config config, BatchUploader diagnosticUploader, boolean useMultiPart) {
    this.diagnosticUploader = diagnosticUploader;
    this.useMultiPart = useMultiPart;
    this.messageBuilder = new Builder(config);
    this.interval = Duration.ofSeconds(config.getDynamicInstrumentationDiagnosticsInterval());
    this.batchSize = config.getDynamicInstrumentationUploadBatchSize();
    this.queue = new ArrayBlockingQueue<>(2 * this.batchSize);
    this.isInstrumentTheWorld = config.getDynamicInstrumentationInstrumentTheWorld() != null;
  }

  public void stop() {
    diagnosticUploader.shutdown();
  }

  public void addReceived(ProbeId probeId) {
    addDiagnostics(messageBuilder.receivedMessage(probeId));
  }

  public void addInstalled(ProbeId probeId) {
    addDiagnostics(messageBuilder.installedMessage(probeId));
  }

  public void addEmitting(ProbeId probeId) {
    addEmitting(probeId.getEncodedId());
  }

  public void addEmitting(String encodedProbeId) {
    TimedMessage timedMessage = probeStatuses.get(encodedProbeId);
    if (timedMessage != null
        && timedMessage.getMessage().getDiagnostics().getStatus() == Status.EMITTING) {
      // if we already have a message for this probe, don't build the message again
      return;
    }
    addDiagnostics(messageBuilder.emittingMessage(encodedProbeId));
  }

  public void addBlocked(ProbeId probeId) {
    addDiagnostics(messageBuilder.blockedMessage(probeId));
  }

  public void addError(ProbeId probeId, Throwable ex) {
    addDiagnostics(messageBuilder.errorMessage(probeId, ex));
  }

  public void addError(ProbeId probeId, String message) {
    addDiagnostics(messageBuilder.errorMessage(probeId, message));
  }

  public void flush(String tags) {
    List<String> serializedDiagnostics = getSerializedDiagnostics();
    List<byte[]> batches = IntakeBatchHelper.createBatches(serializedDiagnostics);
    for (byte[] batch : batches) {
      if (useMultiPart) {
        diagnosticUploader.uploadAsMultipart(
            tags,
            new BatchUploader.MultiPartContent(batch, "event", "event.json", APPLICATION_JSON));
      } else {
        diagnosticUploader.upload(batch, tags);
      }
    }
  }

  private List<String> getSerializedDiagnostics() {
    List<ProbeStatus> diagnostics = getDiagnostics();
    List<String> serializedDiagnostics = new ArrayList<>();
    for (ProbeStatus message : diagnostics) {
      try {
        LOGGER.debug(
            "Sending probe status[{}] for probe id: {}",
            message.getDiagnostics().getStatus(),
            message.getDiagnostics().getProbeId().getId());
        serializedDiagnostics.add(PROBE_STATUS_ADAPTER.toJson(message));
      } catch (Exception e) {
        ExceptionHelper.logException(LOGGER, e, "Error during probe status serialization:");
      }
    }
    return serializedDiagnostics;
  }

  public HttpUrl getUrl() {
    return diagnosticUploader.getUrl();
  }

  public Map<String, String> getProbeStatuses() {
    Map<String, String> result = new HashMap<>();
    for (Map.Entry<String, TimedMessage> entry : probeStatuses.entrySet()) {
      result.put(entry.getKey(), entry.getValue().getMessage().toString());
    }
    return result;
  }

  List<ProbeStatus> getDiagnostics() {
    return getDiagnostics(Clock.systemDefaultZone());
  }

  List<ProbeStatus> getDiagnostics(Clock clock) {
    int missingCapacity = enqueueAllProbesStatusIfNeeded(clock);

    List<ProbeStatus> diagnostics = new ArrayList<>();
    queue.drainTo(diagnostics, batchSize);

    if (missingCapacity > 0) {
      // if queue was full before, try re-emit probe status now that there is free capacity
      enqueueAllProbesStatusIfNeeded(clock);
    }

    return diagnostics;
  }

  private int enqueueAllProbesStatusIfNeeded(Clock clock) {
    Instant now = Instant.now(clock);
    int missingCapacity = 0;
    for (TimedMessage entry : probeStatuses.values()) {
      if (shouldEmitAgain(now, entry.getLastEmit())) {
        if (!enqueueTimedMessage(entry, now)) {
          missingCapacity++;
        }
      }
    }
    return missingCapacity;
  }

  public void removeDiagnostics(ProbeId probeId) {
    probeStatuses.remove(probeId.getEncodedId());
  }

  private void addDiagnostics(ProbeStatus message) {
    if (isInstrumentTheWorld) {
      // drop diagnostic messages in Instrument-The-World mode
      return;
    }
    ProbeId probeId = message.getDiagnostics().getProbeId();
    TimedMessage current = probeStatuses.get(probeId.getId());
    if (current == null || shouldOverwrite(current.getMessage(), message)) {
      TimedMessage newMessage = new TimedMessage(message);
      probeStatuses.put(probeId.getEncodedId(), newMessage);
      enqueueTimedMessage(newMessage, Instant.now(Clock.systemDefaultZone()));
    }
  }

  private boolean enqueueTimedMessage(TimedMessage message, Instant now) {
    if (!queue.contains(message.getMessage())) {
      if (queue.offer(
          message.isAlreadySent()
              ? message.getMessage().withNewTimestamp(now)
              : message.getMessage())) {
        message.setLastEmit(now);
      } else {
        ratelimitedLogger.warn(
            SEND_TELEMETRY,
            "Diagnostic message queue is full. Dropping probe status[{}] for probe id: {}",
            message.getMessage().getDiagnostics().getStatus(),
            message.getMessage().getDiagnostics().getProbeId().getId());
        return false;
      }
    }
    return true;
  }

  private boolean shouldOverwrite(ProbeStatus current, ProbeStatus next) {
    return next.getDiagnostics().getStatus() == Status.ERROR
        || (current.getDiagnostics().getStatus() != next.getDiagnostics().getStatus())
        || (current.getDiagnostics().getProbeId().getVersion()
            < next.getDiagnostics().getProbeId().getVersion());
  }

  private boolean shouldEmitAgain(Instant now, Instant lastEmit) {
    return Duration.between(lastEmit, now).compareTo(interval) >= 1;
  }

  private static class TimedMessage {

    private final ProbeStatus message;
    private Instant lastEmit;

    private TimedMessage(ProbeStatus message) {
      // mark it as never sent before
      this.lastEmit = Instant.EPOCH;
      this.message = message;
    }

    public Instant getLastEmit() {
      return lastEmit;
    }

    public ProbeStatus getMessage() {
      return message;
    }

    public void setLastEmit(Instant instant) {
      lastEmit = instant;
    }

    public boolean isAlreadySent() {
      return !this.lastEmit.equals(Instant.EPOCH);
    }
  }
}
