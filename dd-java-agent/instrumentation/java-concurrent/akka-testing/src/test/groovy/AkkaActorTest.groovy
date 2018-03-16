import datadog.opentracing.DDSpan
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import spock.lang.Shared
import spock.lang.Unroll

import java.lang.reflect.Method
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AkkaActorTest extends AgentTestRunner {
  static {
    System.setProperty("dd.integration.java_concurrent.enabled", "true")
  }

  def setupSpec() {
  }

  def "some test"() {
    when:
    AkkaActors akkaTester = new AkkaActors()
    akkaTester.basicGreeting()

    then:
    noExceptionThrown()
  }
}
