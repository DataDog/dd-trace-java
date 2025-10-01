package datadog.trace.instrumentation.okhttp2;

import com.squareup.okhttp.Request;
import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class RequestBuilderInjectAdapter implements CarrierSetter<Request.Builder> {
  public static final RequestBuilderInjectAdapter SETTER = new RequestBuilderInjectAdapter();

  @Override
  public void set(final Request.Builder carrier, final String key, final String value) {
    carrier.header(key, value);
  }
}
