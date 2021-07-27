package datadog.trace.agent.test.checkpoints

class TimelineValidator {
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
