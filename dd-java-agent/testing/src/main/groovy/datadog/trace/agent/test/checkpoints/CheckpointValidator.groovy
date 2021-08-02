package datadog.trace.agent.test.checkpoints

class CheckpointValidator {
  private static Set<CheckpointValidationMode> excludedValidations = EnumSet.noneOf(CheckpointValidationMode)

  /**
   * Exclude some validations modes from the checks for the current test case.
   * By default all validation modes defined by {@linkplain CheckpointValidationMode} are enabled.
   * @param modes validation modes
   */
  static void excludeValidations(EnumSet<CheckpointValidationMode> modes) {
    excludedValidations.addAll(modes)
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
      if (!excludedValidations.contains(CheckpointValidationMode.SEQUENCE)) {
        def perSpanValidator = new CompositeValidator(new SuspendResumeValidator(), new ThreadSequenceValidator())
        for (def event : events) {
          perSpanValidator.onEvent(event)
        }
        perSpanValidator.onEnd()
        invalidEvents.addAll(perSpanValidator.invalidEvents())
      }
    }

    // validate per-thread sequence
    for (def events : threadEvents.values()) {
      if (!excludedValidations.contains(CheckpointValidationMode.INTERVALS)) {
        def perThreadValidator = new IntervalValidator()
        for (def event : events) {
          perThreadValidator.onEvent(event)
        }
        perThreadValidator.endSequence()
        invalidEvents.addAll(perThreadValidator.invalidEvents)
      }
    }

    return invalidEvents
  }
}
