import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.springframework.boot.actuate.scheduling.ScheduledTasksEndpoint
import org.springframework.context.annotation.AnnotationConfigApplicationContext

import java.util.concurrent.TimeUnit

class SpringSchedulingTest extends InstrumentationSpecification {

  def legacyTracing() {
    false
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    if (legacyTracing()) {
      injectSysConfig("spring-scheduling.legacy.tracing.enabled", "true")
    }
  }

  def "schedule trigger test according to cron expression"() {
    setup:
    def context = new AnnotationConfigApplicationContext(TriggerTaskConfig, SchedulingConfig)
    def task = context.getBean(TriggerTask)

    task.blockUntilExecute()

    expect:
    assert task != null
    def hasParent = legacyTracing()
    assertTraces(hasParent ? 1 : 2) {
      if (!hasParent) {
        trace(1) {
          basicSpan(it, "parent")
        }
      }
      trace(hasParent ? 2 : 1) {
        if (hasParent) {
          basicSpan(it, "parent")
        }
        span {
          resourceName "TriggerTask.run"
          operationName "scheduled.call"
          hasParent ? childOfPrevious() : parent()
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
    def context = new AnnotationConfigApplicationContext(IntervalTaskConfig, SchedulingConfig)
    def task = context.getBean(IntervalTask)

    task.blockUntilExecute()

    expect:
    assert task != null
    def hasParent = legacyTracing()

    assertTraces(hasParent ? 1 : 2) {
      if (!hasParent) {
        trace(1) {
          basicSpan(it, "parent")
        }
      }
      trace(hasParent ? 2 : 1) {
        if (hasParent) {
          basicSpan(it, "parent")
        }
        span {
          resourceName "IntervalTask.run"
          operationName "scheduled.call"
          hasParent ? childOfPrevious() : parent()
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
    def context = new AnnotationConfigApplicationContext(LambdaTaskConfig, SchedulingConfig)
    def configurer = context.getBean(LambdaTaskConfigurer)

    configurer.singleUseLatch.await(2000, TimeUnit.MILLISECONDS)

    expect:
    def hasParent = legacyTracing()
    assertTraces(hasParent ? 1 : 2) {
      if (!hasParent) {
        trace(1) {
          basicSpan(it, "parent")
        }
      }
      trace(hasParent ? 2 : 1) {
        if (hasParent) {
          basicSpan(it, "parent")
        }
        span {
          resourceNameContains("LambdaTaskConfigurer\$\$Lambda")
          operationName "scheduled.call"
          hasParent ? childOfPrevious() : parent()
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

class SpringSchedulingLegacyTracingForkedTest extends SpringSchedulingTest {
  @Override
  def legacyTracing() {
    true
  }
}

