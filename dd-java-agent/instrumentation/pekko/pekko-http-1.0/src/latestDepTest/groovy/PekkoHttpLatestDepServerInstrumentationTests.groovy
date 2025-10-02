import datadog.trace.agent.test.base.HttpServer

class PekkoHttpLatestDepServerInstrumentationBindFlowTest extends PekkoHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new PekkoHttpTestWebServer(PekkoHttpLatestDepTestWebServer.ServerBuilderBindFlow())
  }

  @Override
  boolean redirectHasBody() {
    return true
  }
}

class PekkoHttpLatestDepServerInstrumentationBindTest extends PekkoHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new PekkoHttpTestWebServer(PekkoHttpLatestDepTestWebServer.ServerBuilderBind())
  }
}

class PekkoHttpLatestDepServerInstrumentationBindSyncTest extends PekkoHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new PekkoHttpTestWebServer(PekkoHttpLatestDepTestWebServer.ServerBuilderBindSync())
  }
}

class PekkoHttpLatestDepServerInstrumentationBindAsyncHttp2Test extends PekkoHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new PekkoHttpTestWebServer(PekkoHttpLatestDepTestWebServer.ServerBuilderBindHttp2())
  }
}
