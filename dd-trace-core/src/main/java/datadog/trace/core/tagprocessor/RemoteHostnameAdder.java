package datadog.trace.core.tagprocessor;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.DDSpanContext;
import java.util.function.Supplier;

public final class RemoteHostnameAdder extends TagsPostProcessor {
  private final Supplier<String> hostnameSupplier;

  private TagMap.Entry cachedHostEntry = null;

  public RemoteHostnameAdder(Supplier<String> hostnameSupplier) {
    this.hostnameSupplier = hostnameSupplier;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    if (spanContext.getSpanId() != spanContext.getRootSpanId()) {
      return;
    }

    String hostname = hostnameSupplier.get();
    if (hostname == null) {
      return;
    }

    TagMap.Entry cachedHostEntry = this.cachedHostEntry;
    if (cachedHostEntry != null && hostname.equals(cachedHostEntry.objectValue())) {
      unsafeTags.set(cachedHostEntry);
      return;
    }

    TagMap.Entry newEntry = TagMap.Entry.create(DDTags.TRACER_HOST, hostname);
    unsafeTags.set(newEntry);
    this.cachedHostEntry = newEntry;
  }
}
