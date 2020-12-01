package datadog.trace.agent.tooling

import datadog.trace.bootstrap.WeakMap
import datadog.trace.test.util.GCUtils
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

class WeakConcurrentSupplierTest extends DDSpecification {
  @Shared
  def weakConcurrentSupplier = new WeakMapSuppliers.WeakConcurrent()
  @Shared
  def weakInlineSupplier = new WeakMapSuppliers.WeakConcurrent.Inline()

  def "#name accepts null values"() {
    setup:
    WeakMap.Provider.provider.set(supplier)
    def map = WeakMap.Provider.newWeakMap()

    when:
    map.put('key', null)

    then:
    noExceptionThrown()

    where:
    name             | supplier
    "WeakConcurrent" | weakConcurrentSupplier
    "WeakInline"     | weakInlineSupplier
  }

  def "Calling newWeakMap on #name creates independent maps"() {
    setup:
    WeakMap.Provider.provider.set(supplier)
    def key = new Object()
    def map1 = WeakMap.Provider.newWeakMap()
    def map2 = WeakMap.Provider.newWeakMap()

    when:
    map1.put(key, "value1")
    map2.put(key, "value2")

    then:
    map1.get(key) == "value1"
    map2.get(key) == "value2"

    where:
    name             | supplier
    "WeakConcurrent" | weakConcurrentSupplier
    "WeakInline"     | weakInlineSupplier
  }

  def "Unreferenced supplier gets cleaned up on #name"() {
    setup:
    // Note: we use 'double supplier' here because Groovy keeps reference to test data preventing it from being GCed
    def supplier = supplierSupplier()
    def ref = new WeakReference(supplier)

    when:
    def supplierRef = new WeakReference(supplier)
    supplier = null
    GCUtils.awaitGC(supplierRef)

    then:
    ref.get() == null

    where:
    name             | supplierSupplier
    "WeakConcurrent" | { -> new WeakMapSuppliers.WeakConcurrent() }
    "WeakInline"     | { -> new WeakMapSuppliers.WeakConcurrent.Inline() }
  }

  def "Unreferenced map gets cleaned up on #name"() {
    setup:
    WeakMap.Provider.provider.set(supplier)
    def map = WeakMap.Provider.newWeakMap()
    def ref = new WeakReference(map)

    when:
    def mapRef = new WeakReference(map)
    map = null
    GCUtils.awaitGC(mapRef)

    then:
    ref.get() == null

    where:
    name             | supplier
    "WeakConcurrent" | weakConcurrentSupplier
    "WeakInline"     | weakInlineSupplier
  }

  def "Unreferenced keys get cleaned up on #name"() {
    setup:
    def key = new Object()
    map.put(key, "value")
    GCUtils.awaitGC()

    expect:
    map.size() == 1

    when:
    def keyRef = new WeakReference(key)
    key = null
    GCUtils.awaitGC(keyRef)

    if (name == "WeakConcurrent") {
      // Sleep enough time for cleanup thread to get scheduled.
      // But on a very slow box (or high load) scheduling may not be exactly predictable
      // so we try a few times.
      int count = 0
      while (map.size() != 0 && count < 10) {
        Thread.sleep(TimeUnit.SECONDS.toMillis(WeakMapSuppliers.WeakConcurrent.CLEAN_FREQUENCY_SECONDS))
        count++
      }
    }

    // Hit map to trigger unreferenced entries cleanup.
    // Exact number of times that we need to hit map is implementation dependent.
    if (name == "WeakInline") {
      map.get("test")
    }

    then:
    map.size() == 0

    where:
    name             | map
    "WeakConcurrent" | weakConcurrentSupplier.get()
    "WeakInline"     | weakInlineSupplier.get()
  }
}
