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
      state = state != IntervalState.INACTIVE ? IntervalState.CLOSED : state
    }

    String toString() {
      return "[span/${spanId}|${state}](${startTick}, ${endTick})"
    }
  }

  def tick = 0
  def taskStack = new ArrayDeque<>()

  IntervalValidator() {
    super("intervals", CheckpointValidationMode.INTERVALS)
  }

  @Override
  def startSpan() {
    // ignore
    return startSpan(event.spanId.toLong())
  }

  def startSpan(def spanId) {
    return Result.OK
  }

  @Override
  def startTask() {
    return startTask(event.spanId.toLong())
  }

  def startTask(def spanId) {
    tick++
    taskStack.push(spanId)
    return Result.OK
  }

  @Override
  def endTask() {
    return endTask(event.spanId.toLong())
  }

  def endTask(def spanId) {
    tick++
    if (taskStack.isEmpty()) {
      return Result.FAILED.withMessage("No active task")
    }
    def popped = taskStack.pop()
    if (popped != spanId) {
      return Result.FAILED.withMessage("Invalid active task. Expected ${popped}, got ${spanId}")
    }
    return Result.OK
  }

  @Override
  def suspendSpan() {
    // ignore
    return suspendSpan(event.spanId.toLong())
  }

  def suspendSpan(def spanId) {
    return Result.OK
  }

  @Override
  def resumeSpan() {
    // ignore
    return resumeSpan(event.spanId.toLong())
  }

  def resumeSpan(def spanId) {
    return Result.OK
  }

  @Override
  def endSpan() {
    // ignore
    return endSpan(event.spanId.toLong())
  }

  def endSpan(def spanId) {
    return Result.OK
  }

  @Override
  def endSequence() {
    return taskStack.isEmpty() ? Result.OK : Result.FAILED
  }
}
