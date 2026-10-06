package datadog.trace.agent.test.coverage;

import static datadog.trace.agent.test.coverage.ContextCoverage.inScenario;
import static java.util.Objects.requireNonNull;

import datadog.context.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Test-owned task identities link submission evidence to execution without carrying Context. */
final class HandoffTracker {
  private static final int MAX_HANDOFFS = 1000;
  private static final ThreadLocal<String> CURRENT_HANDOFF = new ThreadLocal<>();
  private static final AtomicLong NEXT_HANDOFF = new AtomicLong();
  private final List<Handoff> handoffs = new ArrayList<>();
  private final List<Map<String, String>> experiments = new ArrayList<>();
  private boolean sealed;

  Runnable observe(String boundary, String scenario, Runnable work) {
    requireName(boundary);
    requireName(scenario);
    requireNonNull(work, "work");
    Handoff handoff = new Handoff(boundary, scenario);
    synchronized (this) {
      requireOpen();
      if (handoffs.size() >= MAX_HANDOFFS) {
        throw new IllegalStateException("Prototype handoff limit reached");
      }
      handoff.handoffId = "h" + NEXT_HANDOFF.incrementAndGet();
      handoffs.add(handoff);
    }
    return () -> {
      boolean entryWithContext = Context.current() != Context.root();
      synchronized (this) {
        if (!sealed) {
          if (handoff.started) {
            throw new IllegalStateException("Handoff wrappers are single-use");
          }
          handoff.started = true;
          handoff.entryWithContext = entryWithContext;
          handoff.workerThreadId = Thread.currentThread().getId();
        }
      }
      String previous = CURRENT_HANDOFF.get();
      CURRENT_HANDOFF.set(handoff.handoffId);
      boolean success = false;
      try {
        inScenario(scenario, work);
        success = true;
      } finally {
        if (previous == null) {
          CURRENT_HANDOFF.remove();
        } else {
          CURRENT_HANDOFF.set(previous);
        }
        synchronized (this) {
          if (!sealed) {
            handoff.completed = true;
            handoff.failed = !success;
          }
        }
      }
    };
  }

  static String currentId() {
    String id = CURRENT_HANDOFF.get();
    return id == null ? "" : id;
  }

  synchronized void compare(String name, String baseline, String intervention) {
    requireOpen();
    requireName(name);
    requireName(baseline);
    requireName(intervention);
    if (baseline.equals(intervention)) {
      throw new IllegalArgumentException("Baseline and intervention must be different scenarios");
    }
    if (experiments.size() >= 100) {
      throw new IllegalStateException("Prototype experiment limit reached");
    }
    Map<String, String> experiment = new LinkedHashMap<>();
    experiment.put("name", name);
    experiment.put("baselineScenario", baseline);
    experiment.put("interventionScenario", intervention);
    experiments.add(experiment);
  }

  synchronized void seal() {
    sealed = true;
  }

  synchronized void addTo(Map<String, Object> report) {
    List<Map<String, Object>> snapshots = new ArrayList<>();
    for (Handoff handoff : handoffs) {
      snapshots.add(handoff.snapshot());
    }
    report.put("handoffs", snapshots);
    report.put("experiments", new ArrayList<>(experiments));
  }

  private void requireOpen() {
    if (sealed) {
      throw new IllegalStateException("Context coverage collection has closed");
    }
  }

  private static void requireName(String value) {
    requireNonNull(value, "name");
    if (value.isEmpty() || value.length() > 512) {
      throw new IllegalArgumentException("Names must contain 1 to 512 characters");
    }
  }

  private static final class Handoff {
    String handoffId;
    final String boundary;
    final String scenario;
    final boolean submittedWithContext;
    final long submissionThreadId;
    final List<String> submissionStack = new ArrayList<>();
    boolean started;
    boolean entryWithContext;
    boolean completed;
    boolean failed;
    long workerThreadId;

    Map<String, Object> snapshot() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("handoffId", handoffId);
      result.put("boundary", boundary);
      result.put("scenario", scenario);
      result.put("submittedWithContext", submittedWithContext);
      result.put("submissionThreadId", submissionThreadId);
      result.put("submissionStack", new ArrayList<>(submissionStack));
      result.put("started", started);
      result.put("entryWithContext", entryWithContext);
      result.put("completed", completed);
      result.put("failed", failed);
      result.put("workerThreadId", workerThreadId);
      return result;
    }

    Handoff(String boundary, String scenario) {
      this.boundary = boundary;
      this.scenario = scenario;
      submittedWithContext = Context.current() != Context.root();
      submissionThreadId = Thread.currentThread().getId();
      for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
        String type = frame.getClassName();
        if (!type.equals(Thread.class.getName())
            && !type.equals(ContextCoverage.class.getName())
            && !type.equals(HandoffTracker.class.getName())
            && !type.startsWith(HandoffTracker.class.getName() + "$")) {
          submissionStack.add(frame.toString());
          if (submissionStack.size() == 12) {
            break;
          }
        }
      }
    }
  }
}
