package com.datadog.appsec.report

import com.datadog.appsec.report.raw.events.attack.Attack010
import com.datadog.appsec.util.JvmTime
import spock.lang.Specification

class DefaultReportStrategySpecification extends Specification {

  JvmTime jvmTime = Mock(JvmTime)
  ReportStrategy testee = new ReportStrategy.Default(jvmTime)

  void 'the first event is immediately flushed'() {
    setup:
    jvmTime.nanoTime() >> System.nanoTime()

    expect:
    testee.shouldFlush(new Attack010(type: 'my type')) == true
  }

  void 'subsequent event of same type should wait 1 min'() {
    given:
    jvmTime.nanoTime() >>> [
      1000_000_000_000L,
      1000_000_000_000L + 60_000_000_000L,
      1000_000_000_000L + 60_000_000_001L,
    ]

    expect:
    testee.shouldFlush(new Attack010(type: 'my type')) == true
    testee.shouldFlush(new Attack010(type: 'my type')) == false
    testee.shouldFlush(new Attack010(type: 'my type')) == true
  }

  void 'subsequent event of different type should wait 5 seconds'() {
    given:
    jvmTime.nanoTime() >>> [
      1000_000_000_000L,
      1000_000_000_000L + 5_000_000_000L,
      1000_000_000_000L + 5_000_000_001L,
    ]

    expect:
    testee.shouldFlush(new Attack010(type: 'my type')) == true
    testee.shouldFlush(new Attack010(type: 'another type')) == false
    testee.shouldFlush(new Attack010(type: 'another type')) == true
  }

  void 'should flush when max queued items are item'() {
    setup:
    jvmTime.nanoTime() >> System.nanoTime()

    expect:
    testee.shouldFlush(new Attack010(type: 'my type')) == true
    50.times { testee.shouldFlush(new Attack010(type: 'my type')) == false }
    testee.shouldFlush(new Attack010(type: 'my type')) == true
  }
}
