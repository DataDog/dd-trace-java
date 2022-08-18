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
  void onRootSpanWritten(AgentSpan rootSpan, boolean published, boolean checkpointsSampled) {
  }

  @Override
  void onRootSpanStarted(AgentSpan rootSpan) {}

  void throwOnInvalidSequence(Collection<DDId> trackedSpanIds) {
    String charset = StandardCharsets.UTF_8.name()
    ByteArrayOutputStream baostream = new ByteArrayOutputStream()
    PrintStream out = new PrintStream(baostream, false, charset)

    out.println("== Checkpoints")
    out.println("=== Spans: ${trackedSpanIds*.toLong()}\n")

    def invalidEvents = CheckpointValidator.validate(spanEvents, threadEvents, orderedEvents, trackedSpanIds, out)

    // The set of excluded validations
    def excludedValidations = CheckpointValidator.excludedValidations.clone()

    // The set of included validations
    def includedValidations = EnumSet.allOf(CheckpointValidationMode)
    includedValidations.removeAll(excludedValidations)

    // The set of included validations that failed
    def includedAndFailedValidations = includedValidations.clone()
    includedAndFailedValidations.retainAll(invalidEvents*.mode)

    out.println(
      "=== Validations:\n" +
      "Excluded: ${excludedValidations.sort()}\n" +
      "Failed: ${includedAndFailedValidations.sort()}\n")

    out.println("=== Timeline:")
    TimelinePrinter.print(spanEvents, threadEvents, orderedEvents, invalidEvents*.event, out)

    out.println("=== Events:")
    orderedEvents.each { event ->
      invalidEvents.findAll { it.event == event }.each { out.println(it) }
      out.println(event)
    }

    out.println("==")
    out.flush()

    // everything that was printed to `out`
    String msg = baostream.toString(charset)

    if (!includedAndFailedValidations.empty) {
      throw new IllegalStateException("Checkpoints validations: included and failed\n\n${msg}")
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
