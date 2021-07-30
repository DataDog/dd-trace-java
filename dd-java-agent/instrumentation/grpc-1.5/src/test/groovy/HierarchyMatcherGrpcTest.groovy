import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode

class HierarchyMatcherGrpcTest extends GrpcTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.grpc.matching.shortcut.enabled", "false")
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SEQUENCE)
  }
}
