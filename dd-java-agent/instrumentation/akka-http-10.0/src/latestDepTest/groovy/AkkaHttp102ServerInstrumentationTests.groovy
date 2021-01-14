class AkkaHttp102ServerInstrumentationBindFlowTest extends AkkaHttpServerInstrumentationTest {
  @Override
  AkkaHttpTestWebServer startServer(int port) {
    return new AkkaHttpTestWebServer(port, AkkaHttp102TestWebServer.ServerBuilderBindFlow())
  }

  @Override
  boolean redirectHasBody() {
    return true
  }
}

class AkkaHttp102ServerInstrumentationBindTest extends AkkaHttpServerInstrumentationTest {
  @Override
  AkkaHttpTestWebServer startServer(int port) {
    return new AkkaHttpTestWebServer(port, AkkaHttp102TestWebServer.ServerBuilderBind())
  }
}

class AkkaHttp102ServerInstrumentationBindSyncTest extends AkkaHttpServerInstrumentationTest {
  @Override
  AkkaHttpTestWebServer startServer(int port) {
    return new AkkaHttpTestWebServer(port, AkkaHttp102TestWebServer.ServerBuilderBindSync())
  }
}

