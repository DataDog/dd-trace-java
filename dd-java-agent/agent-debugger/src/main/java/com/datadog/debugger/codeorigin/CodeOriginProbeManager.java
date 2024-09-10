package com.datadog.debugger.codeorigin;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.SPAN_DEBUG;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackWalkerFactory;
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

  private final Map<String, CodeOriginProbe> fingerprints = new HashMap<>();

  private final Map<String, CodeOriginProbe> probes = new ConcurrentHashMap<>();

  private final ClassNameFiltering classNameFiltering;

  private AgentTaskScheduler taskScheduler = AgentTaskScheduler.INSTANCE;

  private final ConfigurationUpdater configurationUpdater;

  public CodeOriginProbeManager(
      ConfigurationUpdater configurationUpdater, ClassNameFiltering classNameFiltering) {
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
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
          Where.convertLineToMethod(
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
      if (span != null && !probe.isEntrySpanProbe()) {
        span.getLocalRootSpan().setTag(probe.getId(), span);
      }
    }

    return probe.getId();
  }

  public String installProbe(CodeOriginProbe probe) {
    CodeOriginProbe installed = probes.putIfAbsent(probe.getId(), probe);
    if (installed == null) {
      taskScheduler.execute(() -> configurationUpdater.accept(SPAN_DEBUG, getProbes()));
      return probe.getId();
    }
    return installed.getId();
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.containsKey(fingerprint);
  }

  private StackTraceElement findPlaceInStack() {
    return StackWalkerFactory.INSTANCE.walk(
        stream -> {
          List<StackTraceElement> list = stream.collect(Collectors.toList());
          list =
              list.stream()
                  .filter(element -> !classNameFiltering.isExcluded(element.getClassName()))
                  .collect(Collectors.toList());

          return list.stream().findFirst().orElse(null);
        });
  }
}
