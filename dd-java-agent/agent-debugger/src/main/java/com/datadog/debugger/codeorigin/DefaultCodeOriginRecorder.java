package com.datadog.debugger.codeorigin;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.CODE_ORIGIN;
import static java.lang.String.valueOf;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.FrameProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.CodeOriginRecorder;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultCodeOriginRecorder implements CodeOriginRecorder {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultCodeOriginRecorder.class);

  private final ConfigurationUpdater configurationUpdater;

  private final Map<String, CodeOriginProbe> fingerprints = new HashMap<>();

  private final Map<String, CodeOriginProbe> probes = new ConcurrentHashMap<>();
  private final Map<String, FrameProbe> frameProbes = new ConcurrentHashMap<>();

  private final int maxUserFrames;

  public DefaultCodeOriginRecorder(Config config, ConfigurationUpdater configurationUpdater) {
    this.configurationUpdater = configurationUpdater;
    maxUserFrames = config.getDebuggerCodeOriginMaxUserFrames();
  }

  @Override
  public String captureCodeOrigin(boolean entry) {
    StackTraceElement element = findPlaceInStack();
    String fingerprint = Fingerprinter.fingerprint(element);
    CodeOriginProbe probe;

    if (isAlreadyInstrumented(fingerprint)) {
      probe = fingerprints.get(fingerprint);
    } else {
      probe =
          createProbe(
              fingerprint,
              entry,
              Where.of(
                  element.getClassName(),
                  element.getMethodName(),
                  null,
                  valueOf(element.getLineNumber())));
    }

    return probe.getId();
  }

  @Override
  public String captureCodeOrigin(Method method, boolean entry) {
    CodeOriginProbe probe;

    String fingerPrint = method.toString();
    if (isAlreadyInstrumented(fingerPrint)) {
      probe = fingerprints.get(fingerPrint);
    } else {
      probe = createProbe(fingerPrint, entry, Where.of(method));
    }

    return probe.getId();
  }

  private List<StackTraceElement> stackTrace() {
    return StackWalkerFactory.INSTANCE.walk(
        stream ->
            stream
                .filter(element -> !DebuggerContext.isClassNameExcluded(element.getClassName()))
                .collect(Collectors.toList()));
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
                    valueOf(frame.getLineNumber()));
            FrameProbe probe =
                new FrameProbe(
                    new ProbeId(UUID.randomUUID().toString(), 0), where, MethodLocation.DEFAULT);

            frameProbes.put(probe.getId(), probe);
            installProbes();
          }
        });
  }

  private CodeOriginProbe createProbe(String fingerPrint, boolean entry, Where where) {
    CodeOriginProbe probe;
    AgentSpan span = AgentTracer.activeSpan();

    probe =
        new CodeOriginProbe(
            new ProbeId(UUID.randomUUID().toString(), 0), entry, where, maxUserFrames);
    addFingerprint(fingerPrint, probe);
    probes.put(probe.getId(), probe);

    installProbes();
    // committing here manually so that first run probe encounters decorate the span until the
    // instrumentation gets installed
    if (span != null) {
      System.out.println("****** DefaultCodeOriginRecorder.createProbe span = " + span);
      //      probe.commit(
      //          CapturedContext.EMPTY_CONTEXT, CapturedContext.EMPTY_CONTEXT,
      // Collections.emptyList());
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

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.containsKey(fingerprint);
  }

  void addFingerprint(String fingerprint, CodeOriginProbe probe) {
    fingerprints.putIfAbsent(fingerprint, probe);
  }

  public void installProbes() {
    AgentTaskScheduler.INSTANCE.execute(
        () -> configurationUpdater.accept(CODE_ORIGIN, getProbes()));
  }

  public CodeOriginProbe getProbe(String probeId) {
    return probes.get(probeId);
  }

  public Collection<ProbeDefinition> getProbes() {
    List<ProbeDefinition> list = new ArrayList<>();
    list.addAll(probes.values());
    list.addAll(frameProbes.values());
    return list;
  }
}
