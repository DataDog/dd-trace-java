package datadog.trace.agent.test.checkpoints

/**
 * State machine based thread specific checkpoint sequence checker
 */
class ThreadSequenceValidator implements EventReceiver {
  static enum TaskState {
    INIT, INACTIVE, ACTIVE, FINISHED, INVALID
  }

  static enum SpanState {
    INIT, STARTED, RESUMED, SUSPENDED, ENDED, INVALID
  }

  static class State {
    final TaskState taskState
    final SpanState spanState

    State() {
      this(SpanState.INIT, TaskState.INIT)
    }

    State(SpanState spanState, TaskState taskState) {
      this.taskState = taskState
      this.spanState = spanState
    }

    boolean isValid() {
      return taskState != TaskState.INVALID && spanState != SpanState.INVALID
    }

    boolean equals(o) {
      if (this.is(o)) {
        return true
      }
      if (o instanceof State) {

        State state = (State) o

        if (spanState != state.spanState) {
          return false
        }
        return taskState != state.taskState
      }
      return false
    }

    int hashCode() {
      int result
      result = taskState.hashCode()
      result = 31 * result + spanState.hashCode()
      return result
    }

    @Override
    String toString() {
      return "[${spanState}, ${taskState}]"
    }
  }

  static enum Signal {
    START_SPAN, END_SPAN, START_TASK, END_TASK, SUSPEND_SPAN, RESUME_SPAN
  }

  private final Map<Long, SingleThreadTracker> threadTrackers = new HashMap<>()

  @Override
  Boolean onEvent(Event event) {
    def threadTracker = threadTrackers.get(event.threadId)
    if (threadTracker == null) {
      threadTrackers.put(event.threadId, threadTracker = new SingleThreadTracker())
    }
    return threadTracker.onEvent(event)
  }

  static class SingleThreadTracker extends AbstractContextTracker {
    private State state = new State()

    @Override
    Boolean onEvent(Event event) {
      return dispatchEvent(event)
    }

    static State transit(State fromState, Signal signal) {
      def states = transit(fromState.spanState, fromState.taskState, signal)
      return new State(states[0], states[1])
    }

    static transit(def spanState, def taskState, def signal) {
      def newSpanState = spanState
      def newTaskState = taskState

      switch (signal) {
        case Signal.START_SPAN:
          if (spanState == SpanState.INIT) {
            newSpanState = SpanState.STARTED
          } else {
            newSpanState = toInvalid(spanState)
          }
          if (taskState == TaskState.INIT || taskState == TaskState.INACTIVE) {
            newTaskState = TaskState.ACTIVE
          } else {
            newTaskState = toInvalid(taskState)
          }
          break
        case Signal.END_SPAN:
          if (spanState == SpanState.STARTED || spanState == SpanState.RESUMED || spanState == SpanState.SUSPENDED) {
            newSpanState = SpanState.ENDED
          } else {
            newSpanState = toInvalid(spanState)
          }
          break
        case Signal.START_TASK:
          if (taskState == TaskState.INIT || taskState == TaskState.INACTIVE) {
            newTaskState = TaskState.ACTIVE
          } else {
            newTaskState = toInvalid(taskState)
          }
          break
        case Signal.END_TASK:
          if (spanState == SpanState.INIT) {
            newSpanState = toInvalid(spanState)
          } else {
            if (taskState == TaskState.ACTIVE || taskState == TaskState.INACTIVE || taskState == TaskState.FINISHED) {
              newTaskState = TaskState.FINISHED
            } else {
              newTaskState = toInvalid(taskState)
            }
          }
          break
        case Signal.SUSPEND_SPAN:
          switch (spanState) {
            case SpanState.STARTED:
            case SpanState.RESUMED:
            case SpanState.SUSPENDED:
            newSpanState = SpanState.SUSPENDED
            if (taskState == TaskState.ACTIVE || taskState == TaskState.INACTIVE || taskState == TaskState.FINISHED) {
              newTaskState = TaskState.INACTIVE
            } else {
              newSpanState = toInvalid(spanState)
            }
            break
            case SpanState.ENDED:
            if (taskState == TaskState.ACTIVE || taskState == TaskState.INACTIVE) {
              newSpanState = SpanState.SUSPENDED
              newTaskState = TaskState.INACTIVE
            } else {
              newSpanState = toInvalid(spanState)
            }
            break
            default:
            newSpanState = toInvalid(spanState)
          }
          break
        case Signal.RESUME_SPAN:
          switch (spanState) {
            case SpanState.INIT:
            case SpanState.SUSPENDED:
            if (taskState == TaskState.INIT || taskState == TaskState.INACTIVE || taskState == TaskState.FINISHED) {
              newSpanState = SpanState.RESUMED
              newTaskState = TaskState.ACTIVE
            } else {
              newSpanState = toInvalid(spanState)
            }
            break
            case SpanState.RESUMED:
            if (taskState == TaskState.FINISHED || taskState == TaskState.ACTIVE) {
              newSpanState = SpanState.RESUMED
              newTaskState = TaskState.ACTIVE
            } else {
              newSpanState = toInvalid(spanState)
            }
            break
            default:
            newSpanState = toInvalid(spanState)
          }
          break
      }
      return [newSpanState, newTaskState]
    }

    private static toInvalid(def state) {
      if (state instanceof SpanState || state instanceof TaskState) {
        if (state instanceof SpanState) {
          return SpanState.INVALID
        }
        if (state instanceof TaskState) {
          return TaskState.INVALID
        }
      }
      // do not replace for Spock wildcards
      return state
    }

    @Override
    boolean startSpan() {
      state = transit(state, Signal.START_SPAN)
      return state.valid
    }

    @Override
    boolean startTask() {
      state = transit(state, Signal.START_TASK)
      return state.valid
    }

    @Override
    boolean endTask() {
      state = transit(state, Signal.END_TASK)
      return state.valid
    }

    @Override
    boolean suspendSpan() {
      state = transit(state, Signal.SUSPEND_SPAN)
      return state.valid
    }

    @Override
    boolean resumeSpan() {
      state = transit(state, Signal.RESUME_SPAN)
      return state.valid
    }

    @Override
    boolean endSpan() {
      state = transit(state, Signal.END_SPAN)
      return state.valid
    }
  }
}
