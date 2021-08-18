package datadog.trace.agent.test.checkpoints

import datadog.trace.api.Checkpointer
import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TimelineCheckpointer implements Checkpointer {

  private final ConcurrentHashMap<DDId, List<Event>> spanEvents = new ConcurrentHashMap<>()
  private final ConcurrentHashMap<String, List<Event>> threadEvents = new ConcurrentHashMap<>()
  private final List<Event> orderedEvents = new CopyOnWriteArrayList<>()

  @Override
  void checkpoint(AgentSpan span, int flags) {
    Thread currentThread = Thread.currentThread()
    DDId spanId = span.getSpanId()
    DDId traceId = span.getTraceId()
    Event event = new Event(flags, traceId, spanId, currentThread)
    orderedEvents.add(event)
    spanEvents.putIfAbsent(spanId, new CopyOnWriteArrayList<Event>())
    threadEvents.putIfAbsent(currentThread.name, new CopyOnWriteArrayList<Event>())
    spanEvents.get(spanId).add(event)
    threadEvents.get(currentThread.name).add(event)
  }

  @Override
  void onRootSpan(String route, DDId traceId, boolean published) {
  }

  void publish() {
    def validatedEvents = CheckpointValidator.validate(spanEvents, threadEvents, orderedEvents)

    TimelinePrinter.print(spanEvents, threadEvents, orderedEvents, validatedEvents*.key.event)
    TimelineExporter.export(orderedEvents)
    System.err.println("")

    // apparently gradle can not pass system property to spock tests - therefore using env variable instead
    if (Boolean.parseBoolean(System.getenv("VALIDATE_CHECKPOINTS")) && validatedEvents.find {it.value} != null) {
      throw new IllegalStateException("Checkpoint validation failed")
    }
  }

  void clear() {
    orderedEvents.clear()
    spanEvents.clear()
    threadEvents.clear()
    CheckpointValidator.clear()
  }
}
