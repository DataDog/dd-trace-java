import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.springframework.context.annotation.AnnotationConfigApplicationContext

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SpringSchedulingTest extends AgentTestRunner {

  def "schedule trigger test according to cron expression"() {
    setup:
    def context = new AnnotationConfigApplicationContext(TriggerTaskConfig)
    def task = context.getBean(TriggerTask)

    task.blockUntilExecute()

    expect:
    assert task != null
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          resourceName "TriggerTask.run"
          operationName "scheduled.call"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "spring-scheduling"
            defaultTags()
          }
        }
      }
    }
  }
  
  def "schedule interval test"() {
    setup:
    def context = new AnnotationConfigApplicationContext(IntervalTaskConfig)
    def task = context.getBean(IntervalTask)

    task.blockUntilExecute()

    expect:
    assert task != null
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          resourceName "IntervalTask.run"
          operationName "scheduled.call"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "spring-scheduling"
            defaultTags()
          }
        }
      }
    }

  }

  def "schedule lambda test"() {
    setup:
    def context = new AnnotationConfigApplicationContext(LambdaTaskConfig)
    def configurer = context.getBean(LambdaTaskConfigurer)

    configurer.singleUseLatch.await(2000, TimeUnit.MILLISECONDS)

    expect:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          resourceNameContains("LambdaTaskConfigurer\$\$Lambda\$")
          operationName "scheduled.call"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "spring-scheduling"
            defaultTags()
          }
        }
      }
    }

    cleanup:
    context.close()
  }

  def "context propagated through @async annotation" () {
    setup:
    def context = new AnnotationConfigApplicationContext(AsyncTaskConfig)
    AsyncTask asyncTask = context.getBean(AsyncTask)

    when:
    runUnderTrace("root") {
      asyncTask.async()
      .thenApply({
        new Leaf().leaf(it)
      }).join()
    }
    then:
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          resourceName "root"
          threadNameStartsWith "main"
        }
        span(1) {
          resourceName "AsyncTask.async"
          childOf span(0)
          threadNameStartsWith "SimpleAsyncTaskExecutor"
        }
        span(2) {
          resourceName "Leaf.leaf"
          childOf span(1)
          threadNameStartsWith "SimpleAsyncTaskExecutor"
        }
      }
    }

  }

  class Leaf {
    @Trace
    int leaf(int i) {
      return i
    }
  }
}
