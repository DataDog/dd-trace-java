package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

public final class PeerServiceCalculator implements TagsPostProcessor {
  private final NamingSchema.ForPeerService peerServiceNaming;

  private final Map<String, String> peerServiceMapping;

  private final boolean canRemap;

  public PeerServiceCalculator() {
    this(SpanNaming.instance().namingSchema().peerService(), Config.get().getPeerServiceMapping());
  }

  // Visible for testing
  PeerServiceCalculator(
      @Nonnull final NamingSchema.ForPeerService peerServiceNaming,
      @Nonnull final Map<String, String> peerServiceMapping) {
    this.peerServiceNaming = peerServiceNaming;
    this.peerServiceMapping = peerServiceMapping;
    this.canRemap = !peerServiceMapping.isEmpty();
  }

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    Object peerService = unsafeTags.get(Tags.PEER_SERVICE);
    // the user set it
    if (peerService != null) {
      if (canRemap) {
        remapPeerService(unsafeTags, peerService);
        return unsafeTags;
      }
    } else if (peerServiceNaming.supports()) {
      // calculate the defaults (if any)
      peerServiceNaming.tags(unsafeTags);
      // only remap if the mapping is not empty (saves one get)
      remapPeerService(unsafeTags, canRemap ? unsafeTags.get(Tags.PEER_SERVICE) : null);
      return unsafeTags;
    }
    // we have no peer.service and we do not compute defaults. Leave the map untouched
    return unsafeTags;
  }

  private void remapPeerService(Map<String, Object> unsafeTags, Object value) {
    if (value != null) {
      String mapped = peerServiceMapping.get(value);
      if (mapped != null) {
        unsafeTags.put(Tags.PEER_SERVICE, mapped);
        unsafeTags.put(DDTags.PEER_SERVICE_REMAPPED_FROM, value);
      }
    }
  }
}
