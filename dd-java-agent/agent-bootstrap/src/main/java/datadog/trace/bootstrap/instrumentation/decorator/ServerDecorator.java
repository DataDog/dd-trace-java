package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public abstract class ServerDecorator extends BaseDecorator {

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
