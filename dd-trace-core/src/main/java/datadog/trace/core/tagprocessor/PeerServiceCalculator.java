package datadog.trace.core.tagprocessor;

import static datadog.trace.api.KnownTags.DD_PEER_SERVICE_REMAPPED_FROM_ID;
import static datadog.trace.api.KnownTags.PEER_SERVICE_ID;

import datadog.trace.api.Config;
import datadog.trace.api.TagStore;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.DDSpanContext;
import java.util.Map;
import javax.annotation.Nonnull;

public final class PeerServiceCalculator extends EagerTagsPostProcessor {
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
      TagStore unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    Object peerService = unsafeTags.load(PEER_SERVICE_ID);
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
      remapPeerService(unsafeTags, canRemap ? unsafeTags.load(PEER_SERVICE_ID) : null);
      return;
    }
    // we have no peer.service and we do not compute defaults. Leave the map untouched
  }

  private void remapPeerService(TagStore unsafeTags, Object value) {
    if (value != null) {
      String mapped = peerServiceMapping.get(value);
      if (mapped != null) {
        unsafeTags.store(PEER_SERVICE_ID, mapped);
        unsafeTags.store(DD_PEER_SERVICE_REMAPPED_FROM_ID, value);
      }
    }
  }
}
