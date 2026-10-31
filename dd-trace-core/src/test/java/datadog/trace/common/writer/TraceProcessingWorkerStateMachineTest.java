package datadog.trace.common.writer;

import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TraceProcessingWorkerStateMachineTest {

  private enum Command {
    PUBLISH_PRIMARY,
    PUBLISH_SECONDARY
  }

  @Test
  void generatedCommandSequencesRespectFlushBarrier() throws Exception {
    Counterexample counterexample = null;

    for (List<Command> commands : generatedPublishPrefixes(4)) {
      Counterexample candidate = execute(commands);
      if (candidate != null) {
        counterexample = candidate;
        break;
      }
    }

    assertNull(
        counterexample,
        "flush acknowledged before all previously accepted traces were serialized; minimal schedule: "
            + counterexample);
  }

  private static Counterexample execute(List<Command> commands) throws Exception {
    Object observationsLock = new Object();
    Map<List<DDSpan>, Integer> traceIds = new IdentityHashMap<>();
    Set<Integer> serialized = new LinkedHashSet<>();
    AtomicReference<Set<Integer>> serializedAtFlush = new AtomicReference<>();
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
                serializedAtFlush.set(new LinkedHashSet<>(serialized));
              }
              return null;
            })
        .when(dispatcher)
        .flush();

    Set<Integer> accepted = new LinkedHashSet<>();
    ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
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
      int primaryEvents = 0;
      for (int id = 0; id < commands.size(); id++) {
        Command command = commands.get(id);
        List<DDSpan> trace = Collections.singletonList(mock(DDSpan.class));
        traceIds.put(trace, id);
        int priority;
        if (command == Command.PUBLISH_PRIMARY) {
          priority = USER_KEEP;
          primaryEvents++;
        } else {
          priority = USER_DROP;
        }

        PublishResult result = worker.publish(trace.get(0), priority, trace);
        if (result == ENQUEUED_FOR_SERIALIZATION) {
          accepted.add(id);
        }
      }

      Future<Boolean> flush = flushExecutor.submit(() -> worker.flush(3, SECONDS));
      awaitPrimaryQueueSize(worker, primaryEvents + 1);
      worker.start();

      assertTrue(flush.get(3, SECONDS), "generated flush did not complete");
      Set<Integer> observed = serializedAtFlush.get();
      if (!accepted.equals(observed)) {
        return new Counterexample(commands, accepted, observed);
      }
      return null;
    } finally {
      flushExecutor.shutdownNow();
    }
  }

  private static void awaitPrimaryQueueSize(TraceProcessingWorker worker, int expectedSize) {
    long deadline = System.nanoTime() + SECONDS.toNanos(3);
    while (worker.getPrimaryQueue().size() != expectedSize && System.nanoTime() < deadline) {
      Thread.yield();
    }
    assertEquals(expectedSize, worker.getPrimaryQueue().size(), "flush marker was not enqueued");
  }

  private static List<List<Command>> generatedPublishPrefixes(int maximumLength) {
    List<List<Command>> sequences = new ArrayList<>();
    for (int length = 1; length <= maximumLength; length++) {
      generateSequences(length, new ArrayList<>(), sequences);
    }
    return sequences;
  }

  private static void generateSequences(
      int remaining, List<Command> prefix, List<List<Command>> sequences) {
    if (remaining == 0) {
      sequences.add(new ArrayList<>(prefix));
      return;
    }
    for (Command command : EnumSet.allOf(Command.class)) {
      prefix.add(command);
      generateSequences(remaining - 1, prefix, sequences);
      prefix.remove(prefix.size() - 1);
    }
  }

  private static final class Counterexample {
    private final List<Command> commands;
    private final Set<Integer> accepted;
    private final Set<Integer> serializedAtFlush;

    private Counterexample(
        List<Command> commands, Set<Integer> accepted, Set<Integer> serializedAtFlush) {
      this.commands = new ArrayList<>(commands);
      this.accepted = new LinkedHashSet<>(accepted);
      this.serializedAtFlush = new LinkedHashSet<>(serializedAtFlush);
    }

    @Override
    public String toString() {
      return commands
          + " -> FLUSH; accepted="
          + accepted
          + ", serializedAtFlush="
          + serializedAtFlush;
    }
  }
}
