package com.datadog.iast.overhead

import com.datadog.iast.IastRequestContext
import com.datadog.iast.overhead.OverheadController.OverheadControllerImpl
import datadog.trace.api.Config
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler
import groovy.transform.CompileDynamic
import spock.lang.Shared

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED

@CompileDynamic
class OverheadControllerTest extends DDSpecification {

  @Shared
  static final float DEFAULT_REQUEST_SAMPLING = Config.get().getIastRequestSampling()

  def 'Request sampling'() {
    given: 'Set the request sampling percentage'
    def config = Spy(Config.get())
    config.getIastRequestSampling() >> samplingPct
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(config, taskSchedler)

    when:
    int sampledRequests = 0
    for (int i = 0; i < requests; i++) {
      if (overheadController.acquireRequest()) {
        sampledRequests += 1
        overheadController.releaseRequest()
      }
    }

    then:
    sampledRequests == expectedSampledRequests

    where:
    samplingPct              | requests | expectedSampledRequests
    DEFAULT_REQUEST_SAMPLING | 100      | 33
    33                       | 100      | 33
    33                       | 10       | 3
    33                       | 9        | 3
    50                       | 100      | 50
    50                       | 10       | 5
    100                      | 1        | 1
    100                      | 100      | 100
    200                      | 100      | 100
    1000                     | 100      | 100
    0                        | 100      | 100
    51                       | 100      | 51
    99                       | 100      | 99
  }

  void 'No more than two request can be acquired concurrently'() {
    given: 'Set sampling to 100%'
    def config = Spy(Config.get())
    config.getIastRequestSampling() >> 100
    def maxRequests = config.iastMaxConcurrentRequests
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(config, taskSchedler)

    when: 'Acquire max concurrent requests'
    assert maxRequests > 0
    def acquired = (1..maxRequests).collect({ overheadController.acquireRequest() })

    then: 'All of them are acquired'
    acquired.every { it }

    when: 'Requests arrive over concurrency limit'
    def extraAcquired = (1..maxRequests).collect({ overheadController.acquireRequest() })

    then: 'None of them is acquired'
    extraAcquired.every { !it }
  }

  void 'Unlimited concurrent requests'() {
    given: 'Set sampling to 100%'
    def config = Spy(Config.get())
    config.getIastRequestSampling() >> 100
    config.getIastMaxConcurrentRequests() >> UNLIMITED
    def taskScheduler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(config, taskScheduler)

    when: 'Acquire max concurrent requests'
    def acquired = (1..1_000_000).collect({ overheadController.acquireRequest() })

    then: 'All of them are acquired'
    acquired.every { it }
  }

  void 'getContext defaults to global context if span is null'() {
    given:
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(Config.get(), taskSchedler) as OverheadControllerImpl

    when:
    def context = overheadController.getContext(null)

    then:
    context == overheadController.globalContext
  }

  void 'getContext defaults to request context if span is null'() {
    given:
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(Config.get(), taskSchedler) as OverheadControllerImpl
    def span = Stub(AgentSpan)
    span.getRequestContext() >> null

    when:
    def context = overheadController.getContext(span)

    then:
    context == overheadController.globalContext
  }

  void 'getContext returns null if there is no IAST request context'() {
    given:
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(Config.get(), taskSchedler) as OverheadControllerImpl
    def span = getAgentSpanWithNoIASTRequest()

    when:
    def context = overheadController.getContext(span)

    then:
    context == null
  }

  void 'getContext returns specific OverheadContext for IAST request context'() {
    given:
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(Config.get(), taskSchedler)
    def overheadContext = Stub(OverheadContext)
    def iastRequestContext = Stub(IastRequestContext)
    iastRequestContext.getOverheadContext() >> overheadContext
    def requestContext = Stub(RequestContext)
    requestContext.getData(RequestContextSlot.IAST) >> iastRequestContext
    def span = Stub(AgentSpan)
    span.getRequestContext() >> requestContext

    when:
    def context = overheadController.getContext(span)

    then:
    context == overheadContext
  }

  void 'If no context available operations has not quota'() {
    given:
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(Config.get(), taskSchedler)
    def span = getAgentSpanWithNoIASTRequest()

    when:
    def hasQuota = overheadController.hasQuota(Operations.REPORT_VULNERABILITY, span)

    then:
    !hasQuota
  }

  void 'Only two REPORT_VULNERABILITY operations can be consumed in a OverheadContext instance'() {
    given:
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(Config.get(), taskSchedler)
    def span = getAgentSpanWithOverheadContext()

    when:
    def hasQuota1 = overheadController.hasQuota(Operations.REPORT_VULNERABILITY, span)

    then:
    hasQuota1

    when:
    def consumedQuota1 = overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)
    def consumedQuota2 = overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)
    def consumedQuota3 = overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)

    then:
    consumedQuota1
    consumedQuota2
    !consumedQuota3

    when:
    def hasQuota2 = overheadController.hasQuota(Operations.REPORT_VULNERABILITY, span)

    then:
    !hasQuota2
  }

  def 'Available requests always ends up at max'() {
    setup:
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = OverheadController.build(Config.get(), taskSchedler) as OverheadControllerImpl

    def nThreads = 32
    def nIters = 50000
    def executorService = Executors.newFixedThreadPool(nThreads)
    def startLatch = new CountDownLatch(nThreads)
    def sem = new Semaphore(Config.get().getIastMaxConcurrentRequests())

    when:
    List<Future<Boolean[]>> futures = (0..nThreads).collect { thread ->
      executorService.submit({
        ->
        startLatch.countDown()
        startLatch.await()
        final results = new Boolean[nIters]
        for (int j = 0; j < nIters; j++) {
          if (overheadController.acquireRequest()) {
            results[j] = true
            if (!sem.tryAcquire()) {
              throw new RuntimeException("Could not acquire semaphore")
            }
            sem.release()
            overheadController.releaseRequest()
          } else {
            results[j] = false
          }
        }
        return results
      } as Callable<Boolean[]>)
    }
    def futuresResults = futures.collect({
      it.get()
    })

    then:
    // At least one request ran.
    futuresResults.flatten().any { it }
    // At least one request did not run.
    futuresResults.flatten().any { !it }
    // In the final state, there is no consumed available request.
    overheadController.availableRequests.available() == Config.get().getIastMaxConcurrentRequests()

    cleanup:
    executorService?.shutdown()
  }

  def 'acquireRequest works for max concurrent request per reset'() {
    setup:
    def taskSchedler = Stub(AgentTaskScheduler)
    injectSysConfig("dd.iast.request-sampling", "100")
    rebuildConfig()
    def maxConcurrentRequests = Config.get().getIastMaxConcurrentRequests()
    def releaseCount = maxConcurrentRequests - 1
    def overheadController = OverheadController.build(Config.get(), taskSchedler)

    when:
    def acquiredValues = (1..maxConcurrentRequests).collect { overheadController.acquireRequest() }
    def lastAcquired = overheadController.acquireRequest()

    then:
    acquiredValues.every { it == true }
    !lastAcquired

    when:
    overheadController.reset()
    acquiredValues = (1..maxConcurrentRequests).collect { overheadController.acquireRequest() }
    lastAcquired = overheadController.acquireRequest()

    then:
    acquiredValues.every { it == true }
    !lastAcquired

    when:
    (1..releaseCount).each { overheadController.releaseRequest() }
    acquiredValues = (1..releaseCount).collect { overheadController.acquireRequest() }
    lastAcquired = overheadController.acquireRequest()

    then:
    acquiredValues.every { it == true }
    !lastAcquired
  }

  private AgentSpan getAgentSpanWithOverheadContext() {
    def iastRequestContext = Stub(IastRequestContext)
    iastRequestContext.getOverheadContext() >> new OverheadContext(Config.get().getIastVulnerabilitiesPerRequest())
    def requestContext = Stub(RequestContext)
    requestContext.getData(RequestContextSlot.IAST) >> iastRequestContext
    def span = Stub(AgentSpan)
    span.getRequestContext() >> requestContext

    return span
  }

  private AgentSpan getAgentSpanWithNoIASTRequest() {
    def requestContext = Stub(RequestContext)
    requestContext.getData(RequestContextSlot.IAST) >> null
    def span = Stub(AgentSpan)
    span.getRequestContext() >> requestContext
    return span
  }
}
