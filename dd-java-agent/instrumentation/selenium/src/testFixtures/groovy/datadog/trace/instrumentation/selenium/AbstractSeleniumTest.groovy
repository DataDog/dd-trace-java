package datadog.trace.instrumentation.selenium

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.communication.util.IOUtils

import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.ConcurrentLinkedQueue

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

abstract class AbstractSeleniumTest extends CiVisibilityInstrumentationTest {

  private static final String DUMMY_PAGE_PATH = "/dummy-page.html"
  private static final String RUM_DATA_PATH = "/upload-rum-data"

  @Shared
  protected ObjectMapper jsonMapper = new ObjectMapper()

  @AutoCleanup
  protected TestHttpServer intakeServer = httpServer {
    handlers {
      get(DUMMY_PAGE_PATH) {
        def pageBody = IOUtils.readFully(AbstractSeleniumTest.getResourceAsStream("/dummy-page.html")).replace("{{RUM_DATA_VERIFICATION_URL_PLACEHOLDER}}", address.toString() + RUM_DATA_PATH)
        response.status(200).send(pageBody)
      }

      post(RUM_DATA_PATH) {
        def req = jsonMapper.readerFor(Map).readValue(request.body)
        rumData.offer(req)
      }
    }
  }

  protected Queue<Map<String, String>> rumData = new ConcurrentLinkedQueue<>()

  def setup() {
    System.setProperty("selenium-test.dummy-page-url", intakeServer.address.toString() + DUMMY_PAGE_PATH)
    rumData.clear()
  }

  protected void runTests(List<Class<?>> tests) {
    DiscoverySelector[] selectors = new DiscoverySelector[tests.size()]
    for (i in 0..<tests.size()) {
      selectors[i] = selectClass(tests[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcherConfig = LauncherConfig
      .builder()
      .enableTestEngineAutoRegistration(false)
      .addTestEngines(new JupiterTestEngine())
      .build()

    def launcher = LauncherFactory.create(launcherConfig)
    try {
      launcher.execute(launcherReq)
    } catch (Throwable ignored) {
    }

    TestEventsHandlerHolder.stop()
  }

  protected void assertRumData(int expectedTestCasesCount, Map<String, String> dynamicData) {
    // verify that test execution ID injected into RUM is the same as trace ID received over test data intake
    List<String> expectedTraceIds = []
    int testCaseIdx = 0
    while (++testCaseIdx <= expectedTestCasesCount) {
      def suffix = (testCaseIdx > 1) ? "_$testCaseIdx" : ""
      expectedTraceIds += String.valueOf(dynamicData["content_trace_id$suffix"])
    }
    expectedTraceIds.sort()

    List<String> rumTraceIds = []
    while (!rumData.empty) {
      rumTraceIds += rumData.poll()["test_execution_id"]
    }
    rumTraceIds.sort()

    assertEquals(expectedTraceIds, rumTraceIds)
  }

  @Override
  String instrumentedLibraryName() {
    return "junit5"
  }

  @Override
  String instrumentedLibraryVersion() {
    return JupiterTestEngine.getPackage().getImplementationVersion()
  }
}
