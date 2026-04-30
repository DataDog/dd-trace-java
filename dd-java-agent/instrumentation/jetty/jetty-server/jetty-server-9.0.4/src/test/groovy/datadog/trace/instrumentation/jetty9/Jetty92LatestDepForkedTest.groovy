package datadog.trace.instrumentation.jetty9

import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import spock.lang.Requires

/**
 * Integration tests for multipart filename extraction on Jetty 9.2.x.
 *
 * <p>Jetty 9.2 introduces Servlet 3.1 and {@link javax.servlet.http.Part#getSubmittedFileName()},
 * which is used by the {@code jetty-appsec-9.2} instrumentation to report filenames to the WAF.
 *
 * <p>Only activated for the {@code latestDepForkedTest} Gradle task (Jetty 9.2.x). The
 * {@code test.dd.jetty92} system property gates execution, preventing these tests from running
 * against Jetty 9.0.4 where {@code getSubmittedFileName()} does not exist.
 */
abstract class Jetty92LatestDepForkedTest extends Jetty9Test {

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyFilenames() {
    true
  }

  @Override
  boolean testBodyFilenamesCalledOnce() {
    true
  }

  @Override
  boolean testBodyFilenamesCalledOnceCombined() {
    true
  }
}

@Requires({
  System.getProperty('test.dd.jetty92')
})
class Jetty92V0LatestDepForkedTest extends Jetty92LatestDepForkedTest
implements TestingGenericHttpNamingConventions.ServerV0 {
}

@Requires({
  System.getProperty('test.dd.jetty92')
})
class Jetty92V1LatestDepForkedTest extends Jetty92LatestDepForkedTest
implements TestingGenericHttpNamingConventions.ServerV1 {
}
