package datadog.trace.agent.test.checkpoints

class IntervalValidator extends AbstractValidator {
  private static enum IntervalState {
    ACTIVE, INACTIVE, CLOSED
  }
  private static class SpanInterval {
    final long spanId
    final Event startEvent
    final int startTick
    int endTick = Integer.MAX_VALUE
    IntervalState state = IntervalState.ACTIVE

    SpanInterval(long spanId, Event startEvent, int startTick) {
      this.spanId = spanId
      this.startEvent = startEvent
      this.startTick = startTick
    }

    def close(int tick) {
      endTick = tick
      state = IntervalState.CLOSED
    }

    String toString() {
      return "[span/${spanId}|${state}](${startTick}, ${endTick})"
    }
  }

  def openIntervalsBySpan = new HashMap<Long, SpanInterval>()
  def intervalsByTime = new ArrayList<>()
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
      intervalsByTime.add(interval)
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
    tick++
    return closeInterval(spanId)
  }

  @Override
  def suspendSpan() {
    return suspendSpan(event.spanId.toLong())
  }

  def suspendSpan(def spanId) {
    tick++
    def ret = closeInterval(spanId)
    if (ret != Result.OK) {
      return ret
    }
    def interval = new SpanInterval(spanId, event, tick)
    interval.state = IntervalState.INACTIVE
    openIntervalsBySpan.put(spanId, interval)
    intervalsByTime.add(interval)
    return Result.OK
  }

  def closeInterval(def spanId, def requireExisting = false) {
    def interval = openIntervalsBySpan.remove(spanId)
    if (interval == null) {
      if (requireExisting) {
        return Result.FAILED.withMessage("Attempting to close an interval for a non-existing span ${spanId}")
      }
      return Result.OK
    }

    interval.close(tick)

    for (def it : intervalsByTime) {
      if (it.spanId == spanId) {
        continue
      }
      if (it.state != IntervalState.INACTIVE) {
        if ((it.startTick <= interval.startTick && it.endTick >= interval.startTick && it.endTick <= tick) ||
        (it.endTick >= tick && it.startTick >= interval.startTick && it.startTick <= tick)) {
          return Result.FAILED.withMessage("Overlapping spans: ${spanId}, ${it.spanId}")
        }
      }
    }
    return Result.OK
  }

  @Override
  def resumeSpan() {
    return resumeSpan(event.spanId.toLong())
  }

  def resumeSpan(def spanId) {
    tick++
    def interval = openIntervalsBySpan.get(spanId)
    if (interval != null) {
      switch (interval.state) {
        case IntervalState.INACTIVE:
          interval = openIntervalsBySpan.remove(spanId)
          intervalsByTime.remove(interval)
          break
        case IntervalState.ACTIVE:
          return Result.OK
        case IntervalState.CLOSED:
          reutrn Result.FAILED.withMessage("Trying to resume an already closed interval for span ${spanId}")
      }
    }
    openIntervalsBySpan.put(spanId, interval = new SpanInterval(spanId, event, tick))
    intervalsByTime.add(interval)
    return Result.OK
  }

  @Override
  def endSpan() {
    return endSpan(event.spanId.toLong())
  }

  def endSpan(def spanId) {
    tick++
    closeInterval(spanId)
  }

  @Override
  def endSequence() {
    return intervalsByTime.findAll {it.state == IntervalState.ACTIVE}.empty ? Result.OK : Result.FAILED
  }
}
