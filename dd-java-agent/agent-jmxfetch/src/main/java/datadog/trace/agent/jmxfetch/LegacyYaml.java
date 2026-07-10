package datadog.trace.agent.jmxfetch;

import java.io.InputStream;

/**
 * We map the legacy {@code org.yaml.snakeyaml.Yaml} class to this substitute class at build time to
 * keep the GraalVM native-image builder happy. JmxFetch has a solitary reference to the class, but
 * it's never called because JMXFetch discovers the embedded snakeyaml-engine library and uses that.
 */
public class LegacyYaml {
  public <T> T load(InputStream in) {
    throw new UnsupportedOperationException();
  }

  public String dump(Object data) {
    throw new UnsupportedOperationException();
  }
}
