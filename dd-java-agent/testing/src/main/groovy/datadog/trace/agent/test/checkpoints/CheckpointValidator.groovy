package datadog.trace.agent.test.checkpoints

class CheckpointValidator {
  private static Set<CheckpointValidationMode> excludedValidations = EnumSet.noneOf(CheckpointValidationMode)

  /**
   * This method should not be added to any new integrations as it would imply that a new
   * integration is broken for Tracing Context (Code Hotspots). If you are unsure, reach out
   * to the profiling team.
   *
   * Exclude some validations modes from the checks for the current test case.
   * By default all validation modes defined by {@linkplain CheckpointValidationMode} are enabled.
   * @param modes validation modes
   */
  static void excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(CheckpointValidationMode mode0, CheckpointValidationMode... modes) {
    if (Boolean.parseBoolean(System.getenv("FORCE_VALIDATE_CHECKPOINTS"))) {
      return
    }
    excludedValidations.addAll(EnumSet.of(mode0, modes))
  }

  static Set<CheckpointValidationMode> getExcludedValidations() {
    return excludedValidations.clone()
  }

  static void clear() {
    // reset the validation modes after each test case
    excludedValidations = EnumSet.noneOf(CheckpointValidationMode)
  }

  static validate(def spanEvents, def threadEvents, def orderedEvents) {
    if (!excludedValidations.empty) {
      System.err.println("Checkpoint validator is running with the following checks disabled: ${excludedValidations}\n")
    }

    def invalidEvents = new HashSet()
    // validate per-span sequence
    for (def events : spanEvents.values()) {
      def perSpanValidator = new CompositeValidator(new SuspendResumeValidator(), new ThreadSequenceValidator())
      for (def event : events) {
        perSpanValidator.onEvent(event)
      }
      perSpanValidator.onEnd()
      invalidEvents.addAll(perSpanValidator.invalidEvents())
    }

    // validate per-thread sequence
    for (def events : threadEvents.values()) {
      def perThreadValidator = new IntervalValidator()
      for (def event : events) {
        perThreadValidator.onEvent(event)
      }
      perThreadValidator.endSequence()
      invalidEvents.addAll(perThreadValidator.invalidEvents)
    }

    if (!invalidEvents.empty) {
      System.err.println("=== Invalid checkpoint events encountered")
      invalidEvents.each { System.err.println(it) }
    }

    return invalidEvents.collectEntries {
      [(it) : !excludedValidations.contains(it.mode)]
    }
  }
}
