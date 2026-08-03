package datadog.trace.instrumentation.junit5;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import org.junit.platform.engine.ConfigurationParameters;

/**
 * NO-OP {@link ConfigurationParameters}, used when reconstructing a Cucumber retry descriptor for
 * engine versions (6.0–7.23) whose {@code PickleDescriptor} constructor consumes the configuration
 * to compute exclusive resources but does not store it (so it cannot be read back).
 */
@SuppressWarnings("deprecation") // ConfigurationParameters#size() is deprecated in newer platforms
public final class EmptyConfigurationParameters implements ConfigurationParameters {

  @Override
  public Optional<String> get(String key) {
    return Optional.empty();
  }

  @Override
  public Optional<Boolean> getBoolean(String key) {
    return Optional.empty();
  }

  @Override
  public int size() {
    return 0;
  }

  public Set<String> keySet() {
    return Collections.emptySet();
  }
}
