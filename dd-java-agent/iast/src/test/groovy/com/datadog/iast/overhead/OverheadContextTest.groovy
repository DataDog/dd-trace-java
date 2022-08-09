package com.datadog.iast.overhead

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler

class OverheadContextTest extends DDSpecification {

  void 'Can reset global overhead context'() {
    given:
    def taskSchedler = Stub(AgentTaskScheduler)
    def overheadController = new OverheadController(Config.get(), taskSchedler)

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
    def overheadContext = new OverheadContext()
    boolean consumed = false

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
}
