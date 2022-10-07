package com.datadog.iast

import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Location
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityBatch
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.DDId
import datadog.trace.api.TraceSegment
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification
import groovy.json.JsonSlurper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static datadog.trace.api.config.IastConfig.IAST_DEDUPLICATION_ENABLED

class ReporterTest extends DDSpecification {

  void 'basic vulnerability reporting'() {
    given:
    final slurper = new JsonSlurper()
    final Reporter reporter = new Reporter()
    final traceSegment = Mock(TraceSegment)
    final ctx = new IastRequestContext()
    final reqCtx = Stub(RequestContext)
    final spanId = DDId.from(123456)
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    reqCtx.getTraceSegment() >> traceSegment
    VulnerabilityBatch batch = null

    final span = Stub(AgentSpan)
    span.getRequestContext() >> reqCtx
    span.getSpanId() >> spanId

    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(spanId, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    1 * traceSegment.setDataTop('iast', _) >> { batch = it[1] as VulnerabilityBatch }
    slurper.parseText(batch.toString()) == slurper.parseText('''{
      "vulnerabilities": [
        {
          "evidence": { "value":"MD5"},
          "hash":1042880134,
          "location": {
            "spanId":123456,
            "line":1,
            "path":
            "foo"
          },
          "type":"WEAK_HASH"
        }
      ]
    }''')
    1 * traceSegment.setTagTop('manual.keep', true)
    0 * _
  }

  void 'two vulnerabilities'() {
    given:
    final slurper = new JsonSlurper()
    final Reporter reporter = new Reporter()
    final traceSegment = Mock(TraceSegment)
    final ctx = new IastRequestContext()
    final reqCtx = Stub(RequestContext)
    final spanId = DDId.from(123456)
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    reqCtx.getTraceSegment() >> traceSegment
    VulnerabilityBatch batch = null

    final span = Stub(AgentSpan)
    span.getRequestContext() >> reqCtx
    span.getSpanId() >> spanId

    final v1 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(spanId, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )
    final v2 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(spanId, new StackTraceElement("foo", "foo", "foo", 2)),
      new Evidence("MD4")
      )

    when:
    reporter.report(span, v1)
    reporter.report(span, v2)

    then:
    1 * traceSegment.setDataTop('iast', _) >> { batch = it[1] as VulnerabilityBatch }
    slurper.parseText(batch.toString()) == slurper.parseText('''{
      "vulnerabilities": [
        {
          "evidence": { "value":"MD5" },
          "hash":1042880134,
          "location": {
            "spanId":123456,
            "line":1,
            "path":"foo"
          },
          "type":"WEAK_HASH"
        },
        {
          "evidence": {"value":"MD4"},
          "hash":748468584,
          "location": {
            "spanId":123456,
            "line":2,
            "path":"foo"
          },
          "type":"WEAK_HASH"
        }
      ]
    }''')
    1 * traceSegment.setTagTop('manual.keep', true)
    0 * _
  }

  void 'null span does not throw'() {
    given:
    final Reporter reporter = new Reporter()
    final span = null
    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(null, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    noExceptionThrown()
    0 * _
  }

  void 'null RequestContext does not throw'() {
    given:
    final Reporter reporter = new Reporter()
    final span = Mock(AgentSpan)
    span.getRequestContext() >> null
    span.getSpanId() >> null
    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(null, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    noExceptionThrown()
    1 * span.getRequestContext()
    0 * _
  }

  void 'null IastRequestContext does not throw'() {
    given:
    final Reporter reporter = new Reporter()
    final reqCtx = Mock(RequestContext)
    final spanId = DDId.from(123456)
    reqCtx.getData(RequestContextSlot.IAST) >> null
    final span = Mock(AgentSpan)
    span.getRequestContext() >> reqCtx
    span.getSpanId() >> spanId
    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(spanId, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST)
    0 * _
  }

  void 'Vulnerabilities with same type and location are equals'() {
    given:
    final vulnerability1 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(DDId.from(123456), new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("GOOD")
      )
    final vulnerability2 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(DDId.from(7890), new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("BAD")
      )

    expect:
    vulnerability1 == vulnerability2
  }

  void 'Vulnerabilities with same type and different location are not equals'() {
    given:
    final vulnerability1 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(DDId.from(123456), new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("GOOD")
      )
    final vulnerability2 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(DDId.from(7890), new StackTraceElement("foo", "foo", "foo", 2)),
      new Evidence("BAD")
      )

    expect:
    vulnerability1 != vulnerability2
  }

  void 'Reporter when IAST_DEDUPLICATION_ENABLED is enabled prevents duplicates'() {
    given:
    injectSysConfig(IAST_DEDUPLICATION_ENABLED, "true")
    final Reporter reporter = new Reporter()
    final batch = new VulnerabilityBatch()
    final span = spanWithBatch(batch)
    final vulnerability = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span.spanId, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("GOOD")
      )

    when: 'first time a vulnerability is reported'
    reporter.report(span, vulnerability)

    then:
    batch.vulnerabilities.size() == 1

    when: 'second time the a vulnerability is reported'
    reporter.report(span, vulnerability)

    then:
    batch.vulnerabilities.size() == 1
  }

  void 'Reporter when IAST_DEDUPLICATION_ENABLED is disabled does not prevent duplicates'() {
    given:
    injectSysConfig(IAST_DEDUPLICATION_ENABLED, "false")
    final Reporter reporter = new Reporter()
    final batch = new VulnerabilityBatch()
    final span = spanWithBatch(batch)
    final vulnerability = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span.spanId, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("GOOD")
      )

    when: 'first time a vulnerability is reported'
    reporter.report(span, vulnerability)

    then:
    batch.vulnerabilities.size() == 1

    when: 'second time the a vulnerability is reported'
    reporter.report(span, vulnerability)

    then:
    batch.vulnerabilities.size() == 2
  }

  void 'Reporter when IAST_DEDUPLICATION_ENABLED is enabled clears the cache after 1000 different vulnerabilities'() {
    given:
    injectSysConfig(IAST_DEDUPLICATION_ENABLED, "true")
    final deduplicationRange = (0..Reporter.HashBasedDeduplication.DEFAULT_MAX_SIZE - 1)
    final Reporter reporter = new Reporter()
    final batch = new VulnerabilityBatch()
    final span = spanWithBatch(batch)
    final vulnerabilityBuilder = { int index ->
      new Vulnerability(
        VulnerabilityType.WEAK_HASH,
        Location.forSpanAndStack(span.spanId, new StackTraceElement(index.toString(), index.toString(), index.toString(), index)),
        new Evidence("GOOD")
        )
    }

    when: 'the deduplication cache is filled for the first time'
    deduplicationRange.each { i -> reporter.report(span, vulnerabilityBuilder.call(i)) }

    then: 'all vulnerabilities are reported'
    batch.vulnerabilities.size() == Reporter.HashBasedDeduplication.DEFAULT_MAX_SIZE

    when: 'duplicated vulnerabilities are checked'
    deduplicationRange.each { i -> reporter.report(span, vulnerabilityBuilder.call(i)) }

    then: 'no vulnerabilities are reported'
    batch.vulnerabilities.size() == Reporter.HashBasedDeduplication.DEFAULT_MAX_SIZE

    when: 'a new vulnerability pops up'
    reporter.report(span, vulnerabilityBuilder.call(Reporter.HashBasedDeduplication.DEFAULT_MAX_SIZE))
    deduplicationRange.each { i -> reporter.report(span, vulnerabilityBuilder.call(i)) }

    then: 'the new vulnerability is reported and the cache cleared'
    batch.vulnerabilities.size() == 2 * Reporter.HashBasedDeduplication.DEFAULT_MAX_SIZE + 1
  }

  void 'test hash based deduplication under concurrency'() {
    given:
    final executors = Executors.newCachedThreadPool()
    final int size = 32
    final latch = new CountDownLatch(size)
    final predicate = new Reporter.HashBasedDeduplication(size >> 3) // maximum of 4 hashes
    final Reporter reporter = new Reporter({ final Vulnerability vul ->
      latch.countDown()
      predicate.test(vul)
    })
    final batch = new VulnerabilityBatch()
    final span = spanWithBatch(batch)
    final vulnerabilityBuilder = { int index ->
      new Vulnerability(
        VulnerabilityType.WEAK_HASH,
        Location.forSpanAndStack(span.spanId, new StackTraceElement(index.toString(), index.toString(), index.toString(), index)),
        new Evidence("GOOD")
        )
    }

    when: 'a few duplicates are reported in a concurrent scenario'
    (0..size).each { index ->
      executors.execute({ reporter.report(span, vulnerabilityBuilder.call(index % 16)) }) // 8 different vuls
    }

    then: 'there are vulnerabilities reported'
    executors.shutdown()
    executors.awaitTermination(5, TimeUnit.SECONDS)
    executors.isTerminated()
    batch.vulnerabilities.size() >= 8
  }

  private AgentSpan spanWithBatch(final VulnerabilityBatch batch) {
    final traceSegment = Mock(TraceSegment)
    final ctx = Mock(IastRequestContext) {
      it.getVulnerabilityBatch() >> batch
    }
    final reqCtx = Stub(RequestContext) {
      it.getData(RequestContextSlot.IAST) >> ctx
      it.getTraceSegment() >> traceSegment
    }
    final spanId = DDId.from(123456)
    final span = Mock(AgentSpan)
    span.getRequestContext() >> reqCtx
    span.getSpanId() >> spanId
    return span
  }
}
