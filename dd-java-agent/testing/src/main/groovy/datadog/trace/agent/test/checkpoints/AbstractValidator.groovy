package datadog.trace.agent.test.checkpoints

abstract class AbstractValidator {
  private final String name
  private final CheckpointValidationMode mode

  final Set<InvalidEvent> invalidEvents = new HashSet<>()

  static final class Result {
    static final Result OK = new Result(true)
    static final Result FAILED = new Result(false)

    private final name
    private final valid
    private final msg

    private Result(def valid, def msg = "") {
      this.name = valid ? "OK" : "FAILED"
      this.valid = valid
      this.msg = msg != null ? msg : ""
    }

    def asBoolean() {
      return valid
    }

    def withMessage(def msg) {
      if (this == FAILED) {
        return new Result(false, msg)
      }
      return this
    }

    def getMessage() {
      return this.msg
    }

    def isValid() {
      return valid
    }

    @Override
    String toString() {
      return "${this.name} (${this.msg})"
    }
  }

  Event event = null

  AbstractValidator(String name, CheckpointValidationMode mode) {
    this.name = name
    this.mode = mode
  }

  final void markInvalid(Event event, String msg = "") {
    invalidEvents.add(new InvalidEvent(event, msg, mode, name))
  }

  boolean onEvent(Event event) {
    return dispatchEvent(event) == Result.OK
  }

  final dispatchEvent(Event event) {
    def result
    this.event = event
    try {
      switch (event.name) {
        case "startSpan":
          result = startSpan()
          break
        case "startTask":
          result = startTask()
          break
        case "endTask":
          result = endTask()
          break
        case "suspend":
          result = suspendSpan()
          break
        case "resume":
          result = resumeSpan()
          break
        case "endSpan":
          result = endSpan()
          break
        default:
          result = Result.FAILED.withMessage("Unknown event type: ${event.name}")
      }
    } finally {
      if (result != Result.OK) {
        markInvalid(event, result.message)
      }
      this.event = null
    }
    return result
  }

  abstract startSpan()
  abstract startTask()
  abstract endTask()
  abstract suspendSpan()
  abstract resumeSpan()
  abstract endSpan()

  def endSequence() {
    return Result.OK
  }
}
