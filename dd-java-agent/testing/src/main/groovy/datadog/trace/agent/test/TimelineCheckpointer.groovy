package datadog.trace.agent.test

import datadog.trace.api.Checkpointer
import datadog.trace.api.DDId

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TimelineCheckpointer implements Checkpointer {

  private final ConcurrentHashMap<DDId, List<Event>> spanEvents = new ConcurrentHashMap<>()
  private final ConcurrentHashMap<String, List<Event>> threadEvents = new ConcurrentHashMap<>()
  private final List<Event> encounterOrder = new CopyOnWriteArrayList<>()

  @Override
  void checkpoint(DDId traceId, DDId spanId, int flags) {
    Thread currentThread = Thread.currentThread()
    Event event = new Event(flagsToEvent(flags), traceId, spanId, currentThread.name)
    encounterOrder.add(event)
    spanEvents.putIfAbsent(spanId, new CopyOnWriteArrayList<Event>())
    threadEvents.putIfAbsent(currentThread.name, new CopyOnWriteArrayList<Event>())
    spanEvents.get(spanId).add(event)
    threadEvents.get(currentThread.name).add(event)
  }

  private String flagsToEvent(int flags) {
    switch (flags) {
      case SPAN:
        return "startSpan"
      case SPAN | END:
        return "endSpan"
      case THREAD_MIGRATION:
        return "suspend"
      case THREAD_MIGRATION | END:
        return "resume"
      case CPU | END:
        return "endTask"
      case CPU:
        return "startTask"
      default:
        return "unknown"
    }
  }

  @Override
  void onRootSpanPublished(String route, DDId traceId) {
  }

  void clear() {
    encounterOrder.clear()
    spanEvents.clear()
    threadEvents.clear()
  }

  void printActivity() {
    printTimeLine()
  }

  void printTimeLine() {
    System.err.println("Activity checkpoints by thread ordered by time")
    int threadCount = threadEvents.size()
    StringBuilder[] timelineBuilders = new StringBuilder[threadCount]
    Map<String, Integer> threadNameToPosition = new HashMap<>()
    SortedSet<String> threadNames = new TreeSet<>(threadEvents.keySet())
    int position = 0
    int maxNameLength = 0
    for (String threadName : threadNames) {
      StringBuilder sb = new StringBuilder().append(threadName)
      timelineBuilders[position] = sb
      threadNameToPosition.put(threadName, position)
      maxNameLength = Math.max(maxNameLength, threadName.length())
      ++position
    }
    for (StringBuilder timeline : timelineBuilders) {
      int length = timeline.length()
      timeline.append(':')
      for (int i = 0; i < maxNameLength - length + 1; ++i) {
        timeline.append(' ')
      }
      timeline.append('|')
    }
    for (Event event : encounterOrder) {
      String rendering = event.eventName + "/" + event.spanId
      if (!threadNameToPosition.containsKey(event.threadName)) {
        System.err.println(event.threadName + " not in "+ threadNameToPosition.keySet())
        continue
      }
      int pos = threadNameToPosition.get(event.threadName)
      StringBuilder timeline = timelineBuilders[pos]
      timeline.append('-')
      timeline.append(rendering)
      timeline.append('-')
      timeline.append('|')
      for (int i = 0; i < threadCount; ++i) {
        if (i != pos) {
          timeline = timelineBuilders[i]
          timeline.append('-')
          for (int j = 0; j < rendering.length(); ++j) {
            timeline.append('-')
          }
          timeline.append('-')
          timeline.append('|')
        }
      }
    }
    for (StringBuilder timeline : timelineBuilders) {
      System.err.println(timeline.toString())
    }
  }

  class Event {
    private final String eventName
    private final String threadName
    private final DDId traceId
    private final DDId spanId

    Event(String eventName, DDId traceId, DDId spanId, String threadName) {
      this.eventName = eventName
      this.traceId = traceId
      this.spanId = spanId
      this.threadName = threadName
    }

    String getEventName() {
      return eventName
    }

    DDId getTraceId() {
      return traceId
    }

    DDId getSpanId() {
      return spanId
    }
  }
}
