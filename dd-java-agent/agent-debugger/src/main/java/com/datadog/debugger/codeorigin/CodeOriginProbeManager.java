package com.datadog.debugger.codeorigin;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.CODE_ORIGIN;
import static java.lang.String.valueOf;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.FrameProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeOriginProbeManager {
  private static final Logger LOG = LoggerFactory.getLogger(CodeOriginProbeManager.class);

  private final Map<String, ProbeDefinition> fingerprints = new HashMap<>();

  private final Map<String, CodeOriginProbe> probes = new ConcurrentHashMap<>();

  private final Map<String, ProbeDefinition> frameProbes = new ConcurrentHashMap<>();

  private final ClassNameFiltering classNameFiltering;

  private AgentTaskScheduler taskScheduler = AgentTaskScheduler.INSTANCE;

  private final ConfigurationUpdater configurationUpdater;

  public CodeOriginProbeManager(
      ConfigurationUpdater configurationUpdater, ClassNameFiltering classNameFiltering) {
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
  }

  public void createFrameProbes() {
    List<StackTraceElement> stackTrace = stackTrace();

    stackTrace.forEach(
        frame -> {
          String fingerprint = Fingerprinter.fingerprint(frame);
          if (!frameProbes.containsKey(fingerprint)) {
            Where where =
                Where.of(
                    frame.getClassName(),
                    frame.getMethodName(),
                    null,
                    frame.getFileName(),
                    valueOf(frame.getLineNumber()));
            frameProbes.put(
                fingerprint,
                new FrameProbe(
                    new ProbeId(UUID.randomUUID().toString(), 0), where, MethodLocation.DEFAULT));
          }
        });
  }

  public Collection<CodeOriginProbe> getProbes() {
    return probes.values();
  }

  void addFingerprint(String fingerprint, CodeOriginProbe probe) {
    fingerprints.putIfAbsent(fingerprint, probe);
  }

  public ClassNameFiltering getClassNameFiltering() {
    return classNameFiltering;
  }

  public String createProbe(String identifier, boolean entry) {
    CodeOriginProbe probe;

    AgentSpan span = AgentTracer.activeSpan();
    String fingerprint = identifier != null ? identifier : span.getResourceName().toString();
    if (!isAlreadyInstrumented(fingerprint)) {
      StackTraceElement element = findPlaceInStack();
      Where where =
          Where.of(
              element.getClassName(),
              element.getMethodName(),
              fingerprint,
              valueOf(element.getLineNumber()));

      probe =
          new CodeOriginProbe(
              new ProbeId(UUID.randomUUID().toString(), 0),
              entry,
              where.getSignature(),
              where,
              this);
      addFingerprint(fingerprint, probe);

      if (span != null) {
        // committing here manually so that first run probe encounters decorate the span until the
        // instrumentation gets installed
        probe.commit(
            CapturedContext.EMPTY_CONTEXT, CapturedContext.EMPTY_CONTEXT, Collections.emptyList());
      }
      installProbe(probe);

    } else {
      probe = (CodeOriginProbe) fingerprints.get(fingerprint);
    }

    return probe.getId();
  }

  public String installProbe(CodeOriginProbe probe) {
    CodeOriginProbe installed = probes.putIfAbsent(probe.getId(), probe);
    if (installed == null) {
      List<ProbeDefinition> definitions = new ArrayList<>(frameProbes.values());
      definitions.addAll(probes.values());

      taskScheduler.execute(() -> configurationUpdater.accept(CODE_ORIGIN, definitions));
      return probe.getId();
    }
    return installed.getId();
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.containsKey(fingerprint);
  }

  private List<StackTraceElement> stackTrace() {
    return StackWalkerFactory.INSTANCE.walk(
        stream ->
            stream
                .filter(element -> !classNameFiltering.isExcluded(element.getClassName()))
                .collect(Collectors.toList()));
  }

  private StackTraceElement findPlaceInStack() {
    return StackWalkerFactory.INSTANCE.walk(
        stream ->
            stream
                .filter(element -> !classNameFiltering.isExcluded(element.getClassName()))
                .findFirst()
                .orElse(null));
  }
}
