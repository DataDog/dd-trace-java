package datadog.trace.agent.test.checkpoints

/**
 * Validates suspend/resume pairs over the lifetime of a span
 */
class SuspendResumeValidator extends AbstractValidator {
  boolean spanStarted = false
  boolean spanFinished = false
  int activeCount
  int suspendedCount
  int resumeCount
  int suspendCount

  SuspendResumeValidator() {
    super("suspend-resume", CheckpointValidationMode.SEQUENCE)
  }

  @Override
  def startSpan() {
    if (!spanStarted) {
      spanStarted = true
      activeCount = 1
      return Result.OK
    }
    return Result.FAILED.withMessage("Span ${event?.spanId} is already started")
  }

  @Override
  def startTask() {
    return spanStarted ? Result.OK : Result.FAILED.withMessage("Starting task for non-existing span ${event?.spanId}")
  }

  @Override
  def endTask() {
    if (spanStarted && activeCount > 0) {
      activeCount--
      return Result.OK
    }
    if (activeCount == 0) {
      return Result.FAILED.withMessage("Span ${event?.spanId} has no active tasks")
    }
    if (!spanStarted) {
      return Result.FAILED.withMessage("Ending task for non-existing span ${event?.spanId}")
    }
  }


  def suspendSpan() {
    suspendCount++
    if (spanStarted && activeCount > 0) {
      suspendedCount++
      return Result.OK
    }
    if (activeCount <= 0) {
      return Result.FAILED.withMessage("Span ${event?.spanId} has no active tasks")
    }
    if (!spanStarted) {
      return Result.FAILED.withMessage("Attempting to suspend non-existing span ${event?.spanId}")
    }
  }


  def resumeSpan() {
    resumeCount++
    if (spanStarted && suspendedCount > 0) {
      suspendedCount--
      activeCount++
      return Result.OK
    }
    if (suspendCount <= 0) {
      return Result.FAILED.withMessage("Span ${event?.spanId} has no migrations in progress")
    }
    if (!spanStarted) {
      return Result.FAILED.withMessage("Attempting to resume non-existing span ${event?.spanId}")
    }
  }

  @Override
  def endSpan() {
    spanFinished = true
    return spanStarted ? Result.OK : Result.FAILED.withMessage("Attempting to end a non-existing span ${event?.spanId}")
  }

  @Override
  def endSequence() {
    return spanFinished && resumeCount == suspendCount ? Result.OK : Result.FAILED
  }
}
