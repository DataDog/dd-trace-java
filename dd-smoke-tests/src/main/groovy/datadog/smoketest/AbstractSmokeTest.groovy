package datadog.smoketest

import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.agent.test.utils.PortUtils
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork

abstract class AbstractSmokeTest extends ProcessManager {
  @Shared
  protected BlockingQueue<TestHttpServer.HandlerApi.RequestApi> traceRequests = new LinkedBlockingQueue<>()

  @Shared
  protected AtomicInteger traceCount = new AtomicInteger()

  @Shared
  @AutoCleanup
  protected TestHttpServer server = httpServer {
    handlers {
      prefix("/v0.4/traces") {
        def countString = request.getHeader("X-Datadog-Trace-Count")
        int count = countString != null ? Integer.parseInt(countString) : 0
        traceCount.addAndGet(count)
        println("Received traces: " + countString)
        traceRequests.add(request)
        response.status(200).send()
      }
    }
  }

  @Shared
  protected String[] defaultJavaProperties = [
    "${getMaxMemoryArgumentForFork()}",
    "${getMinMemoryArgumentForFork()}",
    "-javaagent:${shadowJarPath}",
    "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
    "-Ddd.trace.agent.port=${server.address.port}",
    "-Ddd.service.name=${SERVICE_NAME}",
    "-Ddd.env=${ENV}",
    "-Ddd.version=${VERSION}",
    "-Ddd.profiling.enabled=true",
    "-Ddd.profiling.start-delay=${PROFILING_START_DELAY_SECONDS}",
    "-Ddd.profiling.upload.period=${PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS}",
    "-Ddd.profiling.url=${getProfilingUrl()}",
    "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
  ]

  def setup() {
    traceRequests.clear()
    traceCount.set(0)
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

  int waitForTraceCount(int count) {
    long start = System.nanoTime()
    long timeout = TimeUnit.SECONDS.toNanos(10)
    int current = traceCount.get()
    while (current < count) {
      if (System.nanoTime() - start >= timeout) {
        throw new TimeoutException("Timed out waiting for " + count + " traces. Have only received " + current + ".")
      }
      Thread.sleep(500)
      current = traceCount.get()
    }
    return current
  }
}
