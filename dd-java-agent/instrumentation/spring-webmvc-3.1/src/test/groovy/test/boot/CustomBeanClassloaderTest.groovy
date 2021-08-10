package test.boot

import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import org.springframework.boot.SpringApplication

class CustomBeanClassloaderTest extends SpringBootBasedTest {

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SUSPEND_RESUME,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }

  @Override
  SpringApplication application() {
    return new SpringApplication(AppConfig, SecurityConfig, AuthServerConfig, CustomClassloaderConfig, TestController)
  }
}
