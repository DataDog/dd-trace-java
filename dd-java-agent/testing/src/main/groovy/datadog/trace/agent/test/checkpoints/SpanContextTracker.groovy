package datadog.trace.agent.test.checkpoints

/**
 * State machine based checkpoint sequence checker
 */
class SpanContextTracker extends AbstractContextTracker {
  static enum TaskState {
    TASK_INIT, ACTIVE, MIGRATING
  }

  static enum SpanState {
    SPAN_INIT, OPEN, CLOSED
  }

  final Deque<TaskState> stack = new ArrayDeque<>()
  SpanState spanState = SpanState.SPAN_INIT

  SpanContextTracker() {
    // initialize the stack
    stack.push(TaskState.TASK_INIT)
  }

  Boolean onEvent(Event event) {
    def fromStack = new ArrayList<>(stack)
    def fromSpanState = spanState
    def ret = dispatchEvent(event)
    if (!ret) {
      System.err.println("SpanContextTracker [FAIL] " + event.name + "/" + event.spanId +
        " ::\n(from stack)\n" + fromStack + "\n(to stack)\n" + stack +
        "\n(from span state)\n" + fromSpanState + "\n(to span state)\n" + spanState
      )
    }
    return ret
  }

  @Override
  boolean startSpan(def dryRun = false) {
    // stack must be initialized
    if (stack.empty) {
      return false
    }
    def stackBackup = dryRun ? new ArrayList<>(stack) : null
    try {
      // startSpan must be the first event
      if (stack.peek() != TaskState.TASK_INIT) {
        return false
      }
      // set the span state to OPEN
      spanState = SpanState.OPEN
      // activate the implicit task
      stack.push(TaskState.ACTIVE)
      return true
    } finally {
      if (dryRun) {
        spanState = SpanState.SPAN_INIT
        stack.clear()
        stack.addAll(stackBackup)
      }
    }
  }

  @Override
  boolean startTask(def dryRun = false) {
    // not supported currently
    throw new UnsupportedOperationException()
  }

  @Override
  boolean endTask(def dryRun = false) {
    // stack must not be empty
    if (stack.empty) {
      return false
    }
    def stackBackup = new ArrayList<>(stack)
    try {
      // take the top state - it must be either ACTIVE or MIGRATING
      def state = stack.pop()
      switch (state) {
        case TaskState.ACTIVE:
          if (spanState == SpanState.CLOSED) {
            // span has already been closed
            if (stack.size() == 2 && stack.peek() == TaskState.ACTIVE) {
              // deal with the implicit span task
              stack.pop()
            }
          }
          break
        case TaskState.MIGRATING:
        // find first non-MIGRATING state
          def popped = 1
          while ((state = stack.pop()) == TaskState.MIGRATING) {
            popped++
          }

        // we can end up here with either INIT or ACTIVE states
          if (state == TaskState.TASK_INIT) {
            // no ACTIVE states on stack
            // restore the INIT state
            stack.push(TaskState.TASK_INIT)
            // make sure one of the MIGRATING states is removed
            popped--
          }
        // reconstruct the stack by pushing a number of MIGRATING states
          popped.times { stack.push(TaskState.MIGRATING) }
          break
        default:
          return false
      }
      return true
    } finally {
      if (dryRun) {
        stack.clear()
        stack.addAll(stackBackup)
      }
    }
  }

  @Override
  boolean suspendTask(def dryRun = false) {
    // stack must not be empty
    if (stack.empty) {
      return false
    }
    // can suspend only when the span has been open
    if (spanState != SpanState.OPEN) {
      return false
    }
    def stackBackup = new ArrayList<>(stack)
    try {
      // create a new MIGRATING task
      def state = stack.peek()
      switch (state) {
        case TaskState.ACTIVE:
        case TaskState.MIGRATING:
          stack.push(TaskState.MIGRATING)
          break
        default:
          return false
      }
      return true
    } finally {
      if (dryRun) {
        stack.clear()
        stack.addAll(stackBackup)
      }
    }
  }

  @Override
  boolean resumeTask(def dryRun = false) {
    // stack must not be empty
    if (stack.empty) {
      return false
    }
    // can not resume from INIT
    if (spanState == SpanState.SPAN_INIT) {
      return false
    }
    def stackBackup = new ArrayList<>(stack)
    try {
      def state = stack.pop()
      switch (state) {
        case TaskState.MIGRATING:
        // replace MIGRATING with ACTIVE
          stack.push(TaskState.ACTIVE)
          break
        case TaskState.ACTIVE:
        // find the closest non-ACTIVE entry
        // at this moment we will have at least 2 frames popped from the stack:
        //   - one when we entered this method
        //   - at least one in the loop looking for non-ACTIVE entry
          def popped = 2
          while ((state = stack.pop()) == TaskState.ACTIVE) {
            popped++
          }
        // the state should be MIGRATING so we can resume from it
          if (state != TaskState.MIGRATING) {
            return false
          }
        // replace MIGRATING with ACTIVE and restore the stack
          popped.times { stack.push(TaskState.ACTIVE) }
          break
        default:
          return false
      }

      return true
    } finally {
      if (dryRun) {
        stack.clear()
        stack.addAll(stackBackup)
      }
    }
  }

  @Override
  boolean endSpan(def dryRun = false) {
    // stack must not be empty
    if (stack.empty) {
      return false
    }
    // span state must be OPEN
    if (spanState != SpanState.OPEN) {
      return false
    }
    try {
      // there must be at least the implicit span task
      def ret = stack.peek() != TaskState.TASK_INIT
      // close the span
      spanState = SpanState.CLOSED
      return ret
    } finally {
      if (dryRun) {
        spanState = SpanState.OPEN
      }
    }
  }

  def taskStack() {
    return stack.toArray()
  }
}
