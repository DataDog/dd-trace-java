package datadog.trace.civisibility

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.test.util.MultipartRequestParser
import org.apache.commons.io.IOUtils
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

abstract class CiVisibilitySmokeTest extends Specification {

  @Shared
  ObjectMapper msgPackMapper = new ObjectMapper(new MessagePackFactory())

  @Shared
  Queue<Map<String, Object>> receivedTraces = new ConcurrentLinkedQueue<>()

  @Shared
  Queue<Map<String, Object>> receivedCoverages = new ConcurrentLinkedQueue<>()

  @Shared
  boolean codeCoverageEnabled = true

  @Shared
  boolean testsSkippingEnabled = true

  @Shared
  boolean flakyRetriesEnabled = false

  def setup() {
    receivedTraces.clear()
    receivedCoverages.clear()
  }

  def cleanup() {
    receivedTraces.clear()
    receivedCoverages.clear()
  }

  @Shared
  @AutoCleanup
  protected TestHttpServer intakeServer = httpServer {
    handlers {
      prefix("/api/v2/citestcycle") {
        def contentEncodingHeader = request.getHeader("Content-Encoding")
        def gzipEnabled = contentEncodingHeader != null && contentEncodingHeader.contains("gzip")
        def requestBody = gzipEnabled ? CiVisibilitySmokeTest.decompress(request.body) : request.body
        def decodedEvent = msgPackMapper.readValue(requestBody, Map)
        receivedTraces.add(decodedEvent)

        response.status(200).send()
      }

      prefix("/api/v2/citestcov") {
        def contentEncodingHeader = request.getHeader("Content-Encoding")
        def gzipEnabled = contentEncodingHeader != null && contentEncodingHeader.contains("gzip")
        def requestBody = gzipEnabled ? CiVisibilitySmokeTest.decompress(request.body) : request.body
        def parsed = MultipartRequestParser.parseRequest(requestBody, request.headers.get("Content-Type"))
        def coverages = parsed.get("coverage1")
        for (def coverage : coverages) {
          def decodedCoverage = msgPackMapper.readValue(coverage.get(), Map)
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
        response.status(200).addHeader("Content-Encoding", "gzip").send(CiVisibilitySmokeTest.compress(('{ "data": [{' +
          '  "id": "d230520a0561ee2f",' +
          '  "type": "test",' +
          '  "attributes": {' +
          '    "configurations": {' +
          '        "test.bundle": "Maven Smoke Tests Project maven-surefire-plugin default-test"' +
          '    },' +
          '    "name": "test_to_skip_with_itr",' +
          '    "suite": "datadog.smoke.TestSucceed"' +
          '  }' +
          '}, {' +
          '  "id": "d230520a0561ee2g",' +
          '  "type": "test",' +
          '  "attributes": {' +
          '    "configurations": {' +
          '        "test.bundle": ":test"' +
          '    },' +
          '    "name": "test_to_skip_with_itr",' +
          '    "suite": "datadog.smoke.TestSucceed"' +
          '  }' +
          '}] ' +
          '}').bytes))
      }

      prefix("/api/v2/ci/libraries/tests/flaky") {
        response.status(200).addHeader("Content-Encoding", "gzip").send(CiVisibilitySmokeTest.compress(('{ "data": [{' +
          '  "id": "d230520a0561ee2f",' +
          '  "type": "test",' +
          '  "attributes": {' +
          '    "configurations": {' +
          '        "test.bundle": "Maven Smoke Tests Project maven-surefire-plugin default-test"' +
          '    },' +
          '    "name": "test_failed",' +
          '    "suite": "datadog.smoke.TestFailed"' +
          '  }' +
          '}, {' +
          '  "id": "d230520a0561ee2g",' +
          '  "type": "test",' +
          '  "attributes": {' +
          '    "configurations": {' +
          '        "test.bundle": ":test"' +
          '    },' +
          '    "name": "test_failed",' +
          '    "suite": "datadog.smoke.TestFailed"' +
          '  }' +
          '}] ' +
          '}').bytes))
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

  protected verifyEventsAndCoverages(String projectName, String toolchain, String toolchainVersion, int expectedEventsCount, int expectedCoveragesCount) {
    def events = waitForEvents(expectedEventsCount)
    def coverages = waitForCoverages(expectedCoveragesCount)

    def additionalReplacements = ["content.meta.['test.toolchain']": "$toolchain:$toolchainVersion"]

    // uncomment to generate expected data templates
    //    def baseTemplatesPath = CiVisibilitySmokeTest.classLoader.getResource(projectName).toURI().schemeSpecificPart.replace('build/resources/test', 'src/test/resources')
    //    CiVisibilityTestUtils.generateTemplates(baseTemplatesPath, events, coverages, additionalReplacements)

    CiVisibilityTestUtils.assertData(projectName, events, coverages, additionalReplacements)
  }

  protected List<Map<String, Object>> waitForEvents(int expectedEventsSize) {
    def traceReceiveConditions = new PollingConditions(timeout: 15, initialDelay: 1, delay: 0.5, factor: 1)
    traceReceiveConditions.eventually {
      int eventsSize = 0
      for (Map<String, Object> trace : receivedTraces) {
        eventsSize += trace["events"].size()
      }
      assert eventsSize == expectedEventsSize
    }

    List<Map<String, Object>> events = new ArrayList<>()
    while (!receivedTraces.isEmpty()) {
      def trace = receivedTraces.poll()
      events.addAll((List<Map<String, Object>>) trace["events"])
    }
    return events
  }

  protected List<Map<String, Object>> waitForCoverages(int traceSize) {
    def traceReceiveConditions = new PollingConditions(timeout: 15, initialDelay: 1, delay: 0.5, factor: 1)
    traceReceiveConditions.eventually {
      assert receivedCoverages.size() == traceSize
    }

    List<Map<String, Object>> coverages = new ArrayList<>()
    while (!receivedCoverages.isEmpty()) {
      def trace = receivedCoverages.poll()
      coverages.addAll((List<Map<String, Object>>) trace["coverages"])
    }
    return coverages
  }
}
