package datadog.trace.instrumentation.okhttp3;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import javax.annotation.ParametersAreNonnullByDefault;
import okhttp3.Request;

/**
 * Helper class to inject span context into request headers.
 *
 * @author Pavol Loffay
 */
@ParametersAreNonnullByDefault
public class RequestBuilderInjectAdapter implements AgentPropagation.Setter<Request.Builder> {

  public static final RequestBuilderInjectAdapter SETTER = new RequestBuilderInjectAdapter();

  @Override
  public void set(final Request.Builder carrier, final String key, final String value) {
    carrier.header(key, value);
  }
}
