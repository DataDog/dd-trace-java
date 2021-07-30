import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode

class AkkaHttp102ServerInstrumentationBindFlowTest extends AkkaHttpServerInstrumentationTest {
  @Override
  def setup() {
    CheckpointValidator.excludeAllValidations()
  }

  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBindFlow())
  }

  @Override
  boolean redirectHasBody() {
    return true
  }
}

class AkkaHttp102ServerInstrumentationBindTest extends AkkaHttpServerInstrumentationTest {
  @Override
  def setup() {
    CheckpointValidator.excludeAllValidations()
  }

  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBind())
  }
}

class AkkaHttp102ServerInstrumentationBindSyncTest extends AkkaHttpServerInstrumentationTest {
  @Override
  def setup() {
    CheckpointValidator.excludeAllValidations()
  }

  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBindSync())
  }
}

class AkkaHttp102ServerInstrumentationBindAsyncHttp2Test extends AkkaHttpServerInstrumentationTest {
  @Override
  def setup() {
    CheckpointValidator.excludeAllValidations()
  }

  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBindHttp2())
  }
}
