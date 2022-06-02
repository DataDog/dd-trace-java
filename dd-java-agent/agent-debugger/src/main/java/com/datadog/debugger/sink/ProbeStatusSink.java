package com.datadog.debugger.sink;

import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.agent.ProbeStatus.Builder;
import com.datadog.debugger.agent.ProbeStatus.Status;
import com.datadog.debugger.util.ExceptionHelper;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collects probe status messages that needs to be sent to the backend */
public class ProbeStatusSink {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProbeStatusSink.class);
  private static final JsonAdapter<ProbeStatus> PROBE_STATUS_ADAPTER =
      MoshiHelper.createMoshiProbeStatus().adapter(ProbeStatus.class);

  private final Builder messageBuilder;
  private final Map<String, TimedMessage> probeStatuses = new ConcurrentHashMap<>();
  private final ArrayBlockingQueue<ProbeStatus> queue = new ArrayBlockingQueue<>(1000);
  private final Duration interval;
  private final int batchSize;
  private final boolean isInstrumentTheWorld;

  ProbeStatusSink(Config config) {
    this.messageBuilder = new Builder(config);
    this.interval = Duration.ofSeconds(config.getDebuggerDiagnosticsInterval());
    this.batchSize = config.getDebuggerUploadBatchSize();
    this.isInstrumentTheWorld = config.isDebuggerInstrumentTheWorld();
  }

  public void addReceived(String probeId) {
    addDiagnostics(messageBuilder.receivedMessage(probeId));
  }

  public void addInstalled(String probeId) {
    addDiagnostics(messageBuilder.installedMessage(probeId));
  }

  public void addBlocked(String probeId) {
    addDiagnostics(messageBuilder.blockedMessage(probeId));
  }

  public void addError(String probeId, Throwable ex) {
    addDiagnostics(messageBuilder.errorMessage(probeId, ex));
  }

  public void addError(String probeId, String message) {
    addDiagnostics(messageBuilder.errorMessage(probeId, message));
  }

  public List<String> getSerializedDiagnostics() {
    List<ProbeStatus> diagnostics = getDiagnostics();
    List<String> serializedDiagnostics = new ArrayList<>();
    for (ProbeStatus message : diagnostics) {
      try {
        serializedDiagnostics.add(PROBE_STATUS_ADAPTER.toJson(message));
      } catch (Exception e) {
        ExceptionHelper.logException(LOGGER, e, "Error during probe status serialization:");
      }
    }
    return serializedDiagnostics;
  }

  List<ProbeStatus> getDiagnostics() {
    return getDiagnostics(Clock.systemDefaultZone());
  }

  List<ProbeStatus> getDiagnostics(Clock clock) {
    Instant now = Instant.now(clock);
    for (TimedMessage entry : probeStatuses.values()) {
      if (shouldEmitAgain(now, entry.getLastEmit())) {
        if (!queue.contains(entry.getMessage())) {
          enqueueDiagnosticMessage(entry.getMessage());
        }
        entry.setLastEmit(Instant.now(Clock.systemDefaultZone()));
      }
    }
    List<ProbeStatus> diagnostics = new ArrayList<>();
    queue.drainTo(diagnostics, batchSize);
    return diagnostics;
  }

  public void removeDiagnostics(String probeId) {
    probeStatuses.remove(probeId);
  }

  private void addDiagnostics(ProbeStatus message) {
    if (isInstrumentTheWorld) {
      // drop diagnostic messages in Instrument-The-World mode
      return;
    }
    String probeId = message.getDiagnostics().getProbeId();
    TimedMessage current = probeStatuses.get(probeId);
    if (current == null || shouldOverwrite(current.getMessage(), message)) {
      probeStatuses.put(probeId, new TimedMessage(Instant.now(Clock.systemDefaultZone()), message));
      enqueueDiagnosticMessage(message);
    }
  }

  private boolean shouldOverwrite(ProbeStatus current, ProbeStatus next) {
    return next.getDiagnostics().getStatus() == Status.ERROR
        || (current.getDiagnostics().getStatus() != next.getDiagnostics().getStatus());
  }

  private void enqueueDiagnosticMessage(ProbeStatus message) {
    if (!queue.offer(message)) {
      queue.clear();
      for (TimedMessage entry : probeStatuses.values()) {
        if (!queue.contains(entry.getMessage())) {
          queue.offer(entry.getMessage());
        }
        entry.setLastEmit(Instant.now(Clock.systemDefaultZone()));
      }
    }
  }

  private boolean shouldEmitAgain(Instant now, Instant lastEmit) {
    return Duration.between(lastEmit, now).compareTo(interval) >= 1;
  }

  private static class TimedMessage {

    private final ProbeStatus message;
    private Instant lastEmit;

    private TimedMessage(Instant lastEmit, ProbeStatus message) {
      this.lastEmit = lastEmit;
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
  }
}
