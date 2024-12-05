package com.datadog.debugger.codeorigin;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.CODE_ORIGIN;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.Where;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultCodeOriginRecorder implements CodeOriginRecorder {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultCodeOriginRecorder.class);

  private final ConfigurationUpdater configurationUpdater;

  private final Map<String, CodeOriginProbe> probesByFingerprint = new HashMap<>();

  private final Map<String, CodeOriginProbe> probes = new ConcurrentHashMap<>();

  private final int maxUserFrames;

  public DefaultCodeOriginRecorder(Config config, ConfigurationUpdater configurationUpdater) {
    this.configurationUpdater = configurationUpdater;
    maxUserFrames = config.getDebuggerCodeOriginMaxUserFrames();
  }

  @Override
  public String captureCodeOrigin(boolean entry) {
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

  private CodeOriginProbe createProbe(String fingerPrint, boolean entry, Where where) {
    CodeOriginProbe probe;
    AgentSpan span = AgentTracer.activeSpan();

    probe =
        new CodeOriginProbe(
            new ProbeId(UUID.randomUUID().toString(), 0), entry, where, maxUserFrames);
    addFingerprint(fingerPrint, probe);

    installProbe(probe);
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

  public void installProbe(CodeOriginProbe probe) {
    CodeOriginProbe installed = probes.putIfAbsent(probe.getId(), probe);
    if (installed == null) {
      AgentTaskScheduler.INSTANCE.execute(
          () -> configurationUpdater.accept(CODE_ORIGIN, getProbes()));
    }
  }

  public CodeOriginProbe getProbe(String probeId) {
    return probes.get(probeId);
  }

  public Collection<CodeOriginProbe> getProbes() {
    return probes.values();
  }
}
