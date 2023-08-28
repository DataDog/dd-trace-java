package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

abstract class AbstractHierarchyMatcherTest extends DDSpecification {
  static {
    TypePoolFacade.registerAsSupplier()
    DDElementMatchers.registerAsSupplier()
  }

  @Shared
  def typePool = SharedTypePools.typePool(this.class.classLoader)
}
