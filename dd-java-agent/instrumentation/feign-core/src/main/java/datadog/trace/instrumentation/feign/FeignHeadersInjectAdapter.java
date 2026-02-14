package datadog.trace.instrumentation.feign;

import datadog.context.propagation.CarrierSetter;
import feign.Request;
import java.util.Collections;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class FeignHeadersInjectAdapter implements CarrierSetter<Request> {

  public static final FeignHeadersInjectAdapter SETTER = new FeignHeadersInjectAdapter();

  @Override
  public void set(final Request carrier, final String key, final String value) {
    carrier.headers().put(key, Collections.singletonList(value));
  }
}
