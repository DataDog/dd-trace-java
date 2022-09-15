package datadog.trace.instrumentation.csi.sample

import com.test.TestSuite
import datadog.trace.agent.test.AgentTestRunner
import spock.lang.Shared

import java.security.MessageDigest

class SampleCallSiteTest extends AgentTestRunner {

  @Shared
  private SampleCallSite.Handler defaultHandler

  def setup() {
    defaultHandler = SampleCallSite.HANDLER
  }

  def cleanup() {
    SampleCallSite.HANDLER = defaultHandler
  }

  def 'test around call site'() {
    setup:
    SampleCallSite.HANDLER = Mock(SampleCallSite.Handler)

    when:
    final digest = TestSuite.messageDigestGetInstance('MD5')

    then:
    digest.algorithm == 'SHA1'
    1 * SampleCallSite.HANDLER.aroundMessageDigestGetInstance('MD5') >> { args ->
      return MessageDigest.getInstance('SHA1')
    }
  }

  def 'test after call site'() {
    setup:
    SampleCallSite.HANDLER = Mock(SampleCallSite.Handler)

    when:
    final url = TestSuite.urlInit('https://google.com')
    url.toString() == 'ftp://google.com'

    then:
    1 * SampleCallSite.HANDLER.afterURLInit('https://google.com', _ as URL) >> { String value, URL result ->
      return new URL('ftp', result.getHost(), '')
    }
  }

  def 'test before call site'() {
    setup:
    SampleCallSite.HANDLER = Mock(SampleCallSite.Handler)

    when:
    TestSuite.stringConcat('Hello ', 'World!')

    then:
    1 * SampleCallSite.HANDLER.beforeStringConcat('Hello ', 'World!')
  }
}
