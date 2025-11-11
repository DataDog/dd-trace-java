package datadog.trace.bootstrap

import datadog.trace.test.util.ThreadUtils
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.atomic.AtomicInteger

class FieldBackedContextStoresTest extends DDSpecification {

  def "test FieldBackedContextStore id allocation"() {
    setup:
    int testAllocations = 128
    FieldBackedContextStore[] allocatedStores = new FieldBackedContextStore[testAllocations]
    AtomicInteger keyIds = new AtomicInteger()

    ThreadUtils.runConcurrently(10, testAllocations, {
      int keyId = keyIds.getAndIncrement()
      int storeId = FieldBackedContextStores.getContextStoreId("key${keyId}", "value${keyId}")
      assert allocatedStores[storeId] == null
      allocatedStores[storeId] = FieldBackedContextStores.getContextStore(storeId)
    })

    expect:
    keyIds.get() == testAllocations
    allocatedStores.size() == testAllocations
    (allocatedStores as List).withIndex().collect({ store, storeId -> assert store.storeId == storeId })
  }
}
