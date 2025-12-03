package datadog.trace.instrumentation.liberty23

import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import spock.lang.IgnoreIf

@IgnoreIf({
  System.getProperty('java.vm.name') == 'IBM J9 VM' &&
  System.getProperty('java.specification.version') == '1.8'
})
class Liberty23InactiveAppSecTest extends AppSecInactiveHttpServerTest {
  HttpServer server() {
    new Liberty23Server()
  }
}
