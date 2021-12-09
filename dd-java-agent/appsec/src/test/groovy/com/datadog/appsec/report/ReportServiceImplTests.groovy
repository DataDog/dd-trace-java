package com.datadog.appsec.report

import com.datadog.appsec.report.raw.events.AppSecEvent100
import com.datadog.appsec.report.raw.dtos.intake.IntakeBatch
import com.datadog.appsec.test.JsonMatcher
import com.squareup.moshi.JsonAdapter
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler

import javax.annotation.Nonnull
import java.util.concurrent.TimeUnit

import static org.hamcrest.MatcherAssert.assertThat

class ReportServiceImplTests extends DDSpecification {

  ReportService testee
  AppSecApi api = Mock()
  ReportServiceImpl.TaskScheduler scheduler = Mock()

  void cleanup() {
    testee.close()
  }

  enum AlwaysFlush implements ReportStrategy {
    INSTANCE

    @Override
    boolean shouldFlush() {
      true
    }

    @Override
    boolean shouldFlush(@Nonnull AppSecEvent100 event) {
      true
    }
  }

  void 'NoOp implementation does nothing'() {
    setup:
    testee = ReportService.NoOp.INSTANCE
    testee.reportEvents(null, null)
  }


  void 'calls AppSecApi and schedules task'() {
    setup:
    String json
    AppSecEvent100 event = new AppSecEvent100(eventType: 'appsec')
    testee = new ReportServiceImpl(
      api, AlwaysFlush.INSTANCE, scheduler)

    when:
    testee.reportEvents([event], null)

    then:
    1 * scheduler.scheduleAtFixedRate(_, testee, 5, 60, TimeUnit.SECONDS) >>
      new AgentTaskScheduler.Scheduled(new Object())
    1 * api.sendIntakeBatch(
      _ as IntakeBatch,
      _ as JsonAdapter<List<IntakeBatch>>) >> {
        json = it[1].toJson(it[0]); null
      }
    assertThat json, JsonMatcher.matchesJson('''
      {
         "events" : [
            {
              "event_type": "appsec"
            }
         ],
         "protocol_version" : 1
      }''', false, true)
  }

  void 'does not flush if not told to'() {
    setup:
    AppSecEvent100 event = new AppSecEvent100(eventType: 'appsec')

    when:
    testee = new ReportServiceImpl(api, { false } as ReportStrategy, scheduler)
    testee.reportEvents([event], null)

    then:
    0 * api._(*_)
  }

  void 'the task flushes if the report strategy indicates so'() {
    setup:
    AppSecEvent100 event = new AppSecEvent100(eventType: 'appsec')
    def reportResponsesStack = [false, false, true, true]
    AgentTaskScheduler.Task task
    def scheduled = new AgentTaskScheduler.Scheduled(new Object())

    when:
    testee = new ReportServiceImpl(
      api, {reportResponsesStack.pop() /* pops off the front*/ } as ReportStrategy,
      scheduler)
    testee.reportEvents([event], null)

    then:
    1 * scheduler.scheduleAtFixedRate(_, { it.is(testee) }, 5, 60, TimeUnit.SECONDS) >>
    { task = it[0]; scheduled }
    0 * api._(*_)

    when:
    // flush sequence: false, true, true. nothing to send on 3rd iter
    3.times { task.run(testee) }

    then:
    1 * api.sendIntakeBatch(_, _)

    when:
    testee.close()

    then:
    scheduled.get() == null
  }
}
