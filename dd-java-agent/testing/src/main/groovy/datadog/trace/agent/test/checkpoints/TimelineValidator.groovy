package datadog.trace.agent.test.checkpoints

class TimelineValidator {
  // To verify which tests have the annotation and which are passing, run:
  //  $> VALIDATE_CHECKPOINTS=true FORCE_VALIDATE_CHECKPOINTS=true ./gradlew \
  //      `grep -r -l "TimelineValidator.ignoreTest" dd-java-agent/instrumentation/ | \
  //            sed "s/\/build\/.*$//" | \
  //            sed "s/\/src\/.*$//" | \
  //            tr '/' ':' | \
  //            sort -u | \
  //            xargs -n 1 -I{} echo :{}:test` \
  //          --continue --no-parallel
  // Then check that all of the classes which use TimelineValidator.ignoreTest are indeed failing.

  @Deprecated
  static boolean ignoreTest() {
    return Boolean.parseBoolean(System.getenv("VALIDATE_CHECKPOINTS")) &&
      !Boolean.parseBoolean(System.getenv("FORCE_VALIDATE_CHECKPOINTS"))
  }

  static Set<Event> validate(def spanEvents, def threadEvents, def orderedEvents) {
    def invalidEvents = new HashSet<>()
    for (def events : spanEvents.values()) {
      // validate global span sequence
      validateSpanSequence(events, invalidEvents)
    }

    for (def events : threadEvents.values()) {
      // first sanity check that each thread timeline starts with a 'startSpan' or 'resume'
      // and ends with 'endSpan', 'endTask' or 'suspend'
      def startEvent = events[0]
      def endEvent = events[events.size() - 1]
      if (startEvent.name != "startSpan" && startEvent.name != "resume") {
        invalidEvents.add(startEvent)
      }
      if (endEvent.name != "endSpan" && endEvent.name != "suspend" && endEvent.name != "endTask") {
        invalidEvents.add(endEvent)
      }
      // do more thorough checks eg. for overlapping spans
      ThreadContextTracker tracker = new ThreadContextTracker()
      for (def event : events) {
        if (!tracker.onEvent(event)) {
          invalidEvents.add(event)
        }
      }
    }
    return invalidEvents
  }

  private static void validateSpanSequence(def events, def invalidEvents) {
    def tracker = new SpanContextTracker()
    for (def event : events) {
      if (!tracker.onEvent(event)) {
        invalidEvents.add(event)
      }
    }
  }
}
