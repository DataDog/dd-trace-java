package datadog.trace.core.datastreams;

import static datadog.trace.api.DDTags.PATHWAY_HASH;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.SPAN_CONTEXT_KEY;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.PROPAGATION_KEY_BASE64;
import static datadog.trace.core.datastreams.DefaultPathwayContext.TAGS_CONTEXT_KEY;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.HttpCodec;
import java.io.IOException;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataStreamContextInjector implements HttpCodec.ContextInjector {
  private static final Logger LOGGER = LoggerFactory.getLogger(DataStreamContextInjector.class);
  private static final LinkedHashMap<String, String> EMPTY = new LinkedHashMap<>();
  private final DataStreamsMonitoring dataStreamsMonitoring;

  public DataStreamContextInjector(DataStreamsMonitoring dataStreamsMonitoring) {
    this.dataStreamsMonitoring = dataStreamsMonitoring;
  }

  @Override
  public <C> void inject(
      AgentScopeContext scopeContext, C carrier, AgentPropagation.Setter<C> setter) {
    // Get Pathway context and tags from context
    AgentSpan span = scopeContext.get(SPAN_CONTEXT_KEY);
    if (span == null || !(span.context() instanceof DDSpanContext)) {
      return;
    }
    LinkedHashMap<String, String> tags = scopeContext.get(TAGS_CONTEXT_KEY);
    if (tags == null) {
      tags = EMPTY;
    }
    final DDSpanContext context = (DDSpanContext) span.context();
    PathwayContext pathwayContext = context.getPathwayContext();
    pathwayContext.setCheckpoint(tags, dataStreamsMonitoring::add);

    boolean injected =
        setter instanceof AgentPropagation.BinarySetter
            ? injectBinaryPathwayContext(
                pathwayContext, carrier, (AgentPropagation.BinarySetter<C>) setter)
            : injectPathwayContext(pathwayContext, carrier, setter);
    if (injected && pathwayContext.getHash() != 0) {
      span.setTag(PATHWAY_HASH, Long.toUnsignedString(pathwayContext.getHash()));
    }
  }

  private static <C> boolean injectBinaryPathwayContext(
      PathwayContext pathwayContext, C carrier, AgentPropagation.BinarySetter<C> setter) {
    try {
      byte[] encodedContext = pathwayContext.encode();
      if (encodedContext != null) {
        LOGGER.debug("Injecting binary pathway context {}", pathwayContext);
        setter.set(carrier, PROPAGATION_KEY_BASE64, encodedContext);
        return true;
      }
    } catch (IOException e) {
      LOGGER.debug("Unable to set encode pathway context", e);
    }
    return false;
  }

  private static <C> boolean injectPathwayContext(
      PathwayContext pathwayContext, C carrier, AgentPropagation.Setter<C> setter) {
    try {
      String encodedContext = pathwayContext.strEncode();
      if (encodedContext != null) {
        LOGGER.debug("Injecting pathway context {}", pathwayContext);
        setter.set(carrier, PROPAGATION_KEY_BASE64, encodedContext);
        return true;
      }
    } catch (IOException e) {
      LOGGER.debug("Unable to set encode pathway context", e);
    }
    return false;
  }
}
