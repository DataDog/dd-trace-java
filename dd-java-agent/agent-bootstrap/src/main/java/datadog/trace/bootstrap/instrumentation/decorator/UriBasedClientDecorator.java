package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.net.URI;
import javax.annotation.Nonnull;

public abstract class UriBasedClientDecorator extends ClientDecorator {
  public void onURI(@Nonnull final AgentSpan span, @Nonnull final URI uri) {
    String host = uri.getHost();
    int port = uri.getPort();
    if (null != host && !host.isEmpty()) {
      span.setTag(Tags.PEER_HOSTNAME, host);
      if (Config.get().isHttpClientSplitByDomain() && host.charAt(0) >= 'A') {
        span.setServiceName(host, component());
      }
      if (port > 0) {
        setPeerPort(span, port);
      }
    }
  }

  public <C> void injectContext(Context context, final C request, CarrierSetter<C> setter) {
    // Add additional default DSM context for HTTP clients if missing but DSM is enabled
    if (DataStreamsContext.fromContext(context) == null && traceConfig().isDataStreamsEnabled()) {
      context = context.with(DataStreamsContext.forHttpClient());
    }
    // Inject context into carrier
    defaultPropagator().inject(context, request, setter);
  }
}
