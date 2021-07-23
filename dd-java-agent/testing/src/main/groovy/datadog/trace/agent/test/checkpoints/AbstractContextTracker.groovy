package datadog.trace.agent.test.checkpoints

abstract class AbstractContextTracker<T> implements EventReceiver<Boolean> {
  Event event = null

  final boolean dispatchEvent(Event event) {
    this.event = event
    try {
      switch (event.name) {
        case "startSpan":
          return startSpan()
        case "startTask":
          return startTask()
        case "endTask":
          return endTask()
        case "suspend":
          return suspendTask()
        case "resume":
          return resumeTask()
        case "endSpan":
          return endSpan()
        default:
          return false
      }
    } finally {
      this.event = null
    }
  }

  abstract boolean startSpan()
  abstract boolean startTask()
  abstract boolean endTask()
  abstract boolean suspendTask()
  abstract boolean resumeTask()
  abstract boolean endSpan()
}
