package datadog.trace.agent.test.checkpoints

import datadog.trace.api.Checkpointer
import datadog.trace.api.DDId

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.stream.Collectors

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
    def invalidEvents = CheckpointValidator.validate(spanEvents, threadEvents, orderedEvents)
    if (!invalidEvents.empty) {
      System.err.println("=== Invalid checkpoint events encountered")
      invalidEvents.each { System.err.println(it) }
    }
    TimelinePrinter.print(spanEvents, threadEvents, orderedEvents, invalidEvents.collect {it[0]})
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
    def failed = includedValidations.clone()
    failed.retainAll(invalidEvents.collect { it[1] }.toSet())

    // The set of included validations that passed despite being exluded
    def passed = includedValidations.clone()
    passed.removeAll(excludedValidations)
    passed.removeAll(failed)

    System.err.println(
      "Checkpoint validator is running with the following checks disabled: ${excludedValidations}, and enabled: ${includedValidations}\n" +
      "\tFailed: ${failed}\n" +
      "\tExcluded & Passed: ${passed}\n")
    if (!failed.empty) {
      throw new RuntimeException(
      "Failed validations: [" +
      failed.collect { it.toString() }.sort().join(", ") +
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
