package datadog.trace.agent.test.checkpoints

class InvalidEvent {
  final Event event
  final String message
  final CheckpointValidationMode mode
  final String source

  InvalidEvent(Event event, String message = "", CheckpointValidationMode mode, String source) {
    this.event = event
    this.message = message
    this.mode = mode
    this.source = source
  }

  boolean equals(o) {
    if (this.is(o)) {
      return true
    }
    if (!(o instanceof InvalidEvent)) {
      return false
    }

    InvalidEvent that = (InvalidEvent) o

    if (event != that.event) {
      return false
    }
    if (mode != that.mode) {
      return false
    }
    return source == that.source
  }

  int hashCode() {
    int result
    result = event == null ? 0 : event.hashCode()
    result = 31 * result + mode.hashCode()
    result = 31 * result + source.hashCode()
    return result
  }

  @Override
  String toString() {
    return "${mode}\t${source}: ${message}\n${event}"
  }
}
