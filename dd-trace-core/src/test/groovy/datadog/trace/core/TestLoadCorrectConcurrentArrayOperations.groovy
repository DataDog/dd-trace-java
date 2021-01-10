package datadog.trace.core

import datadog.trace.api.Platform
import datadog.trace.core.unsafe.UnsafeConcurrentArrayOperations
import datadog.trace.core.varhandles.VarHandleConcurrentArrayOperations
import datadog.trace.test.util.DDSpecification
import datadog.trace.unsafe.ConcurrentArrayOperations

class TestLoadCorrectConcurrentArrayOperations extends DDSpecification {

  def "unsafe concurrent array operations are not loaded on JDK9+"() {
    // test this in core because neither unsafe nor varhandles can be
    // loaded from internal-api itself
    when:
    ConcurrentArrayOperations ops = Platform.concurrentArrayOperations()
    then:
    if (Platform.isJavaVersionAtLeast(9)) {
      ops instanceof VarHandleConcurrentArrayOperations
    } else {
      ops instanceof UnsafeConcurrentArrayOperations
    }
  }
}
