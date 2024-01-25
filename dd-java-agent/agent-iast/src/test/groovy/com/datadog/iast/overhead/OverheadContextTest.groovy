package com.datadog.iast.overhead

import datadog.trace.api.Config
import datadog.trace.api.iast.IastContext
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler
import com.datadog.iast.overhead.OverheadController.OverheadControllerImpl
import groovy.transform.CompileDynamic

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED

@CompileDynamic
class OverheadContextTest extends DDSpecification {

  void 'Can reset global overhead context'() {
    given:
    def taskSchedler = Stub(AgentTaskScheduler)
    final config = Config.get()
    final globalFallback = config.iastContextMode == IastContext.Mode.GLOBAL
    def overheadController = new OverheadControllerImpl(
      config.iastRequestSampling,
      config.iastMaxConcurrentRequests,
      globalFallback,
      taskSchedler
      )

    when:
    overheadController.globalContext.consumeQuota(1)

    then:
    overheadController.globalContext.getAvailableQuota() == 1

    when:
    overheadController.globalContext.reset()

    then:
    overheadController.globalContext.getAvailableQuota() == 2
  }

  void 'Quota is not consumed once it has been exhausted'() {
    given:
    final config = Config.get()
    def overheadContext = new OverheadContext(config.iastVulnerabilitiesPerRequest)
    boolean consumed

    when: 'reduce quota by two'
    consumed = overheadContext.consumeQuota(2)

    then: 'available quota is zero'
    consumed
    overheadContext.getAvailableQuota() == 0

    when: 'reduce quota again'
    consumed = overheadContext.consumeQuota(1)

    then: 'available quota still zero'
    !consumed
    overheadContext.getAvailableQuota() == 0
  }

  void 'Unlimited quota'() {
    given:
    final overheadContext = new OverheadContext(UNLIMITED)

    when:
    final consumed = (1..1_000_000).collect { overheadContext.consumeQuota(1) }

    then:
    !consumed.any { !it }
    overheadContext.availableQuota == Integer.MAX_VALUE
  }
}
