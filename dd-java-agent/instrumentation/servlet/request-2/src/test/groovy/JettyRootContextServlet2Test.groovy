import datadog.trace.agent.test.base.HttpServer

class JettyRootContextServlet2Test extends JettyServlet2Test {
  @Override
  HttpServer server() {
    new JettyServer("")
  }

  @Override
  String expectedServiceName() {
    "root-context"
  }
}
