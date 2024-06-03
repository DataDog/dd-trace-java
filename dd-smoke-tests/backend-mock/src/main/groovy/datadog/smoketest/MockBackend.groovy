package datadog.smoketest

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.test.util.MultipartRequestParser
import org.apache.commons.io.IOUtils
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.util.concurrent.PollingConditions

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class MockBackend implements AutoCloseable {

  private static final ObjectMapper MSG_PACK_MAPPER = new ObjectMapper(new MessagePackFactory())
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper()

  private final Queue<Map<String, Object>> receivedTraces = new ConcurrentLinkedQueue<>()

  private final Queue<Map<String, Object>> receivedCoverages = new ConcurrentLinkedQueue<>()

  private final Queue<Map<String, Object>> receivedTelemetryMetrics = new ConcurrentLinkedQueue<>()

  private final Queue<Map<String, Object>> receivedTelemetryDistributions = new ConcurrentLinkedQueue<>()
  private final Queue<Map<String, Object>> receivedLogs = new ConcurrentLinkedQueue<>()

  private final Collection<Map<String, Object>> skippableTests = new CopyOnWriteArrayList<>()
  private final Collection<Map<String, Object>> flakyTests = new CopyOnWriteArrayList<>()

  private boolean codeCoverageEnabled = true

  private boolean testsSkippingEnabled = true

  private boolean flakyRetriesEnabled = false

  void reset() {
    receivedTraces.clear()
    receivedCoverages.clear()
    receivedTelemetryMetrics.clear()
    receivedTelemetryDistributions.clear()
    receivedLogs.clear()

    skippableTests.clear()
    flakyTests.clear()
  }

  @Override
  void close() throws Exception {
    intakeServer.close()
  }

  void givenFlakyRetries(boolean flakyRetries) {
    this.flakyRetriesEnabled = flakyRetries
  }

  void givenFlakyTest(String module, String suite, String name) {
    flakyTests.add(["module": module, "suite": suite, "name": name])
  }

  void givenSkippableTest(String module, String suite, String name) {
    skippableTests.add(["module": module, "suite": suite, "name": name])
  }

  String getIntakeUrl() {
    return intakeServer.address.toString()
  }

  private final TestHttpServer intakeServer = httpServer {
    handlers {
      prefix("/api/v2/citestcycle") {
        def contentEncodingHeader = request.getHeader("Content-Encoding")
        def gzipEnabled = contentEncodingHeader != null && contentEncodingHeader.contains("gzip")
        def requestBody = gzipEnabled ? MockBackend.decompress(request.body) : request.body
        def decodedEvent = MSG_PACK_MAPPER.readValue(requestBody, Map)
        receivedTraces.add(decodedEvent)

        response.status(200).send()
      }

      prefix("/api/v2/citestcov") {
        def contentEncodingHeader = request.getHeader("Content-Encoding")
        def gzipEnabled = contentEncodingHeader != null && contentEncodingHeader.contains("gzip")
        def requestBody = gzipEnabled ? MockBackend.decompress(request.body) : request.body
        def parsed = MultipartRequestParser.parseRequest(requestBody, request.headers.get("Content-Type"))
        def coverages = parsed.get("coverage1")
        for (def coverage : coverages) {
          def decodedCoverage = MSG_PACK_MAPPER.readValue(coverage.get(), Map)
          receivedCoverages.add(decodedCoverage)
        }

        response.status(202).send()
      }

      prefix("/api/v2/libraries/tests/services/setting") {
        // not compressing settings response on purpose, to better mimic real backend behavior:
        // it may choose to compress the response or not based on its size,
        // so smaller responses (like those of /setting endpoint) are uncompressed,
        // while the larger ones (skippable and flaky test lists) are compressed
        response.status(200).send(('{ "data": { "type": "ci_app_tracers_test_service_settings", "id": "uuid", "attributes": { '
          + '"code_coverage": ' + codeCoverageEnabled
          + ', "tests_skipping": ' + testsSkippingEnabled
          + ', "flaky_test_retries_enabled": ' + flakyRetriesEnabled +  '} } }').bytes)
      }

      prefix("/api/v2/ci/tests/skippable") {
        StringBuilder skippableTestsResponse = new StringBuilder("[")
        def i = skippableTests.iterator()
        while (i.hasNext()) {
          def test = i.next()
          skippableTestsResponse.append("""
          {
            "id": "${UUID.randomUUID().toString()}",
            "type": "test",
            "attributes": {
              "configurations": {
                  "test.bundle": "$test.module"
              },
              "name": "$test.name",
              "suite": "$test.suite"
            }
          }
          """)
          if (i.hasNext()) {
            skippableTestsResponse.append(',')
          }
        }
        skippableTestsResponse.append("]")

        response.status(200)
          .addHeader("Content-Encoding", "gzip")
          .send(MockBackend.compress((""" { "data": $skippableTestsResponse } """).bytes))
      }

      prefix("/api/v2/ci/libraries/tests/flaky") {
        StringBuilder flakyTestsResponse = new StringBuilder("[")
        def i = flakyTests.iterator()
        while (i.hasNext()) {
          def test = i.next()
          flakyTestsResponse.append("""
          {
            "id": "${UUID.randomUUID().toString()}",
            "type": "test",
            "attributes": {
              "configurations": {
                  "test.bundle": "$test.module"
              },
              "name": "$test.name",
              "suite": "$test.suite"
            }
          }
          """)
          if (i.hasNext()) {
            flakyTestsResponse.append(',')
          }
        }
        flakyTestsResponse.append("]")

        response.status(200)
          .addHeader("Content-Encoding", "gzip")
          .send(MockBackend.compress((""" { "data": $flakyTestsResponse } """).bytes))
      }

      prefix("/api/v2/apmtelemetry") {
        def telemetryRequest = JSON_MAPPER.readerFor(Map.class).readValue(request.body)
        def requestType = telemetryRequest["request_type"]
        if (requestType == "message-batch") {
          for (def message : telemetryRequest["payload"]) {
            def payload = message["payload"]
            if (message["request_type"] == 'generate-metrics') {
              receivedTelemetryMetrics.addAll((List) payload["series"])
            } else if (message["request_type"] == 'distributions') {
              receivedTelemetryDistributions.addAll((List) payload["series"])
            }
          }
        }
        response.status(202).send()
      }

      prefix("/api/v2/logs") {
        def contentEncodingHeader = request.getHeader("Content-Encoding")
        def gzipEnabled = contentEncodingHeader != null && contentEncodingHeader.contains("gzip")
        def requestBody = gzipEnabled ? MockBackend.decompress(request.body) : request.body
        def decodedEvent = JSON_MAPPER.readValue(requestBody, List)
        receivedLogs.addAll(decodedEvent)

        response.status(200).send()
      }
    }
  }

  private static byte[] compress(byte[] bytes) {
    def baos = new ByteArrayOutputStream()
    try (GZIPOutputStream zip = new GZIPOutputStream(baos)) {
      IOUtils.copy(new ByteArrayInputStream(bytes), zip)
    }
    return baos.toByteArray()
  }

  private static byte[] decompress(byte[] bytes) {
    def baos = new ByteArrayOutputStream()
    try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      IOUtils.copy(zip, baos)
    }
    return baos.toByteArray()
  }

  List<Map<String, Object>> waitForEvents(int expectedEventsSize) {
    def traceReceiveConditions = new PollingConditions(timeout: 15, initialDelay: 1, delay: 0.5, factor: 1)
    try {
      traceReceiveConditions.eventually {
        int eventsSize = 0
        for (Map<String, Object> trace : receivedTraces) {
          eventsSize += trace["events"].size()
        }
        assert eventsSize >= expectedEventsSize
      }
    } catch (AssertionError e) {
      throw new AssertionError("Error while waiting for $expectedEventsSize trace events, received: $receivedTraces", e)
    }

    List<Map<String, Object>> events = new ArrayList<>()
    while (!receivedTraces.isEmpty()) {
      def trace = receivedTraces.poll()
      events.addAll((List<Map<String, Object>>) trace["events"])
    }
    return events
  }

  List<Map<String, Object>> waitForCoverages(int expectedCoveragesSize) {
    def traceReceiveConditions = new PollingConditions(timeout: 15, initialDelay: 1, delay: 0.5, factor: 1)
    try {
      traceReceiveConditions.eventually {
        int coveragesSize = 0
        for (Map<String, Object> trace : receivedCoverages) {
          coveragesSize += trace["coverages"].size()
        }
        assert coveragesSize >= expectedCoveragesSize
      }
    } catch (AssertionError e) {
      throw new AssertionError("Error while waiting for $expectedCoveragesSize coverages, received: $receivedCoverages", e)
    }

    List<Map<String, Object>> coverages = new ArrayList<>()
    while (!receivedCoverages.isEmpty()) {
      def trace = receivedCoverages.poll()
      coverages.addAll((List<Map<String, Object>>) trace["coverages"])
    }
    return coverages
  }

  List<Map<String, Object>> waitForLogs(int expectedCount) {
    def traceReceiveConditions = new PollingConditions(timeout: 15, initialDelay: 1, delay: 0.5, factor: 1)
    traceReceiveConditions.eventually {
      assert receivedLogs.size() == expectedCount
    }

    List<Map<String, Object>> logs = new ArrayList<>()
    while (!receivedLogs.isEmpty()) {
      logs.add(receivedLogs.poll())
    }
    return logs
  }

  List<Map<String, Object>> getAllReceivedTelemetryMetrics() {
    return new ArrayList(receivedTelemetryMetrics)
  }

  List<Map<String, Object>> getAllReceivedTelemetryDistributions() {
    return new ArrayList(receivedTelemetryDistributions)
  }
}
