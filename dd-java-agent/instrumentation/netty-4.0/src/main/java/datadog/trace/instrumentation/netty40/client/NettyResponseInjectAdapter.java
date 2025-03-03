package datadog.trace.instrumentation.netty40.client;

import datadog.context.propagation.CarrierSetter;
import io.netty.handler.codec.http.HttpHeaders;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class NettyResponseInjectAdapter implements CarrierSetter<HttpHeaders> {

  public static final NettyResponseInjectAdapter SETTER = new NettyResponseInjectAdapter();

  @Override
  public void set(final HttpHeaders headers, final String key, final String value) {
    headers.set(key, value);
  }
}
