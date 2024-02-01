package com.datadog.iast

import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Location
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityBatch
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.Config
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler
import org.skyscreamer.jsonassert.JSONAssert
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static com.datadog.iast.IastTag.ANALYZED
import static com.datadog.iast.test.TaintedObjectsUtils.noOpTaintedObjects
import static datadog.trace.api.config.IastConfig.IAST_DEDUPLICATION_ENABLED

class ReporterTest extends DDSpecification {

  @Shared
  protected static final TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  def cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  void 'basic vulnerability reporting'() {
    given:
    final Reporter reporter = new Reporter()
    final traceSegment = Mock(TraceSegment)
    final ctx = new IastRequestContext(noOpTaintedObjects())
    final reqCtx = Stub(RequestContext)
    final spanId = 123456
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    reqCtx.getTraceSegment() >> traceSegment
    VulnerabilityBatch batch = null

    final span = Stub(AgentSpan)
    span.getRequestContext() >> reqCtx
    span.getSpanId() >> spanId

    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    1 * traceSegment.getDataTop('iast') >> null
    1 * traceSegment.setDataTop('iast', _) >> { batch = it[1] as VulnerabilityBatch }
    JSONAssert.assertEquals('''{
      "vulnerabilities": [
        {
          "evidence": { "value":"MD5"},
          "hash":1042880134,
          "location": {
            "spanId":123456,
            "line":1,
            "path": "foo",
            "method": "foo"
          },
          "type":"WEAK_HASH"
        }
      ]
    }''', batch.toString(), true)
    1 * traceSegment.setTagTop('manual.keep', true)
    0 * _
  }

  void 'two vulnerabilities'() {
    given:
    final Reporter reporter = new Reporter()
    final traceSegment = Mock(TraceSegment)
    final ctx = new IastRequestContext(noOpTaintedObjects())
    final reqCtx = Stub(RequestContext)
    final spanId = 123456
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    reqCtx.getTraceSegment() >> traceSegment
    VulnerabilityBatch batch = null

    final span = Stub(AgentSpan)
    span.getRequestContext() >> reqCtx
    span.getSpanId() >> spanId

    final v1 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )
    final v2 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span, new StackTraceElement("foo", "foo", "foo", 2)),
      new Evidence("MD4")
      )

    when:
    reporter.report(span, v1)
    reporter.report(span, v2)

    then:
    // first vulnerability
    1 * traceSegment.getDataTop('iast') >> null
    1 * traceSegment.setDataTop('iast', _) >> { batch = it[1] as VulnerabilityBatch }
    // second vulnerability
    1 * traceSegment.getDataTop('iast') >> { return batch } // second vulnerability
    JSONAssert.assertEquals('''{
      "vulnerabilities": [
        {
          "evidence": { "value":"MD5" },
          "hash":1042880134,
          "location": {
            "spanId":123456,
            "line":1,
            "path":"foo",
            "method": "foo"
          },
          "type":"WEAK_HASH"
        },
        {
          "evidence": {"value":"MD4"},
          "hash":748468584,
          "location": {
            "spanId":123456,
            "line":2,
            "path":"foo",
            "method": "foo"
          },
          "type":"WEAK_HASH"
        }
      ]
    }''', batch.toString(), true)
    1 * traceSegment.setTagTop('manual.keep', true)
    0 * _
  }

  void 'null span creates a new one before reporting'() {
    given:
    final tracerAPI = Mock(TracerAPI)
    AgentTracer.forceRegister(tracerAPI)
    final spanId = 12345L
    final serviceName = 'service-name'
    final span = Mock(AgentSpan)
    final scope = Mock(AgentScope)
    final ctx = new IastRequestContext(noOpTaintedObjects())
    final reqCtx = Stub(RequestContext)
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final reporter = new Reporter()
    final hash = v.getHash()

    when:
    reporter.report(null, v)

    then:
    noExceptionThrown()
    1 * tracerAPI.startSpan('iast', 'vulnerability', _ as AgentSpan.Context) >> span
    1 * tracerAPI.activateSpan(span, ScopeSource.MANUAL) >> scope
    1 * span.getRequestContext() >> reqCtx
    1 * span.setSpanType(InternalSpanTypes.VULNERABILITY) >> span
    1 * span.setTag(ANALYZED.key(), ANALYZED.value())
    1 * span.getServiceName() >> serviceName
    1 * span.getSpanId() >> spanId
    1 * span.finish()
    1 * scope.close()
    0 * _

    when:
    def newSpanId = null
    def newServiceName = null
    if(v.getType() instanceof VulnerabilityType.HeaderVulnerabilityType){
      newServiceName = v.getLocation().getServiceName()
    }else{
      newSpanId =  v.getLocation().getSpanId()
    }
    def newHash = v.getHash()

    then:
    if(v.getType() instanceof VulnerabilityType.HeaderVulnerabilityType){
      assert newServiceName == serviceName
      assert newHash != hash
    }else{
      assert newSpanId == spanId
      assert newHash == hash
    }

    where:
    v | _
    defaultVulnerability() | _
    cookieVulnerability() | _
    headerVulnerability() | _
  }

  void 'no spans are create if duplicates are reported'() {
    given:
    final tracerAPI = Mock(TracerAPI)
    AgentTracer.forceRegister(tracerAPI)
    final ctx = new IastRequestContext(noOpTaintedObjects())
    final reqCtx = Stub(RequestContext)
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    final reporter = new Reporter((vul) -> true)
    final v = new Vulnerability(
    VulnerabilityType.WEAK_HASH,
    Location.forSpanAndStack(null, new StackTraceElement("foo", "foo", "foo", 1)),
    new Evidence("MD5")
    )

    when:
    reporter.report(null, v)

    then:
    noExceptionThrown()
    0 * _
  }

  void 'null RequestContext does not throw'() {
    given:
    final Reporter reporter = new Reporter()
    final span = Mock(AgentSpan)
    span.getRequestContext() >> null
    span.getSpanId() >> 12345L
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

  void 'null IastRequestContext also is able to send vulnerabilities'() {
    given:
    final Reporter reporter = new Reporter()
    final reqCtx = Mock(RequestContext)
    final spanId = 123456
    final traceSegment = Mock(TraceSegment)
    final span = Mock(AgentSpan)
    span.getRequestContext() >> reqCtx
    span.getSpanId() >> spanId
    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> null
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * traceSegment.getDataTop('iast') >> null
    1 * traceSegment.setDataTop('iast', _ as VulnerabilityBatch)
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop('_dd.iast.enabled', 1)
    0 * _
  }

  void 'Vulnerabilities with same type and location are equals'() {
    given:
    final span1 = Mock(AgentSpan)
    span1.getSpanId() >> 123456
    final vulnerability1 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span1, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("GOOD")
      )
    final span2 = Mock(AgentSpan)
    span1.getSpanId() >> 7890
    final vulnerability2 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span2, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("BAD")
      )

    expect:
    vulnerability1 == vulnerability2
  }

  void 'Vulnerabilities with same type and different location are not equals'() {
    given:
    final span1 = Mock(AgentSpan)
    span1.getSpanId() >> 123456
    final vulnerability1 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span1, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("GOOD")
      )
    final span2 = Mock(AgentSpan)
    span1.getSpanId() >> 7890
    final vulnerability2 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(span2, new StackTraceElement("foo", "foo", "foo", 2)),
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
      Location.forSpanAndStack(span, new StackTraceElement("foo", "foo", "foo", 1)),
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
      Location.forSpanAndStack(span, new StackTraceElement("foo", "foo", "foo", 1)),
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
        Location.forSpanAndStack(span, new StackTraceElement(index.toString(), index.toString(), index.toString(), index)),
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
    final predicate = new Reporter.HashBasedDeduplication(size >> 3, null) // maximum of 4 hashes
    final Reporter reporter = new Reporter({ final Vulnerability vul ->
      latch.countDown()
      predicate.test(vul)
    })
    final batch = new VulnerabilityBatch()
    final span = spanWithBatch(batch)
    final vulnerabilityBuilder = { int index ->
      new Vulnerability(
        VulnerabilityType.WEAK_HASH,
        Location.forSpanAndStack(span, new StackTraceElement(index.toString(), index.toString(), index.toString(), index)),
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

  void 'cache reset is scheduled'() {
    given:
    final AgentTaskScheduler scheduler = Mock()

    when:
    new Reporter(Config.get(), scheduler)

    then: 'there are vulnerabilities reported'
    1 * scheduler.scheduleAtFixedRate(_, 1, 1, TimeUnit.HOURS)
    0 * _
  }

  private AgentSpan spanWithBatch(final VulnerabilityBatch batch) {
    final traceSegment = Mock(TraceSegment) {
      getDataTop('iast') >> batch
    }
    final ctx = Mock(IastRequestContext) {
      it.getVulnerabilityBatch() >> batch
    }
    final reqCtx = Stub(RequestContext) {
      it.getData(RequestContextSlot.IAST) >> ctx
      it.getTraceSegment() >> traceSegment
    }
    final spanId = 123456
    final span = Mock(AgentSpan)
    span.getRequestContext() >> reqCtx
    span.getSpanId() >> spanId
    return span
  }

  private Vulnerability defaultVulnerability(){
    return new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forSpanAndStack(null, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )
  }

  private Vulnerability cookieVulnerability(){
    return new Vulnerability(
      VulnerabilityType.INSECURE_COOKIE,
      Location.forSpanAndStack(null, new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("cookie-name")
      )
  }

  private Vulnerability headerVulnerability(){
    return new Vulnerability(
      VulnerabilityType.XCONTENTTYPE_HEADER_MISSING, Location.forSpan(null), null)
  }
}
