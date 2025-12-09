import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.Trace
import datadog.trace.core.DDSpan
import datadog.trace.test.util.Flaky

// Note: test builder x2 + test factory can be refactored but are kept simple to ease with debugging.
@Flaky("class loader deadlock on virtual thread clean up while Groovy do dynamic code generation - APMLP-782")
class VirtualThreadApiTest extends InstrumentationSpecification {
  def "test Thread.Builder.OfVirtual - start()"() {
    setup:
    def threadBuilder = Thread.ofVirtual().name("builder - started")

    when:
    new Runnable() {
        @Override
        @Trace(operationName = "parent")
        void run() {
          // this child will have a span
          threadBuilder.start(new JavaAsyncChild())
          // this child won't
          threadBuilder.start(new JavaAsyncChild(false, false))
          blockUntilChildSpansFinished(1)
        }
      }.run()

    then:
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace.get(0).operationName == "parent"
    trace.get(1).operationName == "asyncChild"
    trace.get(1).parentId == trace.get(0).spanId
  }

  def "test Thread.Builder.OfVirtual - unstarted()"() {
    setup:
    def threadBuilder = Thread.ofVirtual().name("builder - unstarted")

    when:
    new Runnable() {
        @Override
        @Trace(operationName = "parent")
        void run() {
          // this child will have a span
          threadBuilder.unstarted(new JavaAsyncChild()).start()
          // this child won't
          threadBuilder.unstarted(new JavaAsyncChild(false, false)).start()
          blockUntilChildSpansFinished(1)
        }
      }.run()


    then:
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace.get(0).operationName == "parent"
    trace.get(1).operationName == "asyncChild"
    trace.get(1).parentId == trace.get(0).spanId
  }

  def "test Thread.startVirtual()"() {
    when:
    new Runnable() {
        @Override
        @Trace(operationName = "parent")
        void run() {
          // this child will have a span
          Thread.startVirtualThread(new JavaAsyncChild())
          // this child won't
          Thread.startVirtualThread(new JavaAsyncChild(false, false))
          blockUntilChildSpansFinished(1)
        }
      }.run()

    then:
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace.get(0).operationName == "parent"
    trace.get(1).operationName == "asyncChild"
    trace.get(1).parentId == trace.get(0).spanId
  }

  def "test virtual ThreadFactory"() {
    setup:
    def threadFactory = Thread.ofVirtual().factory()

    when:
    new Runnable() {
        @Override
        @Trace(operationName = "parent")
        void run() {
          // this child will have a span
          threadFactory.newThread(new JavaAsyncChild()).start()
          // this child won't
          threadFactory.newThread(new JavaAsyncChild(false, false)).start()
          blockUntilChildSpansFinished(1)
        }
      }.run()

    then:
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace.get(0).operationName == "parent"
    trace.get(1).operationName == "asyncChild"
    trace.get(1).parentId == trace.get(0).spanId
  }

  def "test nested virtual threads"() {
    setup:
    def threadBuilder = Thread.ofVirtual()

    when:
    new Runnable() {
        @Trace(operationName = "parent")
        @Override
        void run() {
          threadBuilder.start(new Runnable() {
              @Trace(operationName = "child")
              @Override
              void run() {
                threadBuilder.start(new Runnable() {
                    @Trace(operationName = "great-child")
                    @Override
                    void run() {
                      println "complete"
                    }
                  })
                blockUntilChildSpansFinished(1)
              }
            })
          blockUntilChildSpansFinished(1)
        }
      }.run()

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(3) {
        span {
          operationName "parent"
        }
        span {
          childOfPrevious()
          operationName "child"
        }
        span {
          childOfPrevious()
          operationName "great-child"
        }
      }
    }
  }
}
