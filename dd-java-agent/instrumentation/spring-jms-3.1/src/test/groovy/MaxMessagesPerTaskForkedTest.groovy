import datadog.trace.api.config.TracerConfig
import salistener.MaxMessagesPerTaskConfig

class MaxMessagesPerTaskForkedTest extends SpringSAListenerTest {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    // force test failure if we fall back to the iteration span cleaner
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "600")
  }

  @Override
  def configClass() {
    return MaxMessagesPerTaskConfig
  }
}
