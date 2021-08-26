package datadog.trace.agent.test.checkpoints

class IntervalValidator extends AbstractValidator {
  private static class SpanInterval {
    final long spanId
    final Event startEvent
    final int startTick
    int endTick = Integer.MAX_VALUE
    boolean inactive = false

    SpanInterval(long spanId, Event startEvent, int startTick) {
      this.spanId = spanId
      this.startEvent = startEvent
      this.startTick = startTick
    }

    String toString() {
      return "[span/${spanId}|${inactive}](${startTick}, ${endTick})"
    }
  }

  def openIntervalsBySpan = new HashMap<Long, SpanInterval>()
  def openIntervalsByTime = new ArrayList<>()
  def closedIntervalsBySpan = new HashMap<Long, SpanInterval>()
  def closedIntervalsByTime = new ArrayList<>()
  def tick = 0

  IntervalValidator() {
    super("intervals", CheckpointValidationMode.INTERVALS)
  }

  @Override
  def startSpan() {
    return startSpan(event.spanId.toLong())
  }

  def startSpan(def spanId) {
    tick++

    def interval = openIntervalsBySpan.get(spanId)
    if (interval == null) {
      openIntervalsBySpan.put(spanId, (interval = new SpanInterval(spanId,  event, tick)))
      openIntervalsByTime.add(interval)
      return Result.OK
    }
    return Result.FAILED.withMessage("There is already an open interval for span ${spanId}")
  }

  @Override
  def startTask() {
    return Result.OK
  }

  @Override
  def endTask() {
    return endTask(event.spanId.toLong())
  }

  def endTask(def spanId) {
    return deactivateSpan(spanId)
  }

  @Override
  def suspendSpan() {
    return suspendSpan(event.spanId.toLong())
  }

  def suspendSpan(def spanId) {
    return deactivateSpan(spanId)
  }

  def deactivateSpan(def spanId) {
    tick++
    def interval = openIntervalsBySpan.get(spanId)
    if (interval == null) {
      // allow out-of-order suspension
      // usually happens in async frameworks
      interval = closedIntervalsBySpan.get(spanId)
      if (interval == null) {
        return Result.FAILED.withMessage("Attempting to deactivate a non-existing span ${spanId}")
      }
    }
    interval.inactive = true
    return Result.OK
  }

  @Override
  def resumeSpan() {
    return resumeSpan(event.spanId.toLong())
  }

  def resumeSpan(def spanId) {
    tick++
    def interval = openIntervalsBySpan.get(spanId)
    if (interval == null) {
      openIntervalsBySpan.put(spanId, interval = new SpanInterval(spanId, event, tick))
      openIntervalsByTime.add(interval)
    } else {
      interval.inactive = false
    }
    return Result.OK
  }

  @Override
  def endSpan() {
    return endSpan(event.spanId.toLong())
  }

  def endSpan(def spanId) {
    def result = true
    tick++
    def interval = openIntervalsBySpan.remove(spanId)
    if (interval == null) {
      return Result.FAILED.withMessage("Attempting to end a non-existing span ${spanId}")
    }
    def index = openIntervalsByTime.size() - 1
    for (def open : openIntervalsByTime.reverse()) {
      if (open.spanId == spanId) {
        break
      }
      if (!open.inactive) {
        markInvalid(open.startEvent, "Overlapping active spans: (${open.spanId}, ${spanId})")
        result = false
      }
      index--
    }
    if (index >= 0) {
      openIntervalsByTime.remove(index)
    }
    if (closedIntervalsBySpan.containsKey(spanId)) {
      return Result.FAILED.withMessage("Interval for span ${spanId} has already been closed")
    }
    for (def closed : closedIntervalsByTime.reverse()) {
      if (closed.endTick < interval.startTick) {
        break
      }
      if (closed.startTick < interval.startTick) {
        markInvalid(closed.startEvent, "Overlapping spans: (${closed.spanId}, ${spanId})")
        result = false
      }
    }
    interval.endTick = tick
    closedIntervalsByTime.add(interval)
    closedIntervalsBySpan.put(interval.spanId, interval)

    return result ? Result.OK : Result.FAILED
  }

  @Override
  def endSequence() {
    return openIntervalsByTime.findAll {!it.inactive}.empty ? Result.OK : Result.FAILED
  }
}
