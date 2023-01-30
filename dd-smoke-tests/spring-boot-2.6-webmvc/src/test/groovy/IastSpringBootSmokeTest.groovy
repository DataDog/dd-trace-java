import datadog.smoketest.AbstractIastSpringBootSmokeTest
import okhttp3.Request

import java.util.function.Predicate

class IastSpringBootSmokeTest extends AbstractIastSpringBootSmokeTest{

  def "weak hash vulnerability is present on boot"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when: 'ensure the controller is loaded'
    client.newCall(request).execute()

    then: 'a vulnerability pops in the logs (startup traces might not always be available)'
    hasVulnerabilityInLogs(type('WEAK_HASH').and(evidence('SHA1')).and(withSpan()))
  }

  private boolean hasVulnerabilityInLogs(final Predicate<?> predicate) {
    def found = false
    checkLog { final String log ->
      final index = log.indexOf(TAG_NAME)
      if (index >= 0) {
        final vulnerabilities = parseVulnerabilities(log, index)
        found |= vulnerabilities.stream().anyMatch(predicate)
      }
    }
    return found
  }
}
