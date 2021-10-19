package com.datadog.appsec.report

import com.datadog.appsec.report.raw.events.attack.Attack010
import com.datadog.appsec.report.raw.events.attack._definitions.rule.Rule010
import com.datadog.appsec.report.raw.events.attack._definitions.rule_match.RuleMatch010
import com.datadog.appsec.test.JsonMatcher
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonWriter
import datadog.trace.api.TraceSegment
import datadog.trace.test.util.DDSpecification

import static org.hamcrest.MatcherAssert.assertThat

class InbandReportServiceImplTest extends DDSpecification {

  def reportService = new InbandReportServiceImpl()
  def traceSegment = Mock(TraceSegment)

  void 'NoOp implementation does nothing'() {
    when:
    InbandReportService.NoOp.INSTANCE.reportAttacks([], traceSegment)

    then:
    0 * traceSegment._
  }

  void 'does nothing when attacks is empty'() {
    when:
    reportService.reportAttacks([], traceSegment)

    then:
    0 * traceSegment._
  }

  void 'combines several attacks'() {
    given:
    String json
    def r1 = new Rule010(id: "rule1")
    def rm1 = new RuleMatch010(operator: "operator1")
    def a1 = new Attack010(type: "type1", rule: r1, ruleMatch: rm1)

    when:
    reportService.reportAttacks([a1], traceSegment)

    then:
    1 * traceSegment.setDataTop("appsec", _) >> {
      json = it[1].toString()
    }
    assertThat json, JsonMatcher.matchesJson('''
      {
         "triggers": [{
           "rule": {
             "id": "rule1"
           },
           "rule_match": {
             "highlight": [],
             "operator": "operator1",
             "parameters": []
           }
         }],
         "types": ["type1"],
         "rule_ids": ["rule1"]
      }''', false, false)
  }

  void 'serializer uses the cached value'() {
    given:
    JsonAdapter<String> mockAdapter = Mock(JsonAdapter)
    def count = 0
    mockAdapter.toJson(_ as JsonWriter,_ as String) >> {
      def c = count++
      JsonWriter writer = (JsonWriter) it[0]
      String string = (String) it[1]
      if (c == 0) {
        writer.beginObject().name("value").value(string).endObject().flush()
      } else {
        throw new IllegalArgumentException()
      }
    }
    def serializer = new InbandReportServiceImpl.Serializer<String>("whatever", mockAdapter)

    when:
    String json1 = serializer.toString()
    String json2 = serializer.toString()

    then:
    json1 == '{"value":"whatever"}'
    json1 == json2
  }

  void 'serializer handles exceptions'() {
    given:
    JsonAdapter<String> mockAdapter = Mock(JsonAdapter)
    mockAdapter.toJson(_ as JsonWriter,_ as String) >> { throw new IllegalArgumentException() }
    def serializer = new InbandReportServiceImpl.Serializer<String>("whatever", mockAdapter)

    when:
    String json = serializer.toString()

    then:
    json == '{}'
  }
}
