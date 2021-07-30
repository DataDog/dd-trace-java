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

  static Set<Event> validate(def spanEvents, def threadEvents, def orderedEvents) {
    if (!excludedValidations.empty) {
      System.err.println("Checkpoint validator is running with the following checks disabled: ${excludedValidations}\n")
    }
    def invalidEvents = new HashSet<>()
    for (def events : spanEvents.values()) {
      // validate global span sequence
      validateSpanSequence(events, invalidEvents)
    }

    for (def events : threadEvents.values()) {
      if (!excludedValidations.contains(CheckpointValidationMode.SEQUENCE)) {
        // first sanity check that each thread timeline starts with a 'startSpan' or 'resume'
        // and ends with 'endSpan', 'endTask' or 'suspend'
        def startEvent = events[0]
        def endEvent = events[events.size() - 1]
        if (startEvent.name != "startSpan" && startEvent.name != "resume") {
          invalidEvents.add([startEvent, CheckpointValidationMode.SEQUENCE])
        }
        if (endEvent.name != "endSpan" && endEvent.name != "suspend" && endEvent.name != "endTask") {
          invalidEvents.add([endEvent, CheckpointValidationMode.SEQUENCE])
        }
      }
      if (!excludedValidations.contains(CheckpointValidationMode.INTERVALS)) {
        // do more thorough checks eg. for overlapping spans
        IntervalValidator checker = new IntervalValidator()
        for (def event : events) {
          if (!checker.onEvent(event)) {
            invalidEvents.add([event, CheckpointValidationMode.INTERVALS])
          }
        }
      }
    }
    return invalidEvents
  }

  private static void validateSpanSequence(def events, def invalidEvents) {
    if (!excludedValidations.contains(CheckpointValidationMode.SEQUENCE)) {
      def suspendResumeValidator = new SuspendResumeValidator()
      def threadSequenceValidator = new ThreadSequenceValidator()
      for (def event : events) {
        if (!suspendResumeValidator.onEvent(event)) {
          invalidEvents.add([event, CheckpointValidationMode.SEQUENCE])
        }
        if (!threadSequenceValidator.onEvent(event)) {
          invalidEvents.add([event, CheckpointValidationMode.SEQUENCE])
        }
      }
      // run end-sequence validations
      if (!suspendResumeValidator.endSequence()) {
        for (def event : events) {
          if (event.name == "suspend" || event.name == "resume") {
            invalidEvents.add([event, CheckpointValidationMode.SEQUENCE])
          }
        }
      }
    }
  }
}
