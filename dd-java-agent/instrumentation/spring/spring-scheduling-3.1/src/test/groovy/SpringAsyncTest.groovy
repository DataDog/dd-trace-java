import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.springframework.context.annotation.AnnotationConfigApplicationContext

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SpringAsyncTest extends InstrumentationSpecification {

  boolean asyncMeasured() {
    false
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    if (asyncMeasured()) {
      injectSysConfig("spring-scheduling.measured.enabled", "true")
    }
  }

  def "exception in @async method tags span as errored"() {
    setup:
    def context = new AnnotationConfigApplicationContext(AsyncTaskConfig)
    AsyncTask asyncTask = context.getBean(AsyncTask)

    when:
    Throwable thrown = null
    try {
      asyncTask.asyncThrow().join()
    } catch (Throwable t) {
      thrown = t
    }

    then:
    thrown != null
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "AsyncTask.asyncThrow"
          errored true
          measured asyncMeasured()
          tags {
            "$Tags.COMPONENT" "spring-scheduling"
            errorTags(RuntimeException, "Datadog async repro")
            defaultTags()
          }
        }
      }
    }

    cleanup:
    context.close()
  }

  def "context propagated through @async annotation"() {
    setup:
    def context = new AnnotationConfigApplicationContext(AsyncTaskConfig)
    AsyncTask asyncTask = context.getBean(AsyncTask)
    when:
    if (hasParent) {
      runUnderTrace("root") {
        asyncTask.async().join()
      }
    } else {
      asyncTask.async().join()
    }
    then:
    assertTraces(1) {
      if (hasParent) {
        trace(3) {
          span {
            resourceName "root"
          }
          span {
            resourceName "AsyncTask.async"
            threadNameStartsWith "SimpleAsyncTaskExecutor"
            childOf span(0)
          }
          span {
            resourceName "AsyncTask.getInt"
            threadNameStartsWith "SimpleAsyncTaskExecutor"
            childOf span(1)
          }
        }
      } else {
        trace(2) {
          span {
            resourceName "AsyncTask.async"
            threadNameStartsWith "SimpleAsyncTaskExecutor"
          }
          span {
            resourceName "AsyncTask.getInt"
            threadNameStartsWith "SimpleAsyncTaskExecutor"
            childOf span(0)
          }
        }
      }
    }

    where:
    hasParent << [true, false]
  }
}

class SpringAsyncMeasuredForkedTest extends SpringAsyncTest {
  @Override
  boolean asyncMeasured() {
    true
  }
}
