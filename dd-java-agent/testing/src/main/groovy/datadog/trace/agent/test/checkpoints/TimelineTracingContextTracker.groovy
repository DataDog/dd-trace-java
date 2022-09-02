package datadog.trace.agent.test.checkpoints

import datadog.trace.api.Checkpointer
import datadog.trace.api.DDId
import datadog.trace.api.function.ToIntFunction
import datadog.trace.api.profiling.TracingContextTracker
import datadog.trace.api.profiling.TracingContextTrackerFactory
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TimelineTracingContextTracker implements TracingContextTracker {
  static class TimelineTracingContextTrackerFactory implements TracingContextTrackerFactory.Implementation {
    TimelineTracingContextTracker tracker = new TimelineTracingContextTracker()
    @Override
    TracingContextTracker instance(AgentSpan span) {
      return new TracingContextTracker() {
          @Override
          boolean release() {
            return tracker.release()
          }

          @Override
          void activateContext() {
            tracker.activateContext(span)
          }

          @Override
          void deactivateContext() {
            tracker.deactivateContext(span)
          }

          @Override
          void maybeDeactivateContext() {
            tracker.maybeDeactivateContext()
          }

          @Override
          byte[] persist() {
            return tracker.persist()
          }

          @Override
          int persist(ToIntFunction<ByteBuffer> dataConsumer) {
            return tracker.persist(dataConsumer)
          }

          @Override
          int getVersion() {
            return tracker.getVersion()
          }

          @Override
          DelayedTracker asDelayed() {
            return tracker.asDelayed()
          }
        }
    }
  }

  static final TimelineTracingContextTrackerFactory FACTORY = new TimelineTracingContextTrackerFactory()

  static TimelineTracingContextTracker register() {
    TracingContextTrackerFactory.registerImplementation(FACTORY)
    return FACTORY.tracker
  }

  private final ConcurrentHashMap<DDId, List<Event>> spanEvents = new ConcurrentHashMap<>()
  private final ConcurrentHashMap<Thread, List<Event>> threadEvents = new ConcurrentHashMap<>()
  private final List<Event> orderedEvents = new CopyOnWriteArrayList<>()

  @Override
  boolean release() {
    return false
  }

  void activateContext(AgentSpan span) {
    Thread currentThread = Thread.currentThread()
    DDId traceId = span != null ? span.traceId : DDId.ZERO
    DDId spanId = span != null ? span.spanId : DDId.ZERO
    Event event = new Event(Checkpointer.CPU, traceId, spanId, currentThread)
    orderedEvents.add(event)
    spanEvents.putIfAbsent(spanId, new CopyOnWriteArrayList<Event>())
    threadEvents.putIfAbsent(currentThread, new CopyOnWriteArrayList<Event>())
    spanEvents.get(spanId).add(event)
    threadEvents.get(currentThread).add(event)
  }

  @Override
  void activateContext() {
    throw new UnsupportedOperationException()
  }

  void deactivateContext(AgentSpan span) {
    Thread currentThread = Thread.currentThread()
    DDId traceId = span != null ? span.traceId : DDId.ZERO
    DDId spanId = span != null ? span.spanId : DDId.ZERO
    Event event = new Event(Checkpointer.CPU | Checkpointer.END, traceId, spanId, currentThread)
    orderedEvents.add(event)
    spanEvents.putIfAbsent(spanId, new CopyOnWriteArrayList<Event>())
    threadEvents.putIfAbsent(currentThread, new CopyOnWriteArrayList<Event>())
    spanEvents.get(spanId).add(event)
    threadEvents.get(currentThread).add(event)
  }

  @Override
  void deactivateContext() {
    throw new UnsupportedOperationException()
  }

  @Override
  void maybeDeactivateContext() {
    throw new UnsupportedOperationException()
  }

  @Override
  byte[] persist() {
    return new byte[0]
  }

  @Override
  int persist(ToIntFunction<ByteBuffer> dataConsumer) {
    return -1
  }

  @Override
  int getVersion() {
    return 0
  }

  @Override
  DelayedTracker asDelayed() {
    return null
  }

  void clear() {
    orderedEvents.clear()
    spanEvents.clear()
    threadEvents.clear()
  }

  void print() {
    System.err.println("== Tracing Context Tracker")
    System.err.println("=== Timeline:")
    TimelinePrinter.print(spanEvents, threadEvents, orderedEvents, null, System.err)
    System.err.println("=== Events")
    orderedEvents.each { event ->
      System.err.println(event)
    }
    System.err.println("==")
  }
}
