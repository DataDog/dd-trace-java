package datadog.trace.agent.tooling.bytebuddy

import datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.ForkJoinTask
import java.util.concurrent.Future

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface
import static net.bytebuddy.matcher.ElementMatchers.named

class DDCachingPoolStrategyTest extends DDSpecification {
  static {
    DDCachingPoolStrategy.registerAsSupplier()
    DDElementMatchers.registerAsSupplier()
  }

  def "bootstrap classes can be loaded by our caching type pool"() {
    setup:
    def pool = SharedTypePools.typePool(null)
    def description = pool.describe(ForkJoinTask.name).resolve()

    expect:
    description.name == ForkJoinTask.name
    implementsInterface(named(Future.name)).matches(description)
  }
}
