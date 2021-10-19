package com.datadog.appsec.report

import com.datadog.appsec.report.raw.dtos.intake.IntakeBatch
import com.datadog.appsec.report.raw.events.attack.Attack010
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
    boolean shouldFlush(@Nonnull Attack010 attack) {
      true
    }
  }

  void 'NoOp implementation does nothing'() {
    setup:
    testee = ReportService.NoOp.INSTANCE
    testee.reportAttack(null)
  }


  void 'calls AppSecApi and schedules task'() {
    String json
    Attack010 attack = new Attack010(type: 'waf')
    testee = new ReportServiceImpl(
      api, AlwaysFlush.INSTANCE, scheduler)

    when:
    testee.reportAttack(attack)

    then:
    1 * scheduler.scheduleAtFixedRate(_, testee, 5, 30, TimeUnit.SECONDS) >>
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
              "type": "waf"
            }
         ],
         "protocol_version" : 1
      }''', false, true)
  }

  void 'does not flush if not told to'() {
    Attack010 attack = new Attack010(type: 'waf')

    when:
    testee = new ReportServiceImpl(api, { false } as ReportStrategy, scheduler)
    testee.reportAttack(attack)

    then:
    0 * api._(*_)
  }

  void 'the task flushes if the report strategy indicates so'() {
    Attack010 attack = new Attack010(type: 'waf')
    def reportResponsesStack = [false, false, true, true]
    AgentTaskScheduler.Task task
    def scheduled = new AgentTaskScheduler.Scheduled(new Object())

    when:
    testee = new ReportServiceImpl(
      api, {reportResponsesStack.pop() /* pops off the front*/ } as ReportStrategy,
      scheduler)
    testee.reportAttack(attack)

    then:
    1 * scheduler.scheduleAtFixedRate(_, { it.is(testee) }, 5, 30, TimeUnit.SECONDS) >>
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
