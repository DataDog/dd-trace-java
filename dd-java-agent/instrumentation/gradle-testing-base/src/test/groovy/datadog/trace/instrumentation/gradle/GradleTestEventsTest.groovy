package datadog.trace.instrumentation.gradle

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.civisibility.TestEventsBridge
import org.gradle.api.internal.tasks.testing.DefaultTestSuiteDescriptor
import org.gradle.api.internal.tasks.testing.TestCompleteEvent
import org.gradle.api.internal.tasks.testing.TestResultProcessor
import org.gradle.api.internal.tasks.testing.TestStartEvent
import org.gradle.api.internal.tasks.testing.processors.CaptureTestOutputTestResultProcessor
import org.gradle.api.internal.tasks.testing.processors.StandardOutputRedirector

class GradleTestEventsTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.civisibility.enabled", "true")
  }

  def "test listener is invoked"() {
    setup:
    TestResultProcessor resultProcessor = Stub(TestResultProcessor)
    StandardOutputRedirector testOutputRedirector = Stub(StandardOutputRedirector)

    CaptureTestOutputTestResultProcessor processor = new CaptureTestOutputTestResultProcessor(
      resultProcessor, testOutputRedirector)

    TestEventsBridge.TestEventsListener testEventsListener = Mock()
    TestEventsBridge.addListener(testEventsListener)

    when:
    def rootDescriptor = new DefaultTestSuiteDescriptor("rootId", "name")
    processor.started(rootDescriptor, new TestStartEvent(System.currentTimeMillis()))

    def classDescriptor = new DefaultTestSuiteDescriptor("classId", "className")
    processor.started(classDescriptor, new TestStartEvent(System.currentTimeMillis()))

    def testCaseDescriptor = new DefaultTestSuiteDescriptor("testCaseId", "testCaseName")
    processor.started(testCaseDescriptor, new TestStartEvent(System.currentTimeMillis()))

    processor.completed("testCaseId", new TestCompleteEvent(System.currentTimeMillis()))

    processor.completed("classId", new TestCompleteEvent(System.currentTimeMillis()))

    processor.completed("rootId", new TestCompleteEvent(System.currentTimeMillis()))

    then:
    1 * testEventsListener.onTestModuleStarted()
    1 * testEventsListener.onTestModuleFinished()
    0 * _
  }
}
