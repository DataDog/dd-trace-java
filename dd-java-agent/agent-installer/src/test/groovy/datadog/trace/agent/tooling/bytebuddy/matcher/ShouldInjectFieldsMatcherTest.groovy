package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.agent.tooling.context.ShouldInjectFieldsMatcher

class ShouldInjectFieldsMatcherTest extends AbstractHierarchyMatcherTest {

  def "should inject only into #keyType when #klass is transformed"() {
    setup:
    def matcher = new ShouldInjectFieldsMatcher(keyType, "java.lang.String")

    when:
    boolean matches = matcher.matches(typePool.describe(klass).resolve())

    then:
    ((klass == keyType) && matches) || ((klass != keyType) && !matches)

    where:
    keyType                             | klass
    "java.util.concurrent.ForkJoinTask" | "java.util.concurrent.ForkJoinTask"
    "java.util.concurrent.ForkJoinTask" | "java.util.concurrent.RecursiveTask"
    "java.util.concurrent.ForkJoinTask" | "datadog.trace.agent.test.ARecursiveTask"
  }

  def "should inject only into #expected when #klass implementing #keyType is transformed"() {
    setup:
    def matcher = new ShouldInjectFieldsMatcher(keyType, "java.lang.String")

    when:
    boolean matches = matcher.matches(typePool.describe(klass).resolve())

    then:
    ((klass == expected) && matches) || ((klass != expected) && !matches)

    where:
    keyType                               | klass                                                                   | expected
    "java.util.concurrent.RunnableFuture" | "java.util.concurrent.FutureTask"                                       | "java.util.concurrent.FutureTask"
    "java.util.concurrent.RunnableFuture" | "java.util.concurrent.ScheduledThreadPoolExecutor\$ScheduledFutureTask" | "java.util.concurrent.FutureTask"
    "java.util.concurrent.RunnableFuture" | "java.util.concurrent.ExecutorCompletionService\$QueueingFuture"        | "java.util.concurrent.FutureTask"
    // tests the case where a class in the hierarchy does not implement the target interface but subclasses something which does
    "java.util.concurrent.RunnableFuture" | "datadog.trace.agent.test.LeafFutureTask"                               | "java.util.concurrent.FutureTask"
    // tests the case where a direct interface in the hierarchy does not extend the target interface but an indirect interface does
    "java.util.concurrent.Future"         | "datadog.trace.agent.test.LeafFutureTask"                               | "java.util.concurrent.FutureTask"
  }
}
