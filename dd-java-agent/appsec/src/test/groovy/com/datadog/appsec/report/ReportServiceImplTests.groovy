package com.datadog.appsec.report

import com.datadog.appsec.report.raw.dtos.intake.IntakeBatch
import com.datadog.appsec.report.raw.events.attack.Attack010
import com.datadog.appsec.test.JsonMatcher
import com.squareup.moshi.JsonAdapter
import spock.lang.Specification

import static org.hamcrest.MatcherAssert.assertThat

class ReportServiceImplTests extends Specification {

  AppSecApi api = Mock()

  void 'calls AppSecApi'() {
    String json
    Attack010 attack = new Attack010(type: 'waf')

    when:
    ReportServiceImpl testee = new ReportServiceImpl(
      api, ReportStrategy.AlwaysFlush.INSTANCE)
    testee.reportAttack(attack)

    then:
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
    ReportServiceImpl testee = new ReportServiceImpl(
      api, { false } as ReportStrategy)
    testee.reportAttack(attack)

    then:
    0 * _
  }
}
