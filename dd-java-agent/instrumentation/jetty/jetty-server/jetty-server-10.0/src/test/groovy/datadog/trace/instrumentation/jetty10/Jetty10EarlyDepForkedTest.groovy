package datadog.trace.instrumentation.jetty10

import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import spock.lang.Requires

/**
 * Exercises jetty-appsec-9.4's "early_10_series" muzzle pass ([10.0.0, 10.0.10)) where
 * {@code _multiParts} is {@code MultiPartFormInputStream} instead of {@code MultiParts}.
 * The OR-muzzle reference in jetty-appsec-9.4 covers both field types; this suite
 * verifies the advice actually applies and fires AppSec events on the older field type.
 *
 * <p>Guarded by the {@code test.dd.earlyJetty10} system property so these classes
 * only run in the {@code earlyDep10ForkedTest} Gradle task (Jetty 10.0.9).
 */
@Requires({
  System.getProperty('test.dd.earlyJetty10')
})
class Jetty10EarlyDepV0ForkedTest extends Jetty10Test implements TestingGenericHttpNamingConventions.ServerV0 {
}

@Requires({
  System.getProperty('test.dd.earlyJetty10')
})
class Jetty10EarlyDepV1ForkedTest extends Jetty10Test implements TestingGenericHttpNamingConventions.ServerV1 {
  @Override
  protected boolean useWebsocketPojoEndpoint() {
    false
  }
}
