import datadog.trace.agent.test.InstrumentationSpecification
import org.apache.tomcat.util.threads.TaskQueue
import org.apache.tomcat.util.threads.ThreadPoolExecutor
import spock.lang.Shared

import java.util.concurrent.Callable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ExecutorLambdaTest extends InstrumentationSpecification {
  @Shared
  def executeRunnable = { e, c -> e.execute((Runnable) c) }
  @Shared
  def submitRunnable = { e, c -> e.submit((Runnable) c) }
  @Shared
  def submitCallable = { e, c -> e.submit((Callable) c) }
  @Shared
  def scheduleRunnable = { e, c -> e.schedule((Runnable) c, 10, TimeUnit.MILLISECONDS) }
  @Shared
  def scheduleCallable = { e, c -> e.schedule((Callable) c, 10, TimeUnit.MILLISECONDS) }

  def "#poolName '#name' wrap lambdas"() {
    setup:
    def pool = poolImpl
    def m = method
    def w = wrap
    JavaAsyncChild child = new JavaAsyncChild(true, true)
    new Runnable() {
        @Override
        void run() {
          runUnderTrace("parent") {
            m(pool, w(child))
          }
        }
      }.run()
    child.unblock()

    TEST_WRITER.waitForTraces(1)

    expect:
    TEST_WRITER.size() == 1
    TEST_WRITER.get(0).size() == 2
    TEST_WRITER.get(0).get(1).operationName == "parent"
    TEST_WRITER.get(0).get(0).operationName == "asyncChild"
    TEST_WRITER.get(0).get(0).parentId == TEST_WRITER.get(0).get(1).spanId

    cleanup:
    pool?.shutdown()

    where:
    name                | method           | wrap                                 | poolImpl
    "execute Runnable"  | executeRunnable  | { LambdaGenerator.wrapRunnable(it) } | new ScheduledThreadPoolExecutor(1)
    "submit Runnable"   | submitRunnable   | { LambdaGenerator.wrapRunnable(it) } | new ScheduledThreadPoolExecutor(1)
    "submit Callable"   | submitCallable   | { LambdaGenerator.wrapCallable(it) } | new ScheduledThreadPoolExecutor(1)
    "schedule Runnable" | scheduleRunnable | { LambdaGenerator.wrapRunnable(it) } | new ScheduledThreadPoolExecutor(1)
    "schedule Callable" | scheduleCallable | { LambdaGenerator.wrapCallable(it) } | new ScheduledThreadPoolExecutor(1)

    // TOMCAT uses it's own queue type TaskQueue
    "execute Runnable"  | executeRunnable  | { LambdaGenerator.wrapRunnable(it) } | new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())
    "submit Runnable"   | submitRunnable   | { LambdaGenerator.wrapRunnable(it) } | new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())
    "submit Callable"   | submitCallable   | { LambdaGenerator.wrapCallable(it) } | new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())

    poolName = poolImpl.class.simpleName
  }
}
