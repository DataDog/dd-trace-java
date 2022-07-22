import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.instrumentation.spray.SprayHttpServerDecorator

class SprayServerTest extends HttpServerTest<SprayHttpTestWebServer> {

  @Override
  HttpServer server() {
    SprayHttpTestWebServer server = new SprayHttpTestWebServer()
    server.start()
    return server
  }

  @Override
  void stopServer(SprayHttpTestWebServer sprayHttpTestWebServer) {
    sprayHttpTestWebServer.stop()
  }

  @Override
  String component() {
    return SprayHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return SprayHttpServerDecorator.DECORATE.SPRAY_HTTP_REQUEST
  }

  @Override
  boolean testExceptionBody() {
    // Todo: Response{protocol=http/1.1, code=500, message=Internal Server Error, url=...}
    false
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean hasPeerInformation() {
    return false
  }

  @Override
  boolean hasForwardedIP() {
    return true
  }

  @Override
  boolean hasPlusEncodedSpaces() {
    true
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS)
  }
}
