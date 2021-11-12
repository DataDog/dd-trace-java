package com.datadog.appsec.report


import com.datadog.appsec.report.raw.contexts._definitions.AllContext
import com.datadog.appsec.report.raw.contexts.host.Host
import com.datadog.appsec.report.raw.contexts.http.Http100
import com.datadog.appsec.report.raw.contexts.http.HttpRequest100
import com.datadog.appsec.report.raw.contexts.http.HttpResponse100
import com.datadog.appsec.report.raw.contexts.library.Library
import com.datadog.appsec.report.raw.contexts.service.Service
import com.datadog.appsec.report.raw.contexts.span.Span
import com.datadog.appsec.report.raw.contexts.tags.Tags
import com.datadog.appsec.report.raw.contexts.trace.Trace
import com.datadog.appsec.report.raw.dtos.intake.IntakeBatch
import com.datadog.appsec.report.raw.events.AppSecEvent100
import com.datadog.appsec.report.raw.events.Parameter100
import com.datadog.appsec.report.raw.events.Rule100
import com.datadog.appsec.report.raw.events.RuleMatch100
import org.junit.Test

import java.time.LocalDateTime
import java.time.ZoneId

import static com.datadog.appsec.test.JsonMatcher.matchesJson
import static org.hamcrest.MatcherAssert.assertThat

class AttackSerializationTests {

  private static toInstant(LocalDateTime timeInUTC) {
    timeInUTC.atZone(ZoneId.of('UTC')).toInstant()
  }

  @Test
  void 'serialize sample attack'() {
    AppSecEvent100 event = new AppSecEvent100.AppSecEvent100Builder()
      .withEventId('41174c05-dc35-4fa1-90cd-f97b6d5f3135')
      .withEventType('appsec')
      .withEventVersion('1.0.0')
      .withDetectedAt(toInstant(LocalDateTime.of(2021, 5, 21, 1, 2, 3, 456789000)))
      .withRule(new Rule100.Rule100Builder()
      .withId('rule_942160')
      .withName('Detects blind sqli tests using sleep() or benchmark()')
      .build())
      .withRuleMatch(new RuleMatch100.RuleMatch100Builder()
      .withOperator('match_regex')
      .withOperatorValue('(?i:sleep\\\\(\\\\s*?\\\\d*?\\\\s*?\\\\)|benchmark\\\\(.*?\\\\,.*?\\\\))')
      .withParameters([
        new Parameter100.Parameter100Builder()
        .withAddress('ARGS:text')
        .withKeyPath(['key'])
        .withValue('1))/**/and/**/pg_sleep(7)--1')
        .build()
      ])
      .withHighlight(['sleep(7)'])
      .build())
      .withContext(new AllContext.AllContextBuilder()
      .withHost(new Host.HostBuilder()
      .withContextVersion('0.1.0')
      .withOsType('Linux')
      .withHostname('i-0123456789012345'))
      .withHttp(new Http100.Http100Builder()
      .withContextVersion('1.0.0')
      .withRequest(new HttpRequest100.HttpRequest100Builder()
      .withMethod('PUT')
      .withResource('/article/:article_id')
      .withRemoteIp('192.168.10.12')
      .withRemotePort(32541)
      .withHeaders(['user-agent': ['my user agent']])
      .build())
      .withResponse(new HttpResponse100.HttpResponse100Builder()
      .withStatus(400)
      .withBlocked(Boolean.FALSE)
      .build())
      .build())
      .withService(new Service.ServiceBuilder()
      .withContextVersion('0.1.0')
      .withName('web-front')
      .withEnvironment('staging')
      .withVersion('0.1.2-beta1')
      .build())
      .withSpan(new Span.SpanBuilder()
      .withContextVersion('0.1.0')
      .withId('012345678901234567890123456789')
      .build())
      .withTags(new Tags.TagsBuilder()
      .withContextVersion('0.1.0')
      .withValues(['tag1', 'tag2', 'tag3'] as Set<String>)
      .build())
      .withTrace(new Trace.TraceBuilder()
      .withContextVersion('0.1.0')
      .withId('012345678901234567890123456789')
      .build())
      .withLibrary(new Library.LibraryBuilder()
      .withContextVersion('0.1.0')
      .withRuntimeType('python')
      .withRuntimeVersion('3.7.9')
      .withLibVersion('0.1.0-beta1')
      .build())
      .build())
      .build()

    IntakeBatch batch = new IntakeBatch.IntakeBatchBuilder()
      .withProtocolVersion(1)
      .withIdempotencyKey('35ce0157-39a3-478a-95aa-342f11b37d0e')
      .withEvents([event])
      .build()

    String result = ReportSerializer.intakeBatchAdapter.toJson(batch)

    def expected = '''
      {
        "protocol_version": 1,
        "idempotency_key": "35ce0157-39a3-478a-95aa-342f11b37d0e",
        "events": [{
          "event_id": "41174c05-dc35-4fa1-90cd-f97b6d5f3135",
          "event_type": "appsec",
          "event_version": "1.0.0",
          "detected_at": "2021-05-21T01:02:03.456789Z",
          "rule": {
            "id": "rule_942160",
            "name": "Detects blind sqli tests using sleep() or benchmark()"
          },
          "rule_match": {
            "operator": "match_regex",
            "operator_value": "(?i:sleep\\\\\\\\(\\\\\\\\s*?\\\\\\\\d*?\\\\\\\\s*?\\\\\\\\)|benchmark\\\\\\\\(.*?\\\\\\\\,.*?\\\\\\\\))",
            "parameters": [
              {
                "address": "ARGS:text",
                "key_path": ["key"],
                "value": "1))/**/and/**/pg_sleep(7)--1"
              }
            ],
            "highlight": [
              "sleep(7)"
            ]
          },
          "context": {
            "tags": {
              "context_version" : "0.1.0",
              "values": ["tag1", "tag2", "tag3"]
            },
            "host": {
              "instance": {
                "context_version" : "0.1.0",
                "hostname" : "i-0123456789012345",
                "os_type" : "Linux"
              }
            },
            "http": {
              "context_version": "1.0.0",
              "request": {
                "method": "PUT",
                "resource": "/article/:article_id",
                "remote_ip": "192.168.10.12",
                "remote_port": 32541,
                "headers": {
                  "user-agent": [
                    "my user agent"
                  ]
                }
              },
              "response": {
                "status": 400,
                "blocked": false
              }
            },
            "service": {
              "context_version": "0.1.0",
              "name": "web-front",
              "environment": "staging",
              "version": "0.1.2-beta1"
            },
            "span": {
              "context_version": "0.1.0",
              "id": "012345678901234567890123456789"
            },
            "trace": {
              "context_version": "0.1.0",
              "id": "012345678901234567890123456789"
            },
            "library": {
              "context_version": "0.1.0",
              "runtime_type": "python",
              "runtime_version": "3.7.9",
              "lib_version": "0.1.0-beta1"
            }
          }
        }]
      }
    '''

    assertThat result, matchesJson(expected)
  }
}
