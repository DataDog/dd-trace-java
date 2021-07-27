import datadog.trace.agent.test.base.HttpServer

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
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

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class AkkaHttp102ServerInstrumentationBindTest extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBind())
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class AkkaHttp102ServerInstrumentationBindSyncTest extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBindSync())
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class AkkaHttp102ServerInstrumentationBindAsyncHttp2Test extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttp102TestWebServer.ServerBuilderBindHttp2())
  }
}
