package datadog.trace.instrumentation.playws;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import javax.annotation.ParametersAreNonnullByDefault;
import play.shaded.ahc.org.asynchttpclient.Request;

@ParametersAreNonnullByDefault
public class HeadersInjectAdapter implements AgentPropagation.Setter<Request> {

  public static final HeadersInjectAdapter SETTER = new HeadersInjectAdapter();

  @Override
  public void set(final Request carrier, final String key, final String value) {
    carrier.getHeaders().add(key, value);
  }
}
