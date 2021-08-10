package datadog.trace.agent.test.checkpoints

import datadog.trace.api.Checkpointer
import datadog.trace.api.DDId

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TimelineCheckpointer implements Checkpointer {

  private final ConcurrentHashMap<DDId, List<Event>> spanEvents = new ConcurrentHashMap<>()
  private final ConcurrentHashMap<String, List<Event>> threadEvents = new ConcurrentHashMap<>()
  private final List<Event> orderedEvents = new CopyOnWriteArrayList<>()

  @Override
  void checkpoint(DDId traceId, DDId spanId, int flags) {
    Thread currentThread = Thread.currentThread()
    Event event = new Event(flags, traceId, spanId, currentThread)
    orderedEvents.add(event)
    spanEvents.putIfAbsent(spanId, new CopyOnWriteArrayList<Event>())
    threadEvents.putIfAbsent(currentThread.name, new CopyOnWriteArrayList<Event>())
    spanEvents.get(spanId).add(event)
    threadEvents.get(currentThread.name).add(event)
  }

  @Override
  void onRootSpanPublished(String route, DDId traceId) {
  }

  void publish() {
    def validatedEvents = CheckpointValidator.validate(spanEvents, threadEvents, orderedEvents)

    TimelinePrinter.print(spanEvents, threadEvents, orderedEvents, validatedEvents*.key.event)
    TimelineExporter.export(orderedEvents)
    System.err.println("")

    // The set of excluded validations
    def excludedValidations = CheckpointValidator.excludedValidations

    // The set of included validations
    def includedValidations = EnumSet.allOf(CheckpointValidationMode)
    // if `FORCE_VALIDATE_CHECKPOINTS` is defined, make sure we do not exclude any validation
    if (!Boolean.parseBoolean(System.getenv("FORCE_VALIDATE_CHECKPOINTS"))) {
      includedValidations.removeAll(excludedValidations)
    }

    // The set of included validations that failed
    def includedAndFailedValidations = includedValidations.clone()
    includedAndFailedValidations.retainAll(validatedEvents*.key.mode)

    // The set of excluded validations that passed
    def excludedAndPassedValidations = excludedValidations.clone()
    excludedAndPassedValidations.removeAll(validatedEvents*.key.mode)

    System.err.println(
      "Checkpoint validator is running with the following checks disabled: ${excludedValidations}\n" +
      "\tIncluded & Failed: ${includedAndFailedValidations}\n" +
      "\tExcluded & Passed: ${excludedAndPassedValidations}\n")
    if (!includedAndFailedValidations.empty) {
      throw new IllegalStateException(
      "Failed validations: [" +
      includedAndFailedValidations.collect { it.toString() }.sort().join(", ") +
      "]")
    }
  }

  void clear() {
    orderedEvents.clear()
    spanEvents.clear()
    threadEvents.clear()
    CheckpointValidator.clear()
  }
}
