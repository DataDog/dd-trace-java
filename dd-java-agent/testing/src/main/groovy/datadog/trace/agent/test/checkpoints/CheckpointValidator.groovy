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
    // if `FORCE_VALIDATE_CHECKPOINTS` is defined, make sure we do not exclude any validation
    if (validationMode("force")) {
      return
    }
    excludedValidations.addAll(EnumSet.of(mode0, modes))
  }

  static Set<CheckpointValidationMode> getExcludedValidations() {
    return excludedValidations
  }

  static void clear() {
    // reset the validation modes after each test case
    excludedValidations = EnumSet.noneOf(CheckpointValidationMode)
  }

  static validate(def spanEvents, def threadEvents, def orderedEvents, def trackedSpanIds, PrintStream out) {
    def invalidEvents = new HashSet()
    // validate per-span sequence
    for (def events : spanEvents.values()) {
      def perSpanValidator = new ThreadSequenceValidator()
      for (def event : events) {
        if (trackedSpanIds.contains(event.spanId)) {
          perSpanValidator.onEvent(event)
        }
      }
      perSpanValidator.endSequence()
      invalidEvents.addAll(perSpanValidator.invalidEvents)
    }

    // validate per-thread sequence
    for (def events : threadEvents.values()) {
      def perThreadValidator = new IntervalValidator()
      for (def event : events) {
        if (trackedSpanIds.contains(event.spanId)) {
          perThreadValidator.onEvent(event)
        }
      }
      perThreadValidator.endSequence()
      invalidEvents.addAll(perThreadValidator.invalidEvents)
    }

    if (!invalidEvents.empty) {
      out.println("=== Invalid checkpoint events encountered: ${invalidEvents*.mode.toSet().sort()}")
      invalidEvents*.event.spanId.toSet().sort().each { spanId ->
        orderedEvents.findAll { event -> event.spanId == spanId }.each { event ->
          def invalidEvent = invalidEvents.find { it.event == event }
          if (invalidEvent != null) {
            out.println(invalidEvent)
          } else {
            out.println(event)
          }
        }
      }
    }

    return invalidEvents.collectEntries {
      [(it) : !excludedValidations.contains(it.mode)]
    }
  }

  static validationMode(String mode) {
    return System.getenv("VALIDATE_CHECKPOINTS") != null && System.getenv("VALIDATE_CHECKPOINTS").contains(mode)
  }
}
