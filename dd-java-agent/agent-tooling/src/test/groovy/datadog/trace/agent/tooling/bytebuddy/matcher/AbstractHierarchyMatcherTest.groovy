package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.agent.tooling.bytebuddy.DDCachingPoolStrategy
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class AbstractHierarchyMatcherTest extends DDSpecification {
  static {
    DDCachingPoolStrategy.registerAsSupplier()
  }

  @Shared
  def typePool = SharedTypePools.typePool(this.class.classLoader)
}
