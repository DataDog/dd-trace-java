import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.scalatest.ScalatestUtils
import org.example.TestIgnoredAsync
import org.example.TestSucceed

class ScalatestForkMonitorForkedTest extends CiVisibilityInstrumentationTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED, "true")
  }

  def "test fork monitor keeps reporting tests run in the sbt JVM"() {
    given:
    givenFlakyRetryEnabled(true)

    when:
    ScalatestTest.runTestsWithSbtFramework([TestIgnoredAsync, TestSucceed], true)

    then:
    assertSpansData("test-sbt-async-ignored-then-succeed")
  }

  @Override
  String instrumentedLibraryName() {
    return "scalatest"
  }

  @Override
  String instrumentedLibraryVersion() {
    return ScalatestUtils.scalatestVersion
  }
}
