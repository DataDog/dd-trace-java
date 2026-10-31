package datadog.trace.common.writer;

import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BUFFER_OVERFLOW;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BUFFER_OVERFLOW_SAMPLED_OUT;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TraceProcessingWorkerStateMachineTest {

  private enum Command {
    PUBLISH_PRIMARY,
    PUBLISH_SECONDARY,
    FLUSH
  }

  private enum Finding {
    SECONDARY_QUEUE_BYPASSES_FLUSH,
    PRIMARY_QUEUE_BYPASSES_FLUSH,
    MIXED_ACCEPTED_WORK_BYPASSES_FLUSH,
    WORK_CROSSES_FLUSH_FROM_THE_FUTURE,
    FLUSH_DID_NOT_COMPLETE,
    FLUSH_OBSERVATION_MISSING
  }

  @Test
  void generatedCommandSequencesRespectFlushBarriers() throws Exception {
    Map<Finding, Counterexample> distinctFindings = new EnumMap<>(Finding.class);

    for (List<Command> commands : generatedCommandSequences(4)) {
      for (Counterexample counterexample : executeFlushStateMachine(commands)) {
        distinctFindings.putIfAbsent(counterexample.finding, counterexample);
      }
    }

    assertTrue(
        distinctFindings.isEmpty(),
        () ->
            "flush state machine found distinct counterexamples (shortest schedule per cause): "
                + distinctFindings.values());
  }

  @Test
  void generatedSaturationSequencesReportExplicitDrops() {
    for (List<Command> commands : generatedPublishSequences(5)) {
      Map<Command, Integer> acceptedByLane = new EnumMap<>(Command.class);
      acceptedByLane.put(Command.PUBLISH_PRIMARY, 0);
      acceptedByLane.put(Command.PUBLISH_SECONDARY, 0);

      try (TraceProcessingWorker worker =
          new TraceProcessingWorker(
              2,
              mock(HealthMetrics.class),
              mock(PayloadDispatcher.class),
              () -> false,
              FAST_LANE,
              100,
              SECONDS,
              null)) {
        for (Command command : commands) {
          int accepted = acceptedByLane.get(command);
          PublishResult expected = expectedSaturationResult(command, accepted);
          PublishResult actual =
              publish(worker, command, Collections.singletonList(mock(DDSpan.class)));
          assertEquals(expected, actual, "schedule=" + commands);
          if (actual == ENQUEUED_FOR_SERIALIZATION) {
            acceptedByLane.put(command, accepted + 1);
          }
        }
      }
    }
  }

  private static List<Counterexample> executeFlushStateMachine(List<Command> commands)
      throws Exception {
    Object observationsLock = new Object();
    Map<List<DDSpan>, Integer> traceIds = new IdentityHashMap<>();
    Set<Integer> serialized = new LinkedHashSet<>();
    List<Set<Integer>> serializedAtFlush = new ArrayList<>();
    PayloadDispatcher dispatcher = mock(PayloadDispatcher.class);
    doAnswer(
            invocation -> {
              List<DDSpan> trace = invocation.getArgument(0);
              synchronized (observationsLock) {
                serialized.add(traceIds.get(trace));
              }
              return null;
            })
        .when(dispatcher)
        .addTrace(any());
    doAnswer(
            invocation -> {
              synchronized (observationsLock) {
                serializedAtFlush.add(new LinkedHashSet<>(serialized));
              }
              return null;
            })
        .when(dispatcher)
        .flush();

    Set<Integer> accepted = new LinkedHashSet<>();
    Set<Integer> primaryIds = new LinkedHashSet<>();
    Set<Integer> secondaryIds = new LinkedHashSet<>();
    List<Set<Integer>> expectedAtFlush = new ArrayList<>();
    List<Future<Boolean>> flushes = new ArrayList<>();
    ExecutorService flushExecutor = Executors.newCachedThreadPool();
    List<Counterexample> counterexamples = new ArrayList<>();
    try (TraceProcessingWorker worker =
        new TraceProcessingWorker(
            16,
            mock(HealthMetrics.class),
            dispatcher,
            () -> false,
            FAST_LANE,
            100,
            SECONDS,
            null)) {
      int primaryQueueSize = 0;
      int nextTraceId = 0;
      for (Command command : commands) {
        if (command == Command.FLUSH) {
          expectedAtFlush.add(new LinkedHashSet<>(accepted));
          flushes.add(flushExecutor.submit(() -> worker.flush(3, SECONDS)));
          primaryQueueSize++;
          awaitPrimaryQueueSize(worker, primaryQueueSize);
          continue;
        }

        int id = nextTraceId++;
        List<DDSpan> trace = Collections.singletonList(mock(DDSpan.class));
        traceIds.put(trace, id);
        PublishResult result = publish(worker, command, trace);
        if (result == ENQUEUED_FOR_SERIALIZATION) {
          accepted.add(id);
          if (command == Command.PUBLISH_PRIMARY) {
            primaryIds.add(id);
            primaryQueueSize++;
          } else {
            secondaryIds.add(id);
          }
        }
      }

      assertFalse(flushes.isEmpty(), "generated schedule must contain a flush");
      worker.start();
      for (int barrier = 0; barrier < flushes.size(); barrier++) {
        if (!flushes.get(barrier).get(3, SECONDS)) {
          counterexamples.add(
              new Counterexample(
                  Finding.FLUSH_DID_NOT_COMPLETE,
                  commands,
                  barrier,
                  expectedAtFlush.get(barrier),
                  Collections.emptySet()));
        }
      }

      List<Set<Integer>> observations;
      synchronized (observationsLock) {
        observations = new ArrayList<>(serializedAtFlush);
      }
      for (int barrier = 0; barrier < expectedAtFlush.size(); barrier++) {
        Set<Integer> expected = expectedAtFlush.get(barrier);
        if (barrier >= observations.size()) {
          counterexamples.add(
              new Counterexample(
                  Finding.FLUSH_OBSERVATION_MISSING,
                  commands,
                  barrier,
                  expected,
                  Collections.emptySet()));
          continue;
        }
        Set<Integer> observed = observations.get(barrier);
        if (!expected.equals(observed)) {
          counterexamples.add(
              new Counterexample(
                  classify(expected, observed, primaryIds, secondaryIds),
                  commands,
                  barrier,
                  expected,
                  observed));
        }
      }
    } finally {
      flushExecutor.shutdownNow();
    }
    return counterexamples;
  }

  private static Finding classify(
      Set<Integer> expected,
      Set<Integer> observed,
      Set<Integer> primaryIds,
      Set<Integer> secondaryIds) {
    Set<Integer> unexpected = new LinkedHashSet<>(observed);
    unexpected.removeAll(expected);
    if (!unexpected.isEmpty()) {
      return Finding.WORK_CROSSES_FLUSH_FROM_THE_FUTURE;
    }

    Set<Integer> missing = new LinkedHashSet<>(expected);
    missing.removeAll(observed);
    if (secondaryIds.containsAll(missing)) {
      return Finding.SECONDARY_QUEUE_BYPASSES_FLUSH;
    }
    if (primaryIds.containsAll(missing)) {
      return Finding.PRIMARY_QUEUE_BYPASSES_FLUSH;
    }
    return Finding.MIXED_ACCEPTED_WORK_BYPASSES_FLUSH;
  }

  private static PublishResult publish(
      TraceProcessingWorker worker, Command command, List<DDSpan> trace) {
    int priority = command == Command.PUBLISH_PRIMARY ? USER_KEEP : USER_DROP;
    return worker.publish(trace.get(0), priority, trace);
  }

  private static PublishResult expectedSaturationResult(Command command, int accepted) {
    if (accepted < 2) {
      return ENQUEUED_FOR_SERIALIZATION;
    }
    return command == Command.PUBLISH_PRIMARY
        ? DROPPED_BUFFER_OVERFLOW
        : DROPPED_BUFFER_OVERFLOW_SAMPLED_OUT;
  }

  private static void awaitPrimaryQueueSize(TraceProcessingWorker worker, int expectedSize) {
    long deadline = System.nanoTime() + SECONDS.toNanos(3);
    while (worker.getPrimaryQueue().size() != expectedSize && System.nanoTime() < deadline) {
      Thread.yield();
    }
    assertEquals(expectedSize, worker.getPrimaryQueue().size(), "flush marker was not enqueued");
  }

  private static List<List<Command>> generatedCommandSequences(int maximumLength) {
    List<List<Command>> sequences = generateSequences(maximumLength, EnumSet.allOf(Command.class));
    sequences.removeIf(sequence -> !sequence.contains(Command.FLUSH));
    return sequences;
  }

  private static List<List<Command>> generatedPublishSequences(int maximumLength) {
    return generateSequences(
        maximumLength, EnumSet.of(Command.PUBLISH_PRIMARY, Command.PUBLISH_SECONDARY));
  }

  private static List<List<Command>> generateSequences(int maximumLength, Set<Command> alphabet) {
    List<List<Command>> sequences = new ArrayList<>();
    for (int length = 1; length <= maximumLength; length++) {
      generateSequences(length, alphabet, new ArrayList<>(), sequences);
    }
    return sequences;
  }

  private static void generateSequences(
      int remaining, Set<Command> alphabet, List<Command> prefix, List<List<Command>> sequences) {
    if (remaining == 0) {
      sequences.add(new ArrayList<>(prefix));
      return;
    }
    for (Command command : alphabet) {
      prefix.add(command);
      generateSequences(remaining - 1, alphabet, prefix, sequences);
      prefix.remove(prefix.size() - 1);
    }
  }

  private static final class Counterexample {
    private final Finding finding;
    private final List<Command> commands;
    private final int barrier;
    private final Set<Integer> expected;
    private final Set<Integer> observed;

    private Counterexample(
        Finding finding,
        List<Command> commands,
        int barrier,
        Set<Integer> expected,
        Set<Integer> observed) {
      this.finding = finding;
      this.commands = new ArrayList<>(commands);
      this.barrier = barrier;
      this.expected = new LinkedHashSet<>(expected);
      this.observed = new LinkedHashSet<>(observed);
    }

    @Override
    public String toString() {
      return finding
          + ": "
          + commands
          + "; barrier="
          + barrier
          + ", expected="
          + expected
          + ", observed="
          + observed;
    }
  }
}
