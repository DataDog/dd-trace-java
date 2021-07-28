package datadog.trace.agent.test.checkpoints

/**
 * Validates suspend/resume pairs over the lifetime of a span
 */
class SuspendResumeValidator extends AbstractContextTracker {
  boolean spanStarted = false
  boolean spanFinished = false
  int activeCount
  int suspendedCount
  int resumeCount
  int suspendCount

  Boolean onEvent(Event event) {
    return dispatchEvent(event)
  }

  @Override
  boolean startSpan() {
    if (!spanStarted) {
      spanStarted = true
      activeCount = 1
      return true
    }
    return false
  }

  @Override
  boolean startTask() {
    return spanStarted
  }

  @Override
  boolean endTask() {
    if (spanStarted && activeCount > 0) {
      activeCount--
      return true
    }
    return false
  }


  boolean suspendSpan() {
    suspendCount++
    if (spanStarted && activeCount > 0) {
      suspendedCount++
      return true
    }
    return false
  }


  boolean resumeSpan() {
    resumeCount++
    if (spanStarted && suspendedCount > 0) {
      suspendedCount--
      activeCount++
      return true
    }
    return false
  }

  @Override
  boolean endSpan() {
    spanFinished = true
    return spanStarted
  }

  @Override
  boolean endSequence() {
    return spanFinished && resumeCount == suspendCount
  }
}
