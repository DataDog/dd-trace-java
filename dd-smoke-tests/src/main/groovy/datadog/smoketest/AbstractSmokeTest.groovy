package datadog.smoketest

import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.function.Function
import datadog.trace.test.agent.decoder.DecodedSpan
import datadog.trace.test.agent.decoder.Decoder
import datadog.trace.test.agent.decoder.DecodedMessage
import datadog.trace.test.agent.decoder.DecodedTrace

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork

abstract class AbstractSmokeTest extends ProcessManager {
  @Shared
  protected AtomicInteger traceCount = new AtomicInteger()

  @Shared
  protected CopyOnWriteArrayList<DecodedTrace> decodeTraces = new CopyOnWriteArrayList()

  @Shared
  private Closure decode = decodedTracesCallback()

  @Shared
  @AutoCleanup
  protected TestHttpServer server = httpServer {
    handlers {
      prefix("/v0.4/traces") {
        def countString = request.getHeader("X-Datadog-Trace-Count")
        int count = countString != null ? Integer.parseInt(countString) : 0
        def body = request.getBody()
        if (body.length && decode) {
          try {
            DecodedMessage message = Decoder.decodeV04(body)
            assert message.getTraces().size() == count
            def traces = message.traces
            decode(traces)
            decodeTraces.addAll(traces)
          } catch (Throwable t) {
            println("=== Failure during message v0.4 decoding ===")
            t.printStackTrace(System.out)
            throw t
          }
        }
        traceCount.addAndGet(count)
        println("Received v0.4 traces: " + countString)
        response.status(200).send()
      }
      prefix("/v0.5/traces") {
        def countString = request.getHeader("X-Datadog-Trace-Count")
        int count = countString != null ? Integer.parseInt(countString) : 0
        def body = request.getBody()
        if (body.length && decode) {
          try {
            DecodedMessage message = Decoder.decode(body)
            assert message.getTraces().size() == count
            def traces = message.traces
            decode(traces)
            decodeTraces.addAll(traces)
          } catch (Throwable t) {
            println("=== Failure during message v0.5 decoding ===")
            t.printStackTrace(System.out)
            throw t
          }
        }
        traceCount.addAndGet(count)
        println("Received v0.5 traces: " + countString)
        response.status(200).send()
      }
    }
  }

  @Shared
  protected String[] defaultJavaProperties = [
    "${getMaxMemoryArgumentForFork()}",
    "${getMinMemoryArgumentForFork()}",
    "-javaagent:${shadowJarPath}",
    isIBM ? "-Xdump:directory=/tmp" : "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
    "-Ddd.trace.agent.port=${server.address.port}",
    "-Ddd.service.name=${SERVICE_NAME}",
    "-Ddd.env=${ENV}",
    "-Ddd.version=${VERSION}",
    "-Ddd.profiling.enabled=true",
    "-Ddd.profiling.start-delay=${PROFILING_START_DELAY_SECONDS}",
    "-Ddd.profiling.upload.period=${PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS}",
    "-Ddd.profiling.url=${getProfilingUrl()}",
    "-Ddd.profiling.async.enabled=true",
    "-Ddd.profiling.tracing_context.enabled=true",
    "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=${logLevel()}",
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=${logLevel()}"
  ]

  def setup() {
    traceCount.set(0)
    decodeTraces.clear()
  }

  def cleanup() {
    decodeTraces.clear()
  }

  def setupSpec() {
    startServer()
  }

  def cleanupSpec() {
    stopServer()
  }

  def startServer() {
    server.start()
  }

  def stopServer() {
    // do nothing; 'server' is autocleanup
  }

  Closure decodedTracesCallback() {
    null
  }

  int waitForTraceCount(int count) {
    def conditions = new PollingConditions(timeout: 1000, initialDelay: 0, delay: 0.5, factor: 1)
    return waitForTraceCount(count, conditions)
  }

  int waitForTraceCount(int count, PollingConditions conditions) {
    conditions.eventually {
      assert traceCount.get() >= count
    }
    traceCount.get()
  }

  void waitForTrace(final PollingConditions poll, final Function<DecodedTrace, Boolean> predicate) {
    poll.eventually {
      assert decodeTraces.find { predicate.apply(it) } != null
    }
  }

  void waitForSpan(final PollingConditions poll, final Function<DecodedSpan, Boolean> predicate) {
    waitForTrace(poll) { trace ->
      trace.spans.find {
        predicate.apply(it)
      }
    }
  }

  List<DecodedTrace> getTraces() {
    decodeTraces
  }

  def logLevel() {
    return "info"
  }
}
