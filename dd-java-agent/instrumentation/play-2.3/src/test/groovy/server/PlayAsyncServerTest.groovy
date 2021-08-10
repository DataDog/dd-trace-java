package server

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode

class PlayAsyncServerTest extends PlayServerTest {

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SUSPEND_RESUME,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }

  @Override
  HttpServer server() {
    return new AsyncServer()
  }
}
