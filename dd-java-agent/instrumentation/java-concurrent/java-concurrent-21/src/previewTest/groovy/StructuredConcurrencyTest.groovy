import com.sun.management.HotSpotDiagnosticMXBean
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace

import javax.management.MBeanServer
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.StructuredTaskScope

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace
import static java.time.Instant.now

class StructuredConcurrencyTest extends AgentTestRunner {
  ThreadDumpLogger threadDumpLogger

  def setup() {
    // Use the current feature name as the test name
    String testName = "${specificationContext?.currentSpec?.name ?: "unknown-spec"} : ${specificationContext?.currentFeature?.name ?: "unknown-test"}"

    threadDumpLogger = new ThreadDumpLogger(testName)
    threadDumpLogger.start()
  }

  def cleanup() {
    threadDumpLogger.stop()
  }

  /**
   * Tests the structured task scope with a single task.
   */
  def "test single task"() {
    setup:
    def taskScope = new StructuredTaskScope.ShutdownOnFailure()
    def result = false

    when:
    runUnderTrace("parent") {
      def task = taskScope.fork(new Callable<Boolean>() {
          @Trace(operationName = "child")
          @Override
          Boolean call() throws Exception {
            return true
          }
        })
      taskScope.joinUntil(now() + 10) // Wait for 10 seconds at maximum
      result = task.get()
    }
    taskScope.close()

    then:
    result
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        span(0) {
          parent()
          operationName "parent"
        }
        span(1) {
          childOfPrevious()
          operationName "child"
        }
      }
    }
  }

  /**
   * Tests the structured task scope with a multiple tasks.
   * Here is the expected task/span structure:
   * <pre>
   *   parent
   *   |-- child1
   *   |-- child2
   *   \-- child3
   * </pre>
   */
  def "test multiple tasks"() {
    setup:
    def taskScope = new StructuredTaskScope.ShutdownOnFailure()

    when:
    runUnderTrace("parent") {
      taskScope.fork {
        runnableUnderTrace("child1") {}
      }
      taskScope.fork {
        runnableUnderTrace("child2") {}
      }
      taskScope.fork {
        runnableUnderTrace("child3") {}
      }
      taskScope.joinUntil(now() + 10) // Wait for 10 seconds at maximum
    }
    taskScope.close()

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(4) {
        span {
          parent()
          operationName "parent"
        }
        def parent = span(0)
        span {
          childOf(parent)
          assert span.operationName.toString().startsWith("child")
        }
        span {
          childOf(parent)
          assert span.operationName.toString().startsWith("child")
        }
        span {
          childOf(parent)
          assert span.operationName.toString().startsWith("child")
        }
      }
    }
  }

  /**
   * Tests the structured task scope with a multiple nested tasks.
   * Here is the expected task/span structure:
   * <pre>
   *   parent
   *   |-- child1
   *   |   |-- great-child1-1
   *   |   \-- great-child1-2
   *   \-- child2
   * </pre>
   */
  def "test nested tasks"() {
    setup:
    def taskScope = new StructuredTaskScope.ShutdownOnFailure()

    when:
    runUnderTrace("parent") {
      taskScope.fork {
        runnableUnderTrace("child1") {
          taskScope.fork {
            runnableUnderTrace("great-child1-1") {}
          }
          taskScope.fork {
            runnableUnderTrace("great-child1-2") {}
          }
        }
      }
      taskScope.fork {
        runnableUnderTrace("child2") {}
      }
      taskScope.joinUntil(now() + 10) // Wait for 10 seconds at maximum
    }
    taskScope.close()

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(5) {
        // Check parent span
        span {
          parent()
          operationName "parent"
        }
        def parent = span(0)
        // Check child and great child spans
        def child1 = null
        for (i in 0..<4) {
          span {
            def name = span.operationName.toString()
            if (name.startsWith("child")) {
              childOf(parent)
              if (name == "child1") {
                child1 = span
              }
            } else if (name.startsWith("great-child1")) {
              childOf(child1) // We can assume child1 will be set as spans are sorted by start time
            }
          }
        }
      }
    }
  }

  // 🔒 Private helper class for thread dump logging
  private static class ThreadDumpLogger {
    private final String testName
    private Thread task

    ThreadDumpLogger(String testName) {
      this.testName = testName
    }

    void start() {
      task = new Thread() {
          @Override
          void run() {
            sleep(20000)

            File outputDir = new File("build")
            String fullPath = outputDir.absolutePath.replace("dd-trace-java/dd-java-agent",
            "dd-trace-java/workspace/dd-java-agent")

            outputDir = new File(fullPath)
            if (!outputDir.exists()) {
              println("Folder not found: " + fullPath)
              outputDir.mkdirs()
            } else println("Folder found: " + fullPath)

            // Use the current feature name as the test name
            println("Test name: " + testName)

            heapDump(outputDir, "test_1")

            def reportFile = new File(outputDir, "${System.currentTimeMillis()}-thread-dump.log")

            try (def writer = new FileWriter(reportFile)) {
              writer.write("=== Test: ${testName} ===\n")
              writer.write("=== Thread Dump Triggered at ${new Date()} ===\n")
              getAllStackTraces().each { thread, stack ->
                writer.write("Thread: ${thread.name}, daemon: ${thread.daemon}\n")
                stack.each { writer.write("\tat ${it}\n") }
              }
              writer.write("==============================================\n")
            }

            heapDump(outputDir, "test_2")
          }
        }
      task.start()
    }

    static void heapDump(File outputDir, String kind) {
      def heapDumpFile = new File(outputDir, "${System.currentTimeMillis()}-heap-dump-${kind}.hprof").absolutePath
      MBeanServer server = ManagementFactory.getPlatformMBeanServer()
      HotSpotDiagnosticMXBean mxBean = ManagementFactory.newPlatformMXBeanProxy(
        server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class)
      mxBean.dumpHeap(heapDumpFile, true)
    }

    void stop() {
      task?.interrupt()
    }
  }
}
