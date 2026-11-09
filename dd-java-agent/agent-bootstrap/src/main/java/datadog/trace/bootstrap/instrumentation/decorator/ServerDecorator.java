package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public abstract class ServerDecorator extends BaseDecorator {
  /** A server span's other end is the client, so its peer port is the client's port. */
  @Override
  protected long peerPortTag() {
    return KnownTags.PEER_CLIENT_PORT;
  }

  @Override
  protected SpanPrototype buildSpanPrototype() {
    // Extend the base prototype with the server-level constants (span.kind=server, language). The
    // prototype chain mirrors the decorator class hierarchy; base afterStart applies the whole set.
    return SpanPrototype.builder()
        .extends_(super.buildSpanPrototype())
        .initKind(Tags.SPAN_KIND_SERVER)
        .initTag(DDTags.LANGUAGE_TAG_KEY, DDTags.LANGUAGE_TAG_VALUE)
        .build();
  }
}
