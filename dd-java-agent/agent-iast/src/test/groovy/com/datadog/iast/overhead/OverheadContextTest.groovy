package com.datadog.iast.overhead

import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.Config
import datadog.trace.api.iast.IastContext
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler
import com.datadog.iast.overhead.OverheadController.OverheadControllerImpl
import groovy.transform.CompileDynamic

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED

@CompileDynamic
class OverheadContextTest extends DDSpecification {

  @Override
  void cleanup() {
    OverheadContext.globalMap.clear()
  }

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

  void 'if it is global sampling maps are null'() {
    given:
    OverheadContext ctx = new OverheadContext(1, true)

    expect:
    ctx.requestMap == null
    ctx.copyMap == null
  }

  void "resetMaps is no-op when context is global"() {
    given:
    def ctx = new OverheadContext(5, true)
    OverheadContext.globalMap.put("endpoint", [(VulnerabilityType.WEAK_HASH): 1])

    when:
    ctx.resetMaps()

    then:
    // globalMap remains unchanged
    OverheadContext.globalMap == ["endpoint": [(VulnerabilityType.WEAK_HASH): 1]]
    ctx.copyMap == null
    ctx.requestMap == null
  }

  void "resetMaps clears request and copy maps when quota remains"() {
    given:
    def ctx = new OverheadContext(3, false)
    // Prepare global entry for "endpoint"
    OverheadContext.globalMap.put("endpoint", [(VulnerabilityType.SQL_INJECTION): 2])
    ctx.requestMap.put("endpoint", [(VulnerabilityType.WEAK_HASH): 1])
    ctx.copyMap.put("endpoint", [(VulnerabilityType.WEAK_HASH): 1])
    assert ctx.getAvailableQuota() > 0

    when:
    ctx.resetMaps()

    then:
    // Since quota > 0, we remove any global entry for "endpoint" (none here)
    OverheadContext.globalMap.isEmpty()
    // Per-request and copy maps are cleared
    ctx.requestMap.isEmpty()
    ctx.copyMap.isEmpty()
  }

  void "resetMaps removes global entry when quota consumed and countMap is null"() {
    given:
    def ctx = new OverheadContext(1, false)
    // Prepare global entry for "key"
    OverheadContext.globalMap.put("endpoint", [(VulnerabilityType.SQL_INJECTION): 2])
    // Simulate per-request endpoint present but no inner map
    ctx.requestMap.put("endpoint", null)
    ctx.copyMap.put("endpoint", [(VulnerabilityType.SQL_INJECTION): 2])
    // Consume the only permit
    ctx.consumeQuota(1)
    assert ctx.getAvailableQuota() == 0

    when:
    ctx.resetMaps()

    then:
    // requestMap get("endpoint") was null → globalMap.remove("endpoint")
    !OverheadContext.globalMap.containsKey("endpoint")
    ctx.requestMap.isEmpty()
    ctx.copyMap.isEmpty()
  }

  void "resetMaps removes global entry when quota consumed and countMap is empty"() {
    given:
    def ctx = new OverheadContext(1, false)
    OverheadContext.globalMap.put("endpoint", [(VulnerabilityType.SQL_INJECTION): 5])
    ctx.requestMap.put("endpoint", [:])  // empty inner map
    ctx.copyMap.put("endpoint", [(VulnerabilityType.SQL_INJECTION): 5])
    ctx.consumeQuota(1)
    assert ctx.getAvailableQuota() == 0

    when:
    ctx.resetMaps()

    then:
    // Empty countMap → remove global entry
    !OverheadContext.globalMap.containsKey("endpoint")
    ctx.requestMap.isEmpty()
    ctx.copyMap.isEmpty()
  }

  void "resetMaps merges and updates global entry when quota consumed and countMap non-empty"() {
    given:
    def ctx = new OverheadContext(1, false)
    OverheadContext.globalMap.put("endpoint", [(VulnerabilityType.WEAK_HASH): 1])
    // Simulate we saw 3 in this request
    ctx.requestMap.put("endpoint", [(VulnerabilityType.WEAK_HASH): 3])
    ctx.copyMap.put("endpoint", [(VulnerabilityType.WEAK_HASH): 1])
    ctx.consumeQuota(1)
    assert ctx.getAvailableQuota() == 0

    when:
    ctx.resetMaps()

    then:
    // The max of (global=1, request=3) is 3, so globalMap is updated
    OverheadContext.globalMap["endpoint"][VulnerabilityType.WEAK_HASH] == 3
    ctx.requestMap.isEmpty()
    ctx.copyMap.isEmpty()
  }
}
