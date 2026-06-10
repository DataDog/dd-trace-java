package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import datadog.trace.api.TagMap;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.CoreTagIds;
import datadog.trace.core.DDSpanContext;
import java.util.Map;
import javax.annotation.Nonnull;

public final class PeerServiceCalculator extends TagsPostProcessor {
  private final NamingSchema.ForPeerService peerServiceNaming;

  private final Map<String, String> peerServiceMapping;

  private final boolean canRemap;

  public PeerServiceCalculator() {
    this(SpanNaming.instance().namingSchema().peerService(), Config.get().getPeerServiceMapping());
  }

  @VisibleForTesting
  PeerServiceCalculator(
      @Nonnull final NamingSchema.ForPeerService peerServiceNaming,
      @Nonnull final Map<String, String> peerServiceMapping) {
    this.peerServiceNaming = peerServiceNaming;
    this.peerServiceMapping = peerServiceMapping;
    this.canRemap = !peerServiceMapping.isEmpty();
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    Object peerService = peerService(unsafeTags);
    // the user set it
    if (peerService != null) {
      if (canRemap) {
        remapPeerService(unsafeTags, peerService);
        return;
      }
    } else if (peerServiceNaming.supports()) {
      // calculate the defaults (if any)
      peerServiceNaming.tags(unsafeTags);
      // only remap if the mapping is not empty (saves one get)
      remapPeerService(unsafeTags, canRemap ? peerService(unsafeTags) : null);
      return;
    }
    // we have no peer.service and we do not compute defaults. Leave the map untouched
  }

  private static Object peerService(TagMap unsafeTags) {
    TagMap.Entry entry = unsafeTags.getEntry(CoreTagIds.PEER_SERVICE);
    return entry == null ? null : entry.objectValue();
  }

  private void remapPeerService(TagMap unsafeTags, Object value) {
    if (value != null) {
      String mapped = peerServiceMapping.get(value);
      if (mapped != null) {
        unsafeTags.set(CoreTagIds.PEER_SERVICE, mapped);
        unsafeTags.set(CoreTagIds.PEER_SERVICE_REMAPPED_FROM, value);
      }
    }
  }
}
