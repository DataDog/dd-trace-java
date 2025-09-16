package datadog.trace.instrumentation.netty38.client;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import org.jboss.netty.handler.codec.http.HttpHeaders;

@ParametersAreNonnullByDefault
public class NettyResponseInjectAdapter implements CarrierSetter<HttpHeaders> {

  public static final NettyResponseInjectAdapter SETTER = new NettyResponseInjectAdapter();

  @Override
  public void set(final HttpHeaders headers, final String key, final String value) {
    headers.set(key, value);
  }
}
