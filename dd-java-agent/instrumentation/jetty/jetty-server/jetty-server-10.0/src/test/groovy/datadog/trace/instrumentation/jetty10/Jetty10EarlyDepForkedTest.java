package datadog.trace.instrumentation.jetty10;

import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Exercises jetty-appsec-9.4's "early_10_series" muzzle pass ([10.0.0, 10.0.10)) where {@code
 * _multiParts} is {@code MultiPartFormInputStream} instead of {@code MultiParts}. The OR-muzzle
 * reference in jetty-appsec-9.4 covers both field types; this suite verifies the advice actually
 * applies and fires AppSec events on the older field type.
 *
 * <p>Guarded by the {@code test.dd.earlyJetty10} system property so these classes only run in the
 * {@code earlyDep10ForkedTest} Gradle task (Jetty 10.0.9).
 */
@EnabledIfSystemProperty(named = "test.dd.earlyJetty10", matches = ".+")
class Jetty10EarlyDepV0ForkedTest extends Jetty10Test
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

@EnabledIfSystemProperty(named = "test.dd.earlyJetty10", matches = ".+")
class Jetty10EarlyDepV1ForkedTest extends Jetty10Test
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

  @Override
  protected boolean useWebsocketPojoEndpoint() {
    return false;
  }
}
