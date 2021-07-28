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
    Event event = new Event(flags, traceId, spanId, currentThread, currentThread.stackTrace)
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
    def invalidEvents = TimelineValidator.validate(spanEvents, threadEvents, orderedEvents)
    if (!invalidEvents.empty) {
      System.err.println("=== Invalid checkpoint events encountered")
      invalidEvents.stream().map { it.spanId }.distinct().forEach {
        spanEvents.get(it).each { System.err.println(it) }
      }
    }
    TimelinePrinter.print(spanEvents, threadEvents, orderedEvents, invalidEvents)
    TimelineExporter.export(orderedEvents)
    System.err.println("")

    // apparently gradle can not pass system property to spock tests - therefore using env variable instead
    if (!invalidEvents.empty && Boolean.parseBoolean(System.getenv("VALIDATE_CHECKPOINTS"))) {
      throw new RuntimeException()
    }
  }

  void clear() {
    orderedEvents.clear()
    spanEvents.clear()
    threadEvents.clear()
  }
}
