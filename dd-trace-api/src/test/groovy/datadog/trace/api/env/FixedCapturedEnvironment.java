package datadog.trace.api.env;

import java.util.Collections;
import java.util.Map;
import org.junit.rules.ExternalResource;

/**
 * The {@code FixedCapturedEnvironment} rule cleans the {@code CapturedEnvironment} instance when
 * the test starts. Additionally, this rule can be used to set fixed properties into that {@code
 * CapturedEnvironment} instance. This is useful to be deterministic in those tests that are testing
 * logic which interacts with {@code CapturedEnvironment} instance, because that object returns
 * properties which are platform dependant (JDK, OS, etc...)
 */
public class FixedCapturedEnvironment extends ExternalResource {

  @Override
  protected void before() throws Throwable {
    // Clean CapturedEnvironment instance when test starts.
    CapturedEnvironment.useFixedEnv(Collections.<String, String>emptyMap());
  }

  /** Load properties instance into the {@code CapturedEnvironment} instance. */
  public void load(final Map<String, String> properties) {
    CapturedEnvironment.useFixedEnv(properties);
  }
}
