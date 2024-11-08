package datadog.trace.instrumentation.apachehttpcore5

import datadog.trace.agent.test.AgentTestRunner
import org.apache.hc.core5.http.message.BasicHttpRequest

class HttpRequestInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test constructor'(){
    given:

    when:
    BasicHttpRequest.newInstance(*args)

    then:
    0 * _

    where:
    args | _
    ["GET", 'http://localhost.com'] | _
  }
}
