import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.hystrix.HystrixDecorator

abstract class HystrixTestRunner extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    // Disable so failure testing below doesn't inadvertently change the behavior.
    System.setProperty("hystrix.command.default.circuitBreaker.enabled", "false")

    // hack to guarantee that that extra tags is enabled since it can't be guaranteed that
    // the Config singleton will not be initialised before this block runs.
    HystrixDecorator.DECORATE = new HystrixDecorator(true, true)

    // Uncomment for debugging:
    // System.setProperty("hystrix.command.default.execution.timeout.enabled", "false")
  }
}
