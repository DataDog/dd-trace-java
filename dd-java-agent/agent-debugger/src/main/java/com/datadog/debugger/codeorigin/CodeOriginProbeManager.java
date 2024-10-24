package com.datadog.debugger.codeorigin;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.CODE_ORIGIN;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.Where;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeOriginProbeManager {
  private static final Logger LOG = LoggerFactory.getLogger(CodeOriginProbeManager.class);

  private final Map<String, CodeOriginProbe> fingerprints = new HashMap<>();

  private final Map<String, CodeOriginProbe> probes = new ConcurrentHashMap<>();

  private AgentTaskScheduler taskScheduler = AgentTaskScheduler.INSTANCE;

  private final ConfigurationUpdater configurationUpdater;

  public CodeOriginProbeManager(ConfigurationUpdater configurationUpdater) {
    this.configurationUpdater = configurationUpdater;
  }

  public Collection<CodeOriginProbe> getProbes() {
    return probes.values();
  }

  void addFingerprint(String fingerprint, CodeOriginProbe probe) {
    fingerprints.putIfAbsent(fingerprint, probe);
  }

  public String createProbeForFrame(String signature) {
    StackTraceElement element = findPlaceInStack();
    String fingerprint = Fingerprinter.fingerprint(element);
    if (fingerprint == null) {
      LOG.debug("Unable to fingerprint stack trace");
      return null;
    }
    CodeOriginProbe probe;

    AgentSpan span = AgentTracer.activeSpan();
    if (!isAlreadyInstrumented(fingerprint)) {
      Where where =
          Where.of(
              element.getClassName(),
              element.getMethodName(),
              signature,
              String.valueOf(element.getLineNumber()));

      probe =
          new CodeOriginProbe(
              new ProbeId(UUID.randomUUID().toString(), 0), where.getSignature(), where, this);
      addFingerprint(fingerprint, probe);

      installProbe(probe);
      if (span != null) {
        //  committing here manually so that first run probe encounters decorate the span until the
        // instrumentation gets installed
        probe.commit(
            CapturedContext.EMPTY_CONTEXT, CapturedContext.EMPTY_CONTEXT, Collections.emptyList());
      }

    } else {
      probe = fingerprints.get(fingerprint);
    }

    return probe.getId();
  }

  public String installProbe(CodeOriginProbe probe) {
    CodeOriginProbe installed = probes.putIfAbsent(probe.getId(), probe);
    if (installed == null) {
      taskScheduler.execute(() -> configurationUpdater.accept(CODE_ORIGIN, getProbes()));
      return probe.getId();
    }
    return installed.getId();
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.containsKey(fingerprint);
  }

  private StackTraceElement findPlaceInStack() {
    return StackWalkerFactory.INSTANCE.walk(
        stream ->
            stream
                .filter(element -> !DebuggerContext.isClassNameExcluded(element.getClassName()))
                .findFirst()
                .orElse(null));
  }
}
