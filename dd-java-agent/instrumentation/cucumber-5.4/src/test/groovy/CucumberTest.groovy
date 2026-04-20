import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.cucumber.junit.platform.engine.CucumberTestEngine
import org.example.TestSucceedCucumber
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.suite.engine.SuiteTestEngine

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

class CucumberTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_ENABLED, "true")
    // we are running this test with only Cucumber instrumentation:
    // underlying test framework (JUnit 5) is not instrumented,
    // therefore there is no test span that could be the parent of cucumber step spans,
    // and if we do not disable trace sanitation, our traces will be dropped
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_TRACE_SANITATION_ENABLED, "false")
  }

  def "test successful scenario execution generates spans"() {
    setup:
    runTestClasses(TestSucceedCucumber)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_START, {
      trace(1) {
        cucumberStepSpan(it, 0,
          "a calculator I just turned on",
          "org.example.cucumber.calculator.CalculatorSteps.a_calculator_I_just_turned_on()",
          "io.cucumber.java.JavaStepDefinition")
      }
      trace(1) {
        cucumberStepSpan(it, 0,
          "I add {int} and {int}",
          "org.example.cucumber.calculator.CalculatorSteps.adding(int,int)",
          "io.cucumber.java.JavaStepDefinition",
          "[4, 5]")
      }
      trace(1) {
        cucumberStepSpan(it, 0,
          "the result is {int}",
          "org.example.cucumber.calculator.CalculatorSteps.the_result_is(double)",
          "io.cucumber.java.JavaStepDefinition",
          "[9]")
      }
    })
  }

  private static void runTestClasses(Class<?>... classes) {
    DiscoverySelector[] selectors = new DiscoverySelector[classes.length]
    for (i in 0..<classes.length) {
      selectors[i] = selectClass(classes[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcherConfig = LauncherConfig
      .builder()
      .enableTestEngineAutoRegistration(false)
      .addTestEngines(new SuiteTestEngine(), new CucumberTestEngine())
      .build()

    def launcher = LauncherFactory.create(launcherConfig)
    launcher.execute(launcherReq)
  }

  private static void cucumberStepSpan(TraceAssert trace,
    int index,
    String stepName,
    String location,
    String type,
    String arguments = null) {
    trace.span(index, {
      parent()
      operationName "cucumber.step"
      resourceName stepName
      tags {
        "$Tags.COMPONENT" "cucumber"
        "step.name" stepName
        "step.location" location
        "step.type" type

        if (arguments != null) {
          "step.arguments" arguments
        }

        defaultTags()
      }
    })
  }
}
