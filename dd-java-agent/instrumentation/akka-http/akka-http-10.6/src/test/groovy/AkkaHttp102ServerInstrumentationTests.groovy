import datadog.trace.agent.test.base.HttpServer

class AkkaHttp102ServerInstrumentationBindFlowTest extends AkkaHttpServerInstrumentationTest {
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
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBind())
  }
}

class AkkaHttp102ServerInstrumentationBindSyncTest extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBindSync())
  }

  // we test body endpoints only on the async tests
  @Override
  boolean testRequestBody() {
    false
  }

  @Override
  boolean testBodyMultipart() {
    false
  }

  @Override
  boolean testBodyJson() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    false
  }
}

class AkkaHttp102ServerInstrumentationBindAsyncHttp2Test extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBindHttp2())
  }
}
