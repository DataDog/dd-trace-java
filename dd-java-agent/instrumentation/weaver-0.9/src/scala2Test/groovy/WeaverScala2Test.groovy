import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.weaver.DatadogWeaverReporter
import datadog.trace.instrumentation.weaver.WeaverUtils
import org.example.TestAutomationExcluded
import org.example.TestAutomationDuplicateNames
import org.example.TestAutomationDuplicatePureNames
import org.example.TestAutomationNamed
import org.example.TestAutomationProvider
import org.slf4j.LoggerFactory
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef
import weaver.framework.CatsEffect

@DisableTestTrace(reason = 'avoid self-tracing')
class WeaverScala2Test extends CiVisibilityInstrumentationTest {
  def "Scala 2 declarations preserve exclusion tags and native failure and skip outcomes"() {
    when:
    runNativeSuites([TestAutomationExcluded.name])

    then:
    def spans = TEST_WRITER.toList().flatten().findAll { it.getTag('test.name') in ['protected_failure', 'protected_skip'] }
    spans.size() == 2
    spans.every { it.getTag('test.test_management.no_auto_retry') == true }
    spans.every { it.getTag('test.test_management.prevent_failure_suppression') == true }
    spans.find { it.getTag('test.name') == 'protected_failure' }.getTag('test.status').toString() == 'fail'
    spans.find { it.getTag('test.name') == 'protected_skip' }.getTag('test.status').toString() == 'skip'
  }

  def "Scala 2 duplicate names retain each declaration's annotations and native selection in #testClass.simpleName"() {
    when:
    runNativeSuites([testClass.name])

    then:
    def spans = TEST_WRITER.toList().flatten().findAll { it.getTag("test.suite") == testClass.name && it.getTag("test.name") != null }
    spans.size() == expectedExecutions
    def ignoredCollision = spans.find { it.getTag("test.name") == "ignored_collision" }
    ignoredCollision.getTag("test.status").toString() == "pass"
    ignoredCollision.getTag("test.test_management.no_auto_retry") == null
    ignoredCollision.getTag("test.test_management.prevent_failure_suppression") == null
    def duplicates = spans.findAll { it.getTag("test.name") == "runnable_collision" }
    duplicates.size() == expectedExecutions - 3
    def failed = duplicates.find { it.getTag("test.status").toString() == "fail" }
    failed.getTag("test.test_management.no_auto_retry") == true
    failed.getTag("test.test_management.prevent_failure_suppression") == null
    def passed = duplicates.find { it.getTag("test.status").toString() == "pass" }
    passed.getTag("test.test_management.no_auto_retry") == null
    passed.getTag("test.test_management.prevent_failure_suppression") == true
    def skipped = duplicates.findAll { it.getTag("test.status").toString() == "skip" }
    skipped.size() == expectedExecutions - 5
    skipped.every { it.getTag("test.test_management.no_auto_retry") == null }
    skipped.every { it.getTag("test.test_management.prevent_failure_suppression") == null }
    def copied = spans.findAll { it.getTag("test.name") == "copy_removed" }
    copied.size() == 2
    def annotatedCopy = copied.find { it.getTag("test.status").toString() == "fail" }
    annotatedCopy.getTag("test.test_management.no_auto_retry") == true
    annotatedCopy.getTag("test.test_management.prevent_failure_suppression") == null
    def ordinaryCopy = copied.find { it.getTag("test.status").toString() == "pass" }
    ordinaryCopy.getTag("test.test_management.no_auto_retry") == null
    ordinaryCopy.getTag("test.test_management.prevent_failure_suppression") == null
    testClass.executionCount() == expectedExecutions
    testClass.ignoredExecutionCount() == 0

    where:
    testClass                        | expectedExecutions
    TestAutomationDuplicateNames     | 6
    TestAutomationDuplicatePureNames | 5
  }

  def "Scala 2 task identities preserve distinct provider declarations"() {
    when:
    runNativeSuites([TestAutomationNamed.name, TestAutomationProvider.name])

    then:
    def spans = TEST_WRITER.toList().flatten().findAll { it.getTag('test.name') == 'named_test' }
    spans.size() == 2
    def named = spans.find { it.getTag('test.suite') == TestAutomationNamed.name }
    named.getTag('test.test_management.no_auto_retry') == true
    named.getTag('test.test_management.prevent_failure_suppression') == null
    def provided = spans.find { it.getTag('test.suite') == TestAutomationProvider.name }
    provided.getTag('test.test_management.prevent_failure_suppression') == true
    provided.getTag('test.test_management.no_auto_retry') == null
  }

  private void runNativeSuites(List<String> names) {
    assert CatsEffect.protectionDomain.codeSource.location.toString().contains('weaver-cats_2.')
    def output = new ByteArrayOutputStream()
    def stream = new PrintStream(output, false, 'UTF-8')
    def framework = new CatsEffect(stream)
    def runner = framework.runner([] as String[], [] as String[], getClass().classLoader)
    def fingerprint = framework.fingerprints().find { it instanceof SubclassFingerprint && it.isModule() && it.superclassName() == 'weaver.BaseCatsSuite' }
    def definitions = names.collect { new TaskDef(it, fingerprint, false, [new SuiteSelector()] as sbt.testing.Selector[]) } as TaskDef[]
    def events = []
    def handler = { event -> events.add(event) } as EventHandler
    def log = LoggerFactory.getLogger(WeaverScala2Test)
    def logger = [
      ansiCodesSupported: { false },
      error: { String message -> log.error(message) },
      warn: { String message -> log.warn(message) },
      info: { String message -> log.info(message) },
      debug: { String message -> log.debug(message) },
      trace: { Throwable error ->
        log.error('Weaver error', error)
      }
    ] as Logger
    DatadogWeaverReporter.start()
    try {
      runner.tasks(definitions).each { it.execute(handler, [logger] as Logger[]) }
      assert !events.empty
    } finally {
      runner.done()
      stream.close()
      DatadogWeaverReporter.stop()
    }
  }

  @Override
  String instrumentedLibraryName() {
    'weaver'
  }

  @Override
  String instrumentedLibraryVersion() {
    WeaverUtils.weaverVersion
  }
}
