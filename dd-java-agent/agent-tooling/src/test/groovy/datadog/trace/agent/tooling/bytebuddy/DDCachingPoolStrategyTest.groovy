package datadog.trace.agent.tooling.bytebuddy

import datadog.trace.test.util.DDSpecification
import net.bytebuddy.dynamic.ClassFileLocator.NoOp

import java.util.concurrent.ForkJoinTask
import java.util.concurrent.Future

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface
import static net.bytebuddy.matcher.ElementMatchers.named

class DDCachingPoolStrategyTest extends DDSpecification {

  def "bootstrap classes can be loaded by our caching type pool"() {
    setup:
    def pool = new DDCachingPoolStrategy(true).typePool(NoOp.INSTANCE, null)
    def description = pool.describe(ForkJoinTask.name).resolve()

    expect:
    description.name == ForkJoinTask.name
    implementsInterface(named(Future.name)).matches(description)
  }
}
