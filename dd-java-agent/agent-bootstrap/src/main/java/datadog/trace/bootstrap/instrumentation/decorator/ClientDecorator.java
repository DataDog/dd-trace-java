package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.api.naming.PeerServiceFormulas;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.TagsFormula;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public abstract class ClientDecorator extends BaseDecorator {

  protected abstract String service();

  protected String spanKind() {
    return Tags.SPAN_KIND_CLIENT;
  }

  @Override
  protected SpanPrototype buildSpanPrototype() {
    // Extend the base prototype with the client-level span.kind. spanKind() is overridable and may
    // read a not-yet-initialized static during singleton construction -- building lazily (via
    // spanPrototype()) preserves the ordering safety the old cached spanKindEntry provided.
    SpanPrototype.Builder builder =
        SpanPrototype.builder().extends_(super.buildSpanPrototype()).initKind(spanKind());
    // Override the base prototype's peer-service formula with one resolved for this component, so
    // spans skip the per-span component lookup. Applies only when derived tags are on.
    final CharSequence component = component();
    if (component != null) {
      TagsFormula peerService =
          SpanNaming.instance()
              .namingSchema()
              .peerService()
              .formula(component.toString(), Config.get().getPeerServiceMapping());
      if (peerService != null) {
        builder.initDerivedTags(PeerServiceFormulas.TARGETS, peerService);
      }
    }
    return builder.build();
  }

  @Override
  protected void doAfterStart(final AgentSpan span) {
    final String service = service();
    if (service != null) {
      span.setServiceName(service, component());
    }

    // Generate metrics for all client spans.
    span.setMeasured(true);
    super.doAfterStart(span);
  }
}
