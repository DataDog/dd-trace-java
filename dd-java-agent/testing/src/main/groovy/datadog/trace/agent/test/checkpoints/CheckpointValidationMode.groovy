package datadog.trace.agent.test.checkpoints

enum CheckpointValidationMode {
  /**
   * Asserts that a sequence of events related to a thread is sane.
   * <ul>
   *   <li>Sequence starts with either START_SPAN or RESUME_SPAN
   *   <li>Sequence ends with either SUSPEND_SPAN or END_SPAN
   *   <li>On each thread the sequence must start with START_SPAN or RESUME_SPAN</li>
   *   <li>Each SUSPEND_SPAN must be preceded by START_SPAN or RESUME_SPAN</li>
   *   <li>Each RESUME_SPAN must be either the first in sequence or preceded by SUSPEND_SPAN</li>
   *   <li>Between two RESUME_SPAN event there must be a SUSPEND_SPAN event</li>
   *   <li>END_TASK may appear only after START_SPAN or RESUME_SPAN</li>
   *   <li>Between two END_TASK events there must be a RESUME_SPAN event</li>
   * </ul>
   */
  THREAD_SEQUENCE,
  /**
   * Validates the intervals of activity for all spans on a single thread.
   * Makes sure that each time point can be assigned to a single active span.
   * Since intervals are used in the profiling backend this validates the
   * acceptability of the checkpoint event stream by the backend.
   */
  INTERVALS
}
