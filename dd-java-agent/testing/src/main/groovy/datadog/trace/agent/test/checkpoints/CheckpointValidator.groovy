package datadog.trace.agent.test.checkpoints

class CheckpointValidator {
  private static Set<CheckpointValidationMode> excludedValidations = EnumSet.noneOf(CheckpointValidationMode)

  /**
   * Exclude some validations modes from the checks for the current test case.
   * By default all validation modes defined by {@linkplain CheckpointValidationMode} are enabled.
   *
   * To verify which tests have the annotation and which are passing, run:
   *  $> VALIDATE_CHECKPOINTS=true FORCE_VALIDATE_CHECKPOINTS=true ./gradlew \
   *      `grep -r -l "CheckpointValidator.excludeValidations" dd-java-agent/instrumentation/ | \
   *            sed "s/\/build\/.*$//" | \
   *            sed "s/\/src\/.*$//" | \
   *            tr '/' ':' | \
   *            sort -u | \
   *            xargs -n 1 -I{} echo :{}:test` \
   *          --continue --no-parallel
   * Then check that all of the classes which use CheckpointValidator.excludeValidations are indeed failing.
   *
   * @param modes validation modes
   */
  static void excludeValidations(Set<CheckpointValidationMode> modes) {
    // if `FORCE_VALIDATE_CHECKPOINTS` is defined, make sure we do not exclude any test
    // if (Boolean.parseBoolean(System.getenv("FORCE_VALIDATE_CHECKPOINTS"))) {
    //   return;
    // }
    excludedValidations.addAll(modes)
  }

  static void excludeValidations(CheckpointValidationMode... modes) {
    excludeValidations(EnumSet.of(modes))
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
      if (!excludedValidations.contains(CheckpointValidationMode.THREAD_SANITY)) {
        // first sanity check that each thread timeline starts with a 'startSpan' or 'resume'
        // and ends with 'endSpan', 'endTask' or 'suspend'
        def startEvent = events[0]
        def endEvent = events[events.size() - 1]
        if (startEvent.name != "startSpan" && startEvent.name != "resume") {
          invalidEvents.add([startEvent, CheckpointValidationMode.THREAD_SANITY])
        }
        if (endEvent.name != "endSpan" && endEvent.name != "suspend" && endEvent.name != "endTask") {
          invalidEvents.add([endEvent, CheckpointValidationMode.THREAD_SANITY])
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
    def suspendResumeValidator = new SuspendResumeValidator()
    def threadSequenceValidator = new ThreadSequenceValidator()
    for (def event : events) {
      if (!excludedValidations.contains(CheckpointValidationMode.SUSPEND_RESUME)) {
        if (!suspendResumeValidator.onEvent(event)) {
          invalidEvents.add([event, CheckpointValidationMode.SUSPEND_RESUME])
        }
      }
      if (!excludedValidations.contains(CheckpointValidationMode.THREAD_SEQUENCE)) {
        if (!threadSequenceValidator.onEvent(event)) {
          invalidEvents.add([event, CheckpointValidationMode.THREAD_SEQUENCE])
        }
      }
    }
    // run end-sequence validations
    if (!excludedValidations.contains(CheckpointValidationMode.SUSPEND_RESUME)) {
      if (!suspendResumeValidator.endSequence()) {
        for (def event : events) {
          if (event.name == "suspend" || event.name == "resume") {
            invalidEvents.add([event, CheckpointValidationMode.SUSPEND_RESUME])
          }
        }
      }
    }
  }
}
