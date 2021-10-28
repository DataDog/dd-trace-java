package com.datadog.appsec.report


import com.datadog.appsec.report.raw.contexts._definitions.AllContext
import com.datadog.appsec.report.raw.contexts.actor.Actor010
import com.datadog.appsec.report.raw.contexts.actor.Identifiers
import com.datadog.appsec.report.raw.contexts.actor.Ip
import com.datadog.appsec.report.raw.contexts.host.Host010
import com.datadog.appsec.report.raw.contexts.http.Http010
import com.datadog.appsec.report.raw.contexts.http.HttpHeaders
import com.datadog.appsec.report.raw.contexts.http.HttpRequest
import com.datadog.appsec.report.raw.contexts.http.HttpResponse
import com.datadog.appsec.report.raw.contexts.service_stack.Service
import com.datadog.appsec.report.raw.contexts.service_stack.ServiceStack010
import com.datadog.appsec.report.raw.contexts.span.Span010
import com.datadog.appsec.report.raw.contexts.trace.Trace010
import com.datadog.appsec.report.raw.contexts.tracer.Tracer010
import com.datadog.appsec.report.raw.dtos.intake.IntakeBatch
import com.datadog.appsec.report.raw.events.attack.Attack010
import com.datadog.appsec.report.raw.events.attack._definitions.rule.Rule010
import com.datadog.appsec.report.raw.events.attack._definitions.rule_match.Parameter
import com.datadog.appsec.report.raw.events.attack._definitions.rule_match.RuleMatch010
import datadog.trace.test.util.DDSpecification

import java.time.LocalDateTime
import java.time.ZoneId

import static com.datadog.appsec.test.JsonMatcher.matchesJson
import static org.hamcrest.MatcherAssert.assertThat

class AttackSerializationTests extends DDSpecification {

  private static toInstant(LocalDateTime timeInUTC) {
    timeInUTC.atZone(ZoneId.of('UTC')).toInstant()
  }

  void 'serialize sample attack'() {
    Attack010 attack = new Attack010.Attack010Builder()
      .withEventId('41174c05-dc35-4fa1-90cd-f97b6d5f3135')
      .withEventType('appsec.threat.attack')
      .withEventVersion('0.1.0')
      .withDetectedAt(toInstant(LocalDateTime.of(2021, 5, 21, 1, 2, 3, 456789000)))
      .withType('sql_injection')
      .withBlocked(Boolean.TRUE)
      .withRule(new Rule010.Rule010Builder()
      .withId('rule_942160')
      .withName('Detects blind sqli tests using sleep() or benchmark()')
      .withSet('sql_injection')
      .build())
      .withRuleMatch(new RuleMatch010.RuleMatch010Builder()
      .withOperator('@rx')
      .withOperatorValue('(?i:sleep\\\\(\\\\s*?\\\\d*?\\\\s*?\\\\)|benchmark\\\\(.*?\\\\,.*?\\\\))')
      .withParameters([
        new Parameter.ParameterBuilder()
        .withName('ARGS:text')
        .withValue('1))/**/and/**/pg_sleep(7)--1')
        .build()
      ])
      .withHighlight(['sleep(7)'])
      .withHasServerSideMatch(Boolean.TRUE).build())
      .withContext(new AllContext.AllContextBuilder()
      .withActor(new Actor010.Actor010Builder()
      .withContextVersion('0.1.0')
      .withIp(new Ip.IpBuilder()
      .withAddress('127.0.0.1')
      .build())
      .withIdentifiers(new Identifiers.IdentifiersBuilder()
      .withProperty('sqreen_id', 'test_id')
      .withProperty('email', 'test@domain.tld').build())
      .withId('ewogICAgICAgIHNxcmVlbl9pZDogdGVzdF9pZCwKICAgICAgICBlbWFpbDogdGVzdEBkb21haW4udGxkCiAgICAgIH0K')
      .build())
      .withHost(new Host010.Host010Builder()
      .withContextVersion('0.1.0')
      .withOsType('Linux')
      .withHostname('i-0123456789012345')
      .build())
      .withHttp(new Http010.Http010Builder()
      .withContextVersion('0.1.0')
      .withRequest(new HttpRequest.HttpRequestBuilder()
      .withScheme('HTTPS')
      .withMethod('PUT')
      .withHost('my-secret-service')
      .withPort(8080)
      .withPath('/article/123')
      .withResource('/article/:article_id')
      .withRemoteIp('192.168.10.12')
      .withRemotePort(32541)
      .withHeaders(new HttpHeaders.HttpHeadersBuilder()
      .withHeader('user-agent', 'my user agent')
      .build())
      .build())
      .withResponse(new HttpResponse.HttpResponseBuilder()
      .withStatus(400)
      .withBlocked(Boolean.FALSE)
      .build())
      .build())
      .withService(new Service.ServiceBuilder()
      .withProperty('context_version', '0.1.0')
      .withProperty('name', 'web-front')
      .withProperty('environment', 'staging')
      .withProperty('version', '0.1.2-beta1')
      .build())
      .withServiceStack(new ServiceStack010.ServiceStack010Builder()
      .withContextVersion('0.1.0')
      .withServices([
        new Service.ServiceBuilder()
        .withProperty('name', 'web-front')
        .withProperty('environment', 'staging')
        .withProperty('version', '0.1.2-beta1')
        .withProperty('when', toInstant(LocalDateTime.of(2021, 5, 21, 1, 2, 3, 456789000)))
        .build()
      ])
      .build())
      .withSpan(new Span010.Span010Builder()
      .withContextVersion('0.1.0')
      .withId('012345678901234567890123456789')
      .build())
      .withTrace(new Trace010.Trace010Builder()
      .withContextVersion('0.1.0')
      .withId('012345678901234567890123456789')
      .build())
      .withTracer(new Tracer010.Tracer010Builder()
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
      .withEvents([attack])
      .build()

    String result = ReportSerializer.intakeBatchAdapter.toJson(batch)

    def expected = '''
      {
        "protocol_version": 1,
        "idempotency_key": "35ce0157-39a3-478a-95aa-342f11b37d0e",
        "events": [{
          "event_id": "41174c05-dc35-4fa1-90cd-f97b6d5f3135",
          "event_type": "appsec.threat.attack",
          "event_version": "0.1.0",
          "detected_at": "2021-05-21T01:02:03.456789Z",
          "type": "sql_injection",
          "blocked": true,
          "rule": {
            "id": "rule_942160",
            "name": "Detects blind sqli tests using sleep() or benchmark()",
            "set": "sql_injection"
          },
          "rule_match": {
            "operator": "@rx",
            "operator_value": "(?i:sleep\\\\\\\\(\\\\\\\\s*?\\\\\\\\d*?\\\\\\\\s*?\\\\\\\\)|benchmark\\\\\\\\(.*?\\\\\\\\,.*?\\\\\\\\))",
            "parameters": [
              {
                "name": "ARGS:text",
                "value": "1))/**/and/**/pg_sleep(7)--1"
              }
            ],
            "highlight": [
              "sleep(7)"
            ],
            "has_server_side_match": true
          },
          "context": {
            "actor": {
              "context_version": "0.1.0",
              "ip": {
                "address": "127.0.0.1"
              },
              "identifiers": {
                "sqreen_id": "test_id",
                "email": "test@domain.tld"
              },
              "_id": "ewogICAgICAgIHNxcmVlbl9pZDogdGVzdF9pZCwKICAgICAgICBlbWFpbDogdGVzdEBkb21haW4udGxkCiAgICAgIH0K"
            },
            "host": {
              "context_version": "0.1.0",
              "os_type": "Linux",
              "hostname": "i-0123456789012345"
            },
            "http": {
              "context_version": "0.1.0",
              "request": {
                "scheme": "HTTPS",
                "method": "PUT",
                "host": "my-secret-service",
                "port": 8080,
                "path": "/article/123",
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
            "service_stack": {
              "context_version": "0.1.0",
              "services": [
                {
                  "name": "web-front",
                  "environment": "staging",
                  "version": "0.1.2-beta1",
                  "when": "2021-05-21T01:02:03.456789Z"
                }
              ]
            },
            "span": {
              "context_version": "0.1.0",
              "id": "012345678901234567890123456789"
            },
            "trace": {
              "context_version": "0.1.0",
              "id": "012345678901234567890123456789"
            },
            "tracer": {
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
