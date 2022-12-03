import datadog.trace.agent.test.AgentTestRunner

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

class ParallelismTrackingTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.profiling.enabled", "true")
  }

  def "#name: test record parallelism = #expectedParallelism"() {
    when:
    def executor = executorSupplier()
    for (int i = 0; i < 20; i++) {
      executor.submit({}).get()
    }

    then:
    expectedParallelism < 0 || !TEST_PROFILING_CONTEXT_INTEGRATION.poolParallelism.isEmpty()
    for (def pair : TEST_PROFILING_CONTEXT_INTEGRATION.poolParallelism.entrySet()) {
      assert pair.value == expectedParallelism
    }

    cleanup:
    if (executor != ForkJoinPool.commonPool()) {
      executor.shutdown()
    }

    where:
    expectedParallelism                | name               | executorSupplier
    1                                  | "fixed-tpe-1"      | { Executors.newFixedThreadPool(1) }
    10                                 | "fixed-tpe-10"     | { Executors.newFixedThreadPool(10) }
    1                                  | "fixed-tpe-single" | { Executors.newSingleThreadExecutor() }
    -1 /* won't record it */           | "cached-tpe"       | { Executors.newCachedThreadPool() }
    10                                 | "scheduled-10"     | { Executors.newScheduledThreadPool(10) }
    1                                  | "scheduled-1"      | { Executors.newScheduledThreadPool(1) }
    1                                  | "scheduled-single" | { Executors.newSingleThreadScheduledExecutor() }
    10                                 | "fjp-10"           | { Executors.newWorkStealingPool(10) }
    ForkJoinPool.commonPoolParallelism | "fjp-common"       | { ForkJoinPool.commonPool() }
  }
}
