package datadog.trace.llmobs;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.context.propagation.Propagators;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.llmobs.domain.DDLLMObsSpan;
import java.io.Closeable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit, manual distributed tracing propagation for LLM Observability, for boundaries that
 * automatic instrumentation doesn't cover — e.g. an SQS worker reading its own message attributes.
 *
 * <p>The standard APM trace context (trace id, parent id, sampling, {@code x-datadog-tags}, ...) is
 * injected/extracted via the normal {@link Propagators}. LLMObs-specific values (ml_app,
 * session_id, agent attribution) ride as additional {@code _dd.p.llmobs_*} tags appended to the
 * same {@code x-datadog-tags} carrier entry — the wire container dd-trace-py/js/go already use for
 * these tags — so a mixed-language pipeline can still join a trace across this hop.
 */
public class DDLLMObsPropagator implements LLMObs.LLMObsPropagator {
  private static final Logger LOGGER = LoggerFactory.getLogger(DDLLMObsPropagator.class);

  // Package-private (not exposed by dd-trace-core) but a long-stable wire header name; also
  // listed in datadog.trace.util.PropagationUtils#KNOWN_PROPAGATION_HEADERS.
  private static final String DATADOG_TAGS_HEADER = "x-datadog-tags";

  @Override
  public Map<String, String> injectDistributedHeaders(
      LLMObsSpan span, Map<String, String> headers) {
    Objects.requireNonNull(span, "span");
    Objects.requireNonNull(headers, "headers");
    if (!(span instanceof DDLLMObsSpan)) {
      LOGGER.debug(
          "injectDistributedHeaders requires a span started by the LLM Observability SDK, got {}; ignoring",
          span.getClass());
      return headers;
    }
    DDLLMObsSpan llmObsSpan = (DDLLMObsSpan) span;
    AgentSpan agentSpan = llmObsSpan.getAgentSpan();

    Propagators.defaultPropagator().inject(agentSpan, headers, Map::put);

    StringBuilder llmObsTags = new StringBuilder();
    appendTag(llmObsTags, LLMObsTags.PROPAGATED_ML_APP, llmObsSpan.getMlApp());
    appendTag(llmObsTags, LLMObsTags.PROPAGATED_SESSION_ID, llmObsSpan.getSessionId());
    appendTag(llmObsTags, LLMObsTags.PROPAGATED_PAGENT_SPAN_ID, llmObsSpan.getParentAgentSpanId());
    appendTag(llmObsTags, LLMObsTags.PROPAGATED_PAGENT_NAME, llmObsSpan.getParentAgentName());

    if (llmObsTags.length() > 0) {
      String existing = headers.get(DATADOG_TAGS_HEADER);
      headers.put(
          DATADOG_TAGS_HEADER,
          existing == null || existing.isEmpty()
              ? llmObsTags.toString()
              : existing + "," + llmObsTags);
    }
    return headers;
  }

  @Override
  public Closeable activateDistributedHeaders(Map<String, String> headers) {
    Objects.requireNonNull(headers, "headers");

    Context extracted =
        Propagators.defaultPropagator()
            .extract(Context.root(), headers, (carrier, visitor) -> carrier.forEach(visitor));
    AgentSpan extractedSpan = AgentSpan.fromContext(extracted);
    if (extractedSpan == null) {
      LOGGER.debug(
          "no distributed trace context found in headers; activateDistributedHeaders is a no-op");
      return () -> {};
    }

    Map<String, String> llmObsTags = parseLlmObsTags(headers.get(DATADOG_TAGS_HEADER));
    String sessionId = llmObsTags.get(LLMObsTags.PROPAGATED_SESSION_ID);
    String pagentSpanId = llmObsTags.get(LLMObsTags.PROPAGATED_PAGENT_SPAN_ID);
    String pagentName = llmObsTags.get(LLMObsTags.PROPAGATED_PAGENT_NAME);

    AgentScope apmScope = AgentTracer.get().activateSpan(extractedSpan);
    AgentSpanContext extractedContext = extractedSpan.spanContext();
    ContextScope llmObsScope =
        LLMObsContext.attach(extractedContext, sessionId, null, pagentSpanId, pagentName);

    return () -> {
      llmObsScope.close();
      apmScope.close();
    };
  }

  private static void appendTag(StringBuilder sb, String key, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append(',');
    }
    sb.append(key).append('=').append(value);
  }

  private static Map<String, String> parseLlmObsTags(String xDatadogTags) {
    Map<String, String> tags = new LinkedHashMap<>();
    if (xDatadogTags == null || xDatadogTags.isEmpty()) {
      return tags;
    }
    for (String pair : xDatadogTags.split(",")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = pair.substring(0, eq);
      if (key.startsWith("_dd.p.llmobs_")) {
        tags.put(key, pair.substring(eq + 1));
      }
    }
    return tags;
  }
}
