package datadog.trace.agent.test.checkpoints

import datadog.trace.api.Checkpointer
import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TimelineCheckpointer implements Checkpointer {

  private final ConcurrentHashMap<DDId, List<Event>> spanEvents = new ConcurrentHashMap<>()
  private final ConcurrentHashMap<Thread, List<Event>> threadEvents = new ConcurrentHashMap<>()
  private final List<Event> orderedEvents = new CopyOnWriteArrayList<>()

  @Override
  void checkpoint(AgentSpan span, int flags) {
    Thread currentThread = Thread.currentThread()
    DDId spanId = span.getSpanId()
    DDId traceId = span.getTraceId()
    Event event = new Event(flags, traceId, spanId, currentThread)
    orderedEvents.add(event)
    spanEvents.putIfAbsent(spanId, new CopyOnWriteArrayList<Event>())
    threadEvents.putIfAbsent(currentThread, new CopyOnWriteArrayList<Event>())
    spanEvents.get(spanId).add(event)
    threadEvents.get(currentThread).add(event)
  }

  @Override
  void onRootSpan(AgentSpan rootSpan, boolean published) {
  }

  void throwOnInvalidSequence(Collection<DDId> trackedSpanIds) {
    String charset = StandardCharsets.UTF_8.name()
    ByteArrayOutputStream baostream = new ByteArrayOutputStream()
    PrintStream out = new PrintStream(baostream, false, charset)

    out.println("=== Tracking spans ${trackedSpanIds*.toLong()}\n")

    def validatedEvents = CheckpointValidator.validate(spanEvents, threadEvents, orderedEvents, trackedSpanIds, out)

    // The set of excluded validations
    def excludedValidations = CheckpointValidator.excludedValidations.clone()

    // The set of included validations
    def includedValidations = EnumSet.allOf(CheckpointValidationMode)
    includedValidations.removeAll(excludedValidations)

    // The set of included validations that failed
    def includedAndFailedValidations = includedValidations.clone()
    includedAndFailedValidations.retainAll(validatedEvents*.key.mode)

    // The set of excluded validations that failed
    def excludedAndFailedValidations = excludedValidations.clone()
    excludedAndFailedValidations.retainAll(validatedEvents*.key.mode)

    // The set of excluded validations that passed
    def excludedAndPassedValidations = excludedValidations.clone()
    excludedAndPassedValidations.removeAll(validatedEvents*.key.mode)

    out.println(
      "=== Checkpoint validator is running with the following checks excluded: ${excludedValidations.sort()}\n" +
      "Included & Failed: ${includedAndFailedValidations.sort()}\n" +
      "Excluded & Passed: ${excludedAndPassedValidations.sort()}\n")

    TimelinePrinter.print(spanEvents, threadEvents, orderedEvents, validatedEvents*.key.event, out)

    out.flush()

    // everything that was printed to `out`
    String msg = baostream.toString(charset)

    if (!includedAndFailedValidations.empty) {
      throw new IllegalStateException("Checkpoints validations: included and failed\n\n${msg}")
    }
    if (CheckpointValidator.validationMode("strict")) {
      if (!excludedAndPassedValidations.empty) {
        throw new IllegalStateException("Checkpoints validations: excluded and passed\n\n${msg}")
      }
    }
    if (CheckpointValidator.validationMode("all-failed")) {
      if (!excludedAndFailedValidations.empty) {
        throw new IllegalStateException("Checkpoints validations: excluded and failed\n\n${msg}")
      }
    }

    System.err.print(msg)
  }

  void clear() {
    orderedEvents.clear()
    spanEvents.clear()
    threadEvents.clear()
    CheckpointValidator.clear()
  }
}
