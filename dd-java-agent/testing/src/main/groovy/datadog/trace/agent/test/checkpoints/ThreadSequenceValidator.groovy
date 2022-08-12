package datadog.trace.agent.test.checkpoints

/**
 * State machine based thread specific checkpoint sequence checker
 */
class ThreadSequenceValidator {

  static enum SpanState {
    INIT, STARTED, RESUMED, SUSPENDED, ENDED, INVALID
  }

  static enum Signal {
    START_SPAN, END_SPAN, SUSPEND_SPAN, RESUME_SPAN
  }

  private final Map<Long, SingleThreadTracker> threadTrackers = new HashMap<>()

  boolean onEvent(Event event) {
    def threadTracker = threadTrackers.get(event.threadId)
    if (threadTracker == null) {
      threadTrackers.put(event.threadId, threadTracker = new SingleThreadTracker())
    }
    return threadTracker.onEvent(event)
  }

  def endSequence() {
    threadTrackers.each {it.value.endSequence()}
  }

  def getInvalidEvents() {
    def rslt = new HashSet()
    threadTrackers.values().each {
      rslt.addAll(it.invalidEvents)
    }
    return rslt
  }

  static class SingleThreadTracker extends AbstractValidator {
    private SpanState state = SpanState.INIT

    static transit(def spanState, def signal) {
      def newSpanState = spanState

      switch (signal) {
        case Signal.START_SPAN:
          if (spanState == SpanState.INIT) {
            newSpanState = SpanState.STARTED
          } else {
            newSpanState = toInvalid(spanState)
          }
          break
        case Signal.END_SPAN:
          if (spanState == SpanState.STARTED || spanState == SpanState.RESUMED || spanState == SpanState.SUSPENDED) {
            newSpanState = SpanState.ENDED
          } else {
            newSpanState = toInvalid(spanState)
          }
          break
        case Signal.SUSPEND_SPAN:
          if (spanState == SpanState.INIT) {
            newSpanState = toInvalid(spanState)
          } else {
            newSpanState = SpanState.SUSPENDED
          }
          break
        case Signal.RESUME_SPAN:
          newSpanState = SpanState.RESUMED
          break
      }
      return newSpanState
    }

    private static toInvalid(def state) {
      if (state instanceof SpanState) {
        return SpanState.INVALID
      }
      // do not replace for Spock wildcards
      return state
    }

    SingleThreadTracker() {
      super("thread-sequence", CheckpointValidationMode.THREAD_SEQUENCE)
    }

    @Override
    def startSpan() {
      state = transit(state, Signal.START_SPAN)
      return state != SpanState.INVALID ? Result.OK : Result.FAILED.withMessage("Can not start span ${event?.spanId}. State = ${state}")
    }

    @Override
    def startTask() {
      // ignore
      return Result.OK
    }

    @Override
    def endTask() {
      // ignore
      return Result.OK
    }

    @Override
    def suspendSpan() {
      state = transit(state, Signal.SUSPEND_SPAN)
      return state != SpanState.INVALID ? Result.OK : Result.FAILED.withMessage("Can not suspend span ${event?.spanId}. State = ${state}")
    }

    @Override
    def resumeSpan() {
      state = transit(state, Signal.RESUME_SPAN)
      return state != SpanState.INVALID ? Result.OK : Result.FAILED.withMessage("Can not resume span ${event?.spanId}. State = ${state}")
    }

    @Override
    def endSpan() {
      state = transit(state, Signal.END_SPAN)
      return state != SpanState.INVALID ? Result.OK : Result.FAILED.withMessage("Can not finish span ${event?.spanId}. State = ${state}")
    }
  }
}
