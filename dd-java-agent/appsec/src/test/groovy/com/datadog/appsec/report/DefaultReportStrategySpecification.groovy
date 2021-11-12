package com.datadog.appsec.report

import com.datadog.appsec.report.raw.events.AppSecEvent100
import com.datadog.appsec.util.JvmTime
import datadog.trace.test.util.DDSpecification

class DefaultReportStrategySpecification extends DDSpecification {

  JvmTime jvmTime = Mock(JvmTime)
  ReportStrategy testee = new ReportStrategy.Default(jvmTime)

  void 'the first event is immediately flushed'() {
    setup:
    jvmTime.nanoTime() >> System.nanoTime()

    expect:
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == true
  }

  void 'subsequent event of same type should wait 1 min'() {
    given:
    jvmTime.nanoTime() >>> [
      1000_000_000_000L,
      1000_000_000_000L + 60_000_000_000L,
      1000_000_000_000L + 60_000_000_001L,
    ]

    expect:
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == true
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == false
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == true
  }

  void 'subsequent event of different type should wait 5 seconds'() {
    given:
    jvmTime.nanoTime() >>> [
      1000_000_000_000L,
      1000_000_000_000L + 5_000_000_000L,
      1000_000_000_000L + 5_000_000_001L,
    ]

    expect:
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == true
    testee.shouldFlush(new AppSecEvent100(eventType: 'another type')) == false
    testee.shouldFlush(new AppSecEvent100(eventType: 'another type')) == true
  }

  void 'should flush when max queued items are item'() {
    setup:
    jvmTime.nanoTime() >> System.nanoTime()

    expect:
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == true
    50.times { testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == false }
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == true
  }

  void 'if no new attack then the wait time is 1 min'() {
    setup:
    jvmTime.nanoTime() >>> [
      1000_000_000_000L,
      1000_000_000_000L + 1L,
      1000_000_000_000L + 60_000_000_000L,
      1000_000_000_000L + 60_000_000_001L,
    ]

    expect:
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == true
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == false
    testee.shouldFlush() == false
    testee.shouldFlush() == true
  }

  void 'if no new attack no flush unless there is something queued'() {
    setup:
    jvmTime.nanoTime() >>> [1000_000_000_000L, 1000_000_000_000L + 60_000_000_001L,]

    expect:
    testee.shouldFlush(new AppSecEvent100(eventType: 'my type')) == true
    testee.shouldFlush() == false
  }
}
