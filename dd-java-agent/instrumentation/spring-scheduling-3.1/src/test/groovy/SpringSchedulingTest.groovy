import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.springframework.boot.actuate.scheduling.ScheduledTasksEndpoint
import org.springframework.context.annotation.AnnotationConfigApplicationContext

import java.util.concurrent.TimeUnit

class SpringSchedulingTest extends AgentTestRunner {

  def "schedule trigger test according to cron expression"() {
    setup:
    def context = new AnnotationConfigApplicationContext(TriggerTaskConfig)
    def task = context.getBean(TriggerTask)

    task.blockUntilExecute()

    expect:
    assert task != null
    assertTraces(1) {
      trace(1) {
        span {
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
    and:
    def scheduledTaskEndpoint = context.getBean(ScheduledTasksEndpoint)
    assert scheduledTaskEndpoint != null
    scheduledTaskEndpoint.scheduledTasks().getCron().each {
      it.getRunnable().getTarget() == TriggerTask.getName()
    }
    cleanup:
    context.close()
  }

  def "schedule interval test"() {
    setup:
    def context = new AnnotationConfigApplicationContext(IntervalTaskConfig)
    def task = context.getBean(IntervalTask)

    task.blockUntilExecute()

    expect:
    assert task != null
    assertTraces(1) {
      trace(1) {
        span {
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
    cleanup:
    context.close()
  }

  def "schedule lambda test"() {
    setup:
    def context = new AnnotationConfigApplicationContext(LambdaTaskConfig)
    def configurer = context.getBean(LambdaTaskConfigurer)

    configurer.singleUseLatch.await(2000, TimeUnit.MILLISECONDS)

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceNameContains("LambdaTaskConfigurer\$\$Lambda")
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
}
