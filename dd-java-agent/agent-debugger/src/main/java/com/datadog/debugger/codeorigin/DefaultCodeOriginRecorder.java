package com.datadog.debugger.codeorigin;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.CODE_ORIGIN;
import static datadog.trace.api.DDTags.*;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.LogProbe.Builder;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.CodeOriginRecorder;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultCodeOriginRecorder implements CodeOriginRecorder {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultCodeOriginRecorder.class);

  private final ConfigurationUpdater configurationUpdater;

  private final Map<String, CodeOriginProbe> probesByFingerprint = new HashMap<>();

  private final Map<String, CodeOriginProbe> probes = new ConcurrentHashMap<>();
  private final Map<String, LogProbe> logProbes = new ConcurrentHashMap<>();

  private final int maxUserFrames;

  private AgentTaskScheduler scheduler;

  public DefaultCodeOriginRecorder(Config config, ConfigurationUpdater configurationUpdater) {
    this(config, configurationUpdater, AgentTaskScheduler.get());
  }

  public DefaultCodeOriginRecorder(
      Config config, ConfigurationUpdater configurationUpdater, AgentTaskScheduler scheduler) {
    this.configurationUpdater = configurationUpdater;
    maxUserFrames = config.getDebuggerCodeOriginMaxUserFrames();
    this.scheduler = scheduler;
  }

  @Override
  public String captureCodeOrigin(boolean entry) {
    if (!entry) {
      LOG.debug("Not capturing code origin for exit");
      return null;
    }
    StackTraceElement element = findPlaceInStack();
    String fingerprint = Fingerprinter.fingerprint(element);
    CodeOriginProbe probe = probesByFingerprint.get(fingerprint);
    if (probe == null) {
      Where where =
          Where.of(
              element.getClassName(),
              element.getMethodName(),
              null,
              String.valueOf(element.getLineNumber()));
      probe = createProbe(fingerprint, entry, where);

      LOG.debug("Creating probe for location {}", where);
    }
    return probe.getId();
  }

  @Override
  public String captureCodeOrigin(Method method, boolean entry) {
    String fingerprint = method.toString();
    CodeOriginProbe probe = probesByFingerprint.get(fingerprint);
    if (probe == null) {
      probe = createProbe(fingerprint, entry, Where.of(method));
      LOG.debug("Creating probe for method {}", fingerprint);
    }
    return probe.getId();
  }

  public void registerLogProbe(CodeOriginProbe probe) {
    logProbes.computeIfAbsent(
        probe.getId(),
        key ->
            new Builder()
                .language(probe.getLanguage())
                .probeId(ProbeId.newId())
                .where(probe.getWhere())
                .evaluateAt(probe.getEvaluateAt())
                .captureSnapshot(true)
                .tags("session_id:*")
                .snapshotProcessor(new CodeOriginSnapshotConsumer(probe.entrySpanProbe()))
                .build());
  }

  private CodeOriginProbe createProbe(String fingerPrint, boolean entry, Where where) {
    CodeOriginProbe probe;
    AgentSpan span = AgentTracer.activeSpan();

    probe = new CodeOriginProbe(ProbeId.newId(), entry, where);
    addFingerprint(fingerPrint, probe);
    CodeOriginProbe installed = probes.putIfAbsent(probe.getId(), probe);

    // i think this check is unnecessary at this point time but leaving for now to be safe
    if (installed == null) {
      if (Config.get().isDistributedDebuggerEnabled()) {
        registerLogProbe(probe);
      }
      installProbes();
    }
    // committing here manually so that first run probe encounters decorate the span until the
    // instrumentation gets installed
    if (span != null) {
      probe.commit(
          CapturedContext.EMPTY_CONTEXT, CapturedContext.EMPTY_CONTEXT, Collections.emptyList());
    }
    return probe;
  }

  private StackTraceElement findPlaceInStack() {
    return StackWalkerFactory.INSTANCE.walk(
        stream ->
            stream
                .filter(element -> !DebuggerContext.isClassNameExcluded(element.getClassName()))
                .findFirst()
                .orElse(null));
  }

  void addFingerprint(String fingerprint, CodeOriginProbe probe) {
    probesByFingerprint.putIfAbsent(fingerprint, probe);
  }

  public void installProbes() {
    scheduler.execute(() -> configurationUpdater.accept(CODE_ORIGIN, getProbes()));
  }

  public CodeOriginProbe getProbe(String probeId) {
    return probes.get(probeId);
  }

  public List<ProbeDefinition> getProbes() {
    return Stream.of(probes.values(), logProbes.values())
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  private static class CodeOriginSnapshotConsumer implements Consumer<Snapshot> {
    private final boolean entrySpanProbe;

    public CodeOriginSnapshotConsumer(boolean entrySpanProbe) {
      this.entrySpanProbe = entrySpanProbe;
    }

    @Override
    public void accept(Snapshot snapshot) {
      AgentSpan span = AgentTracer.get().activeSpan();
      span.setTag(DD_CODE_ORIGIN_FRAME_SNAPSHOT_ID, snapshot.getId());
      if (entrySpanProbe) {
        span.getLocalRootSpan().setTag(DD_CODE_ORIGIN_FRAME_SNAPSHOT_ID, snapshot.getId());
      }
    }
  }
}
