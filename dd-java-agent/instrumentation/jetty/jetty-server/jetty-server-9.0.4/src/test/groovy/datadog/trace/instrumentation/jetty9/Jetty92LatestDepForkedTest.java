package datadog.trace.instrumentation.jetty9;

import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for multipart filename extraction on Jetty 9.2.x.
 *
 * <p>Jetty 9.2 introduces Servlet 3.1 and {@link javax.servlet.http.Part#getSubmittedFileName()},
 * which is used by the {@code jetty-appsec-9.2} instrumentation to report filenames to the WAF.
 *
 * <p>Only activated for the {@code latestDepForkedTest} Gradle task (Jetty 9.2.x). The {@code
 * test.dd.jetty92} system property gates execution, preventing these tests from running against
 * Jetty 9.0.4 where {@code getSubmittedFileName()} does not exist.
 */
abstract class Jetty92LatestDepForkedTest extends Jetty9Test {

  @Override
  public boolean testBodyMultipart() {
    return true;
  }

  @Override
  public boolean testBodyFilenames() {
    return true;
  }

  @Override
  public boolean testBodyFilenamesCalledOnce() {
    return true;
  }

  @Override
  public boolean testBodyFilenamesCalledOnceCombined() {
    return true;
  }

  @Override
  public boolean testBodyFilesContent() {
    return true;
  }
}

@EnabledIfSystemProperty(named = "test.dd.jetty92", matches = ".+")
class Jetty92V0LatestDepForkedTest extends Jetty92LatestDepForkedTest
    implements TestingGenericHttpNamingConventions.ServerV0 {

  @Override
  public int version() {
    return 0;
  }

  @Override
  public String service() {
    return null;
  }

  @Override
  public String operation() {
    return "servlet.request";
  }
}

@EnabledIfSystemProperty(named = "test.dd.jetty92", matches = ".+")
class Jetty92V1LatestDepForkedTest extends Jetty92LatestDepForkedTest
    implements TestingGenericHttpNamingConventions.ServerV1 {

  @Override
  public int version() {
    return 1;
  }

  @Override
  public String service() {
    return null;
  }

  @Override
  public String operation() {
    return "http.server.request";
  }
}
