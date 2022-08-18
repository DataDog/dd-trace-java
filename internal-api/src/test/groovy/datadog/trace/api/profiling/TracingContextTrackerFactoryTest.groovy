package datadog.trace.api.profiling

import datadog.trace.test.util.DDSpecification

class TracingContextTrackerFactoryTest extends DDSpecification {
  def factory

  def cleanup() {
    TracingContextTrackerFactory.removeImplementation(factory)
  }

  def "sanity default"() {
    expect:
    !TracingContextTrackerFactory.isTrackingAvailable()
    TracingContextTrackerFactory.instance(null) == TracingContextTracker.EMPTY
  }

  def "sanity empty tracker"() {
    when:
    def tracker = TracingContextTrackerFactory.instance(null)

    then:
    tracker.version == 0
    tracker.activateContext()
    tracker.deactivateContext()
    tracker.maybeDeactivateContext()
    tracker.persist() == null
    tracker.persist({100}) == 0
    tracker.toString() == "Empty context tracker"
    !tracker.release()



    when:
    def delayed = tracker.asDelayed()

    then:
    delayed == TracingContextTracker.DelayedTracker.EMPTY
    delayed.getDelay(null) == -1
    delayed.compareTo(null) == -1
    delayed.cleanup()
  }

  def "custom implementation registration"() {
    setup:
    factory = new TestTracingContextTrackerFactory()

    expect:
    TracingContextTrackerFactory.registerImplementation(factory)
    !TracingContextTrackerFactory.registerImplementation(factory)
    TracingContextTrackerFactory.isTrackingAvailable()
  }

  def "custom factory usage"() {
    setup:
    factory = new TestTracingContextTrackerFactory()
    TracingContextTrackerFactory.registerImplementation(factory)

    when:
    def tracker = TracingContextTrackerFactory.instance(null)

    then:
    tracker != null
    tracker != TracingContextTracker.EMPTY
    tracker instanceof TestTracingContextTracker
  }
}
