package datadog.trace.instrumentation.jaxrs;

import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;

public class ClientTracingFeature implements Feature {
  @Override
  public boolean configure(final FeatureContext context) {
    context.register(new ClientTracingFilter());
    return true;
  }
}
