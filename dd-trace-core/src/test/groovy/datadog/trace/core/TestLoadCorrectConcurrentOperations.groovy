package datadog.trace.core

import datadog.trace.TestObject
import datadog.trace.api.Platform
import datadog.trace.core.unsafe.UnsafeCAS
import datadog.trace.core.unsafe.UnsafeConcurrentArrayOperations
import datadog.trace.core.varhandles.VarHandleCAS
import datadog.trace.core.varhandles.VarHandleConcurrentArrayOperations
import datadog.trace.test.util.DDSpecification
import datadog.trace.unsafe.ConcurrentArrayOperations

class TestLoadCorrectConcurrentOperations extends DDSpecification {

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

  def "unsafe CAS are not loaded on JDK9+"() {
    expect:
    assertCorrectImplementationLoaded(Platform.createReferenceCAS(TestObject, "testField", String))
    assertCorrectImplementationLoaded(Platform.createIntCAS(TestObject, "intField"))
    assertCorrectImplementationLoaded(Platform.createLongCAS(TestObject, "longField"))
  }

  boolean assertCorrectImplementationLoaded(Object cas) {
    if (Platform.isJavaVersionAtLeast(9)) {
      return cas instanceof VarHandleCAS
    } else {
      return cas instanceof UnsafeCAS
    }
  }
}
