package datadog.trace.agent.tooling.bytebuddy.matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.tooling.context.ShouldInjectFieldsMatcher;
import org.tabletest.junit.TableTest;

class ShouldInjectFieldsMatcherTest extends AbstractHierarchyMatcherTest {

  @TableTest({
    "scenario                | keyType                           | klass                                  ",
    "ForkJoinTask itself     | java.util.concurrent.ForkJoinTask | java.util.concurrent.ForkJoinTask      ",
    "RecursiveTask subclass  | java.util.concurrent.ForkJoinTask | java.util.concurrent.RecursiveTask     ",
    "ARecursiveTask subclass | java.util.concurrent.ForkJoinTask | datadog.trace.agent.test.ARecursiveTask"
  })
  void shouldInjectOnlyIntoKeyTypeWhenTransformed(String keyType, String klass) {
    ShouldInjectFieldsMatcher matcher = new ShouldInjectFieldsMatcher(keyType, "java.lang.String");

    // when
    boolean matches = matcher.matches(describe(klass));

    // then
    assertEquals(klass.equals(keyType), matches);
  }

  @TableTest({
    "scenario                         | keyType                             | klass                                                                | expected                       ",
    "FutureTask                       | java.util.concurrent.RunnableFuture | java.util.concurrent.FutureTask                                      | java.util.concurrent.FutureTask",
    "ScheduledFutureTask              | java.util.concurrent.RunnableFuture | java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask | java.util.concurrent.FutureTask",
    "QueueingFuture                   | java.util.concurrent.RunnableFuture | java.util.concurrent.ExecutorCompletionService$QueueingFuture        | java.util.concurrent.FutureTask",
    "// tests the case where a class in the hierarchy does not implement the target interface but subclasses something which does                                                   ",
    "LeafFutureTask via superclass    | java.util.concurrent.RunnableFuture | datadog.trace.agent.test.LeafFutureTask                              | java.util.concurrent.FutureTask",
    "// tests the case where a direct interface in the hierarchy does not extend the target interface but an indirect interface does                                                ",
    "LeafFutureTask via sub-interface | java.util.concurrent.Future         | datadog.trace.agent.test.LeafFutureTask                              | java.util.concurrent.FutureTask"
  })
  void shouldInjectOnlyIntoExpectedWhenImplementingKeyTypeIsTransformed(
      String keyType, String klass, String expected) {
    ShouldInjectFieldsMatcher matcher = new ShouldInjectFieldsMatcher(keyType, "java.lang.String");

    // when
    boolean matches = matcher.matches(describe(klass));

    // then
    assertEquals(klass.equals(expected), matches);
  }
}
