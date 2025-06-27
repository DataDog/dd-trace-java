package com.datadog.iast.overhead

import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.Config
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.VulnerabilityTypes
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.AgentTaskScheduler
import com.datadog.iast.overhead.OverheadController.OverheadControllerImpl
import groovy.transform.CompileDynamic

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED

import java.util.concurrent.atomic.AtomicIntegerArray

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
    def array = new AtomicIntegerArray(VulnerabilityTypes.STRINGS.length)
    array.incrementAndGet(VulnerabilityType.WEAK_HASH.type())
    OverheadContext.globalMap.put("endpoint", array)


    when:
    ctx.resetMaps()

    then:
    // globalMap remains unchanged
    OverheadContext.globalMap.get('endpoint').get(VulnerabilityType.WEAK_HASH.type()) == 1
    ctx.copyMap == null
    ctx.requestMap == null
  }

  void "resetMaps clears request and copy maps when quota remains"() {
    given:
    def ctx = new OverheadContext(3, false)
    // Prepare global entry for "endpoint"
    def globalArray = new AtomicIntegerArray(VulnerabilityTypes.STRINGS.length)
    globalArray.getAndSet(VulnerabilityType.SQL_INJECTION.type(), 2)
    OverheadContext.globalMap.put("endpoint", globalArray)
    def requestArray = new AtomicIntegerArray(VulnerabilityTypes.STRINGS.length)
    requestArray.set(VulnerabilityType.WEAK_HASH.type(), 1)
    ctx.requestMap.put("endpoint", requestArray)
    def copyArray = new int[VulnerabilityTypes.STRINGS.length]
    copyArray[VulnerabilityType.WEAK_HASH.type()] = 1
    ctx.copyMap.put("endpoint", copyArray)
    assert ctx.getAvailableQuota() > 0

    when:
    ctx.resetMaps()

    then:
    // Since quota > 0, we remove any global entry for "endpoint" (none here)
    OverheadContext.globalMap.isEmpty()
  }

  void "resetMaps merges and updates global entry when quota consumed "() {
    given:
    def ctx = new OverheadContext(1, false)
    def globalArray = new AtomicIntegerArray(VulnerabilityTypes.STRINGS.length)
    globalArray.getAndSet(VulnerabilityType.WEAK_HASH.type(), 1)
    OverheadContext.globalMap.put("endpoint", globalArray)
    // Simulate we saw 3 in this request
    def requestArray = new AtomicIntegerArray(VulnerabilityTypes.STRINGS.length)
    requestArray.set(VulnerabilityType.WEAK_HASH.type(), 3)
    ctx.requestMap.put("endpoint", requestArray)
    def copyArray = new int[VulnerabilityTypes.STRINGS.length]
    copyArray[VulnerabilityType.WEAK_HASH.type()] = 1
    ctx.copyMap.put("endpoint", copyArray)
    ctx.consumeQuota(1)
    assert ctx.getAvailableQuota() == 0

    when:
    ctx.resetMaps()

    then:
    // The max of (global=1, request=3) is 3, so globalMap is updated
    OverheadContext.globalMap.get("endpoint").get(VulnerabilityType.WEAK_HASH.type()) == 3
  }

  void "resetMaps merges and updates global entry when quota consumed and counter <= globalCounter"() {
    given:
    def ctx = new OverheadContext(1, false)
    def globalArray = new AtomicIntegerArray(VulnerabilityTypes.STRINGS.length)
    globalArray.getAndSet(VulnerabilityType.WEAK_HASH.type(), 2)
    OverheadContext.globalMap.put("endpoint", globalArray)
    def requestArray = new AtomicIntegerArray(VulnerabilityTypes.STRINGS.length)
    requestArray.set(VulnerabilityType.WEAK_HASH.type(), 1)
    ctx.requestMap.put("endpoint", requestArray)
    def copyArray = new int[VulnerabilityTypes.STRINGS.length]
    copyArray[VulnerabilityType.WEAK_HASH.type()] = 2
    ctx.copyMap.put("endpoint", copyArray)
    ctx.consumeQuota(1)
    assert ctx.getAvailableQuota() == 0

    when:
    ctx.resetMaps()

    then:
    // The max of (global=1, request=3) is 3, so globalMap is updated
    OverheadContext.globalMap.get("endpoint").get(VulnerabilityType.WEAK_HASH.type()) == 2
  }

  void "resetMaps merges and updates global entry when quota consumed and a vuln is detected in a new endpoint"() {
    given:
    def ctx = new OverheadContext(1, false)
    def globalArray = new AtomicIntegerArray(VulnerabilityTypes.STRINGS.length)
    globalArray.getAndSet(VulnerabilityType.WEAK_HASH.type(), 1)
    OverheadContext.globalMap.put("endpoint", globalArray)
    def requestArray = new AtomicIntegerArray(VulnerabilityTypes.STRINGS.length)
    requestArray.set(VulnerabilityType.WEAK_CIPHER.type(), 1)
    ctx.requestMap.put("endpoint2", requestArray)
    def copyArray = new int[VulnerabilityTypes.STRINGS.length]
    copyArray[VulnerabilityType.WEAK_HASH.type()] = 1
    ctx.copyMap.put("endpoint", copyArray)
    ctx.consumeQuota(1)
    assert ctx.getAvailableQuota() == 0

    when:
    ctx.resetMaps()

    then:
    OverheadContext.globalMap.get("endpoint").get(VulnerabilityType.WEAK_HASH.type()) == 1
    OverheadContext.globalMap.get("endpoint2").get(VulnerabilityType.WEAK_CIPHER.type()) == 1
  }


  void "computeIfAbsent should not clear until size exceeds GLOBAL_MAP_MAX_SIZE"() {
    given: "We know the maximum size"
    int maxSize = OverheadContext.GLOBAL_MAP_MAX_SIZE

    when: "We insert exactly maxSize distinct keys via computeIfAbsent"
    (1..maxSize).each { i ->
      AtomicIntegerArray arr = OverheadContext.globalMap.computeIfAbsent("key" + i) {
        new AtomicIntegerArray([i] as int[])
      }
      // verify returned array holds the correct value
      assert arr.get(0) == i
    }

    then: "The map size is exactly maxSize and none of those keys was evicted"
    OverheadContext.globalMap.size() == maxSize
    (1..maxSize).each { i ->
      assert OverheadContext.globalMap.containsKey("key"+i)
      assert OverheadContext.globalMap.get("key"+i).get(0) == i
    }

    when: "We invoke computeIfAbsent on one more distinct key, which should trigger clear()"
    AtomicIntegerArray extra = OverheadContext.globalMap.computeIfAbsent("keyExtra") {
      new AtomicIntegerArray([999] as int[])
    }

    then:
    OverheadContext.globalMap.size() == 1
    // And the returned array is still the one newly created
    extra.get(0) == 999
  }
}
