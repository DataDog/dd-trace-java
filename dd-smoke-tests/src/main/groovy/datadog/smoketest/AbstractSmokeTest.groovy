package datadog.smoketest

import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.Platform
import datadog.trace.test.agent.decoder.DecodedSpan
import datadog.trace.test.agent.decoder.Decoder
import datadog.trace.test.agent.decoder.DecodedMessage
import datadog.trace.test.agent.decoder.DecodedTrace
import datadog.trace.util.Strings
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.function.Function

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
  private String remoteConfigResponse

  @Shared
  private Throwable traceDecodingFailure = null

  @Shared
  protected CopyOnWriteArrayList<Map<String, Object>> telemetryMessages = new CopyOnWriteArrayList()

  @Shared
  protected CopyOnWriteArrayList<Map<String, Object>> telemetryFlatMessages = new CopyOnWriteArrayList()

  @Shared
  private Throwable telemetryDecodingFailure = null

  @Shared
  protected TestHttpServer.Headers lastTraceRequestHeaders = null

  @Shared
  @AutoCleanup
  protected TestHttpServer server = httpServer {
    handlers {
      prefix("/info") {
        response.status(200).send("""{
          "version": "7.54.1",
          "git_commit": "44d1992",
          "endpoints": [
            "/v0.4/traces",
            "/v0.5/traces",
            "/telemetry/proxy/"
          ],
          "client_drop_p0s": true,
          "span_meta_structs": true,
          "long_running_spans": true,
          "evp_proxy_allowed_headers": [
            "Content-Type",
            "Accept-Encoding",
            "Content-Encoding",
            "User-Agent",
            "DD-CI-PROVIDER-NAME"
          ],
          "config": {}
        }""")
      }
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
            traceDecodingFailure = t
            throw t
          }
        }
        traceCount.addAndGet(count)
        lastTraceRequestHeaders = request.headers
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
            traceDecodingFailure = t
            throw t
          }
        }
        traceCount.addAndGet(count)
        lastTraceRequestHeaders = request.headers
        println("Received v0.5 traces: " + countString)
        response.status(200).send()
      }
      prefix("/v0.6/stats") {
        response.status(200).send()
      }
      prefix("/v0.7/config") {
        response.status(200).send(remoteConfigResponse)
      }
      prefix("/telemetry/proxy/api/v2/apmtelemetry") {
        try {
          byte[] body = request.getBody()
          if (body != null) {
            Map<String, Object> msg = new JsonSlurper().parseText(new String(body, StandardCharsets.UTF_8)) as Map<String, Object>
            telemetryMessages.add(msg)
            if (msg.get("request_type") == "message-batch") {
              msg.get("payload")?.each { telemetryFlatMessages.add(it as Map<String, Object>) }
            } else {
              telemetryFlatMessages.add(msg)
            }
          }
        } catch (Throwable t) {
          println("=== Failure during telemetry decoding ===")
          t.printStackTrace(System.out)
          telemetryDecodingFailure = t
          throw t
        }
        response.status(202).send()
      }
    }
  }

  @Shared
  protected String[] defaultJavaProperties = javaProperties()

  @Shared
  protected String[] nativeJavaProperties = [
    "${getMaxMemoryArgumentForFork()}",
    "${getMinMemoryArgumentForFork()}",
    "-Ddd.trace.agent.port=${server.address.port}",
    "-Ddd.service.name=${SERVICE_NAME}",
    "-Ddd.env=${ENV}",
    "-Ddd.version=${VERSION}"
  ]

  def testCrashTracking() {
    return true
  }

  def javaProperties() {
    def tmpDir = "/tmp"

    def ret = [
      "${getMaxMemoryArgumentForFork()}",
      "${getMinMemoryArgumentForFork()}",
      "-javaagent:${shadowJarPath}",
      isIBM ? "-Xdump:directory=${tmpDir}" : "-XX:ErrorFile=${tmpDir}/hs_err_pid%p.log",
      "-Ddd.trace.agent.port=${server.address.port}",
      "-Ddd.env=${ENV}",
      "-Ddd.version=${VERSION}",
      "-Ddd.profiling.enabled=true",
      "-Ddd.profiling.start-delay=${PROFILING_START_DELAY_SECONDS}",
      "-Ddd.profiling.upload.period=${PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS}",
      "-Ddd.profiling.url=${getProfilingUrl()}",
      "-Ddd.profiling.ddprof.enabled=${isDdprofSafe()}",
      "-Ddd.profiling.ddprof.alloc.enabled=${isDdprofSafe()}",
      "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=${logLevel()}",
      "-Dorg.slf4j.simpleLogger.defaultLogLevel=${logLevel()}",
      "-Ddd.site="
    ]
    if (inferServiceName())  {
      ret += "-Ddd.service.name=${SERVICE_NAME}"
    }
    if (testTelemetry()) {
      ret += "-Ddd.telemetry.heartbeat.interval=5"
    }
    // DQH - Nov 2024 - skipping for J9 which doesn't have full crash tracking support
    if (testCrashTracking() && !Platform.isJ9()) {
      def extension = getScriptExtension()
      ret += "-XX:OnError=\"${tmpDir}/dd_crash_uploader.${extension} %p\""
      // Unlike crash tracking smoke test, keep the default delay; otherwise, otherwise other tests will fail
      // ret += "-Ddd.dogstatsd.start-delay=0"
    }
    ret as String[]
  }

  static String getScriptExtension() {
    return Platform.isWindows() ? "bat" : "sh"
  }

  def inferServiceName() {
    true
  }

  private static boolean isDdprofSafe() {
    // currently the J9 handling of jmethodIDs will cause frequent crashes
    return !Platform.isJ9()
  }


  boolean testTelemetry() {
    return true
  }

  def setup() {
    traceCount.set(0)
    decodeTraces.clear()
    remoteConfigResponse = "{}"
    traceDecodingFailure = null
  }

  def cleanup() {
    decodeTraces.clear()
    if (traceDecodingFailure != null) {
      throw traceDecodingFailure
    }
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

  void setRemoteConfig(String path, String jsonData) {
    def targets = """
{
  "signed" : {
      "expires" : "2022-09-17T12:49:15Z",
      "spec_version" : "1.0.0",
      "targets" : {
        "$path" : {
          "custom": {"v": 1},
          "hashes" : {
            "sha256" : "${ Strings.sha256(jsonData) }"
          },
          "length" : ${ jsonData.length() }
        }
      }
  }
}
"""
    remoteConfigResponse = """
{
  "client_configs" : [ "$path" ],
  "roots" : [],
  "target_files" : [
    {
      "path" : "$path",
      "raw" : "${Base64.encoder.encodeToString(jsonData.getBytes(StandardCharsets.UTF_8))}"
    }
  ],
  "targets" : "${Base64.encoder.encodeToString(targets.getBytes(StandardCharsets.UTF_8))}"
}
""".toString()
  }

  int waitForTraceCount(int count) {
    def conditions = new PollingConditions(timeout: 30, initialDelay: 0, delay: 0.5, factor: 1)
    return waitForTraceCount(count, conditions)
  }

  int waitForTraceCount(int count, PollingConditions conditions) {
    conditions.eventually {
      if (traceDecodingFailure != null) {
        throw traceDecodingFailure
      }
      assert traceCount.get() >= count
    }
    traceCount.get()
  }

  void waitForTrace(final PollingConditions poll, final Function<DecodedTrace, Boolean> predicate) {
    assert decode != null // override decodedTracesCallback to avoid this and enable trace decoding
    poll.eventually {
      if (traceDecodingFailure != null) {
        throw traceDecodingFailure
      }
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

  void waitForTelemetryCount(final int count) {
    def conditions = new PollingConditions(timeout: 30, initialDelay: 0, delay: 1, factor: 1)
    waitForTelemetryCount(conditions, count)
  }

  void waitForTelemetryCount(final PollingConditions poll, final int count) {
    poll.eventually {
      telemetryMessages.size() >= count
    }
  }

  void waitForTelemetryFlat(final Function<Map<String, Object>, Boolean> predicate) {
    def conditions = new PollingConditions(timeout: 30, initialDelay: 0, delay: 1, factor: 1)
    waitForTelemetryFlat(conditions, predicate)
  }

  void waitForTelemetryFlat(final PollingConditions poll, final Function<Map<String, Object>, Boolean> predicate) {
    poll.eventually {
      if (telemetryDecodingFailure != null) {
        throw telemetryDecodingFailure
      }
      assert telemetryFlatMessages.find { predicate.apply(it) } != null
    }
  }

  List<DecodedTrace> getTraces() {
    decodeTraces
  }

  def logLevel() {
    return "info"
  }
}
