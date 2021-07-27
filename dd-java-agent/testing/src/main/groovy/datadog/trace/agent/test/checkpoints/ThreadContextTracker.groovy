package datadog.trace.agent.test.checkpoints

class ThreadContextTracker extends AbstractContextTracker {
  private static class SpanInterval {
    final long spanId
    final int startTick
    int endTick = Integer.MAX_VALUE
    boolean suspended = false

    SpanInterval(long spanId, int startTick) {
      this.spanId = spanId
      this.startTick = startTick
    }

    String toString() {
      return "[span/${spanId}|${suspended}](${startTick}, ${endTick})"
    }
  }

  def openIntervalsBySpan = new HashMap<Long, SpanInterval>()
  def openIntervalsByTime = new ArrayList<>()
  def closedIntervalsByTime = new ArrayList<>()
  def tick = 0

  Boolean onEvent(Event event) {
    return dispatchEvent(event)
  }

  @Override
  boolean startSpan() {
    return startSpan(event.spanId.toLong())
  }

  boolean startSpan(def spanId) {
    tick++

    def interval = openIntervalsBySpan.get(spanId)
    if (interval == null) {
      openIntervalsBySpan.put(spanId, (interval = new SpanInterval(spanId,  tick)))
      openIntervalsByTime.add(interval)
      return true
    }
    return false
  }

  @Override
  boolean startTask() {
    throw new UnsupportedOperationException()
  }

  @Override
  boolean endTask() {
    return endTask(event.spanId.toLong())
  }

  boolean endTask(def spanId) {
    tick++
    return true
  }

  @Override
  boolean suspendTask() {
    return suspendTask(event.spanId.toLong())
  }

  boolean suspendTask(def spanId) {
    tick++
    def interval = openIntervalsBySpan.get(spanId)
    if (interval == null) {
      return false
    }
    interval.suspended = true
    return true
  }

  @Override
  boolean resumeTask() {
    return resumeTask(event.spanId.toLong())
  }

  boolean resumeTask(def spanId) {
    tick++
    def interval = openIntervalsBySpan.get(spanId)
    if (interval == null) {
      openIntervalsBySpan.put(spanId, interval = new SpanInterval(spanId, tick))
      openIntervalsByTime.add(interval)
    } else {
      interval.suspended = false
    }
    return true
  }

  @Override
  boolean endSpan() {
    return endSpan(event.spanId.toLong())
  }

  boolean endSpan(def spanId) {
    def result = true
    tick++
    def interval = openIntervalsBySpan.remove(spanId)
    if (interval == null) {
      result = false
    } else {
      def index = openIntervalsByTime.size() - 1
      for (def open : openIntervalsByTime.reverse()) {
        if (open.spanId == spanId) {
          break
        }
        if (!open.suspended) {
          result = false
        }
        index--
      }
      if (index > 0) {
        openIntervalsByTime.remove(index)
      }
      for (def closed : closedIntervalsByTime.reverse()) {
        if (closed.endTick < interval.startTick) {
          break
        }
        if (closed.startTick < interval.startTick) {
          result = false
        }
      }
      interval.endTick = tick
      closedIntervalsByTime.add(interval)
    }
    return result
  }
}
