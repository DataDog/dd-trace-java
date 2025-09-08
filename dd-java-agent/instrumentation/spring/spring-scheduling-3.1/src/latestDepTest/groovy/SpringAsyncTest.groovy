import datadog.trace.agent.test.InstrumentationSpecification
import org.springframework.context.annotation.AnnotationConfigApplicationContext

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SpringAsyncTest extends InstrumentationSpecification {

  def "context propagated through @async annotation"() {
    setup:
    def context = new AnnotationConfigApplicationContext(AsyncTaskConfig)
    AsyncTask asyncTask = context.getBean(AsyncTask)
    when:
    runUnderTrace("root") {
      asyncTask.async().join()
    }
    then:
    assertTraces(1) {
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
    }
  }
}
