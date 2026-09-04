package datadog.trace.llmobs;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.context.propagation.Propagators;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.llmobs.domain.DDLLMObsSpan;
import java.io.Closeable;
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
 * session_id, agent attribution) are written onto the span's {@link AgentSpanContext} as dedicated
 * propagation-tags fields before injection, so the same {@link Propagators} call serializes them as
 * additional {@code _dd.p.llmobs_*} tags — via {@code x-datadog-tags} or {@code tracestate},
 * whichever the configured propagation style carries — the wire container dd-trace-py/js/go already
 * use for these tags, so a mixed-language pipeline can still join a trace across this hop.
 */
public class DDLLMObsPropagator implements LLMObs.LLMObsPropagator {
  private static final Logger LOGGER = LoggerFactory.getLogger(DDLLMObsPropagator.class);

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

    AgentSpanContext spanContext = agentSpan.spanContext();
    spanContext.updateLLMObsMlApp(llmObsSpan.getMlApp());
    spanContext.updateLLMObsSessionId(llmObsSpan.getSessionId());
    spanContext.updateLLMObsParentAgentSpanId(llmObsSpan.getParentAgentSpanId());
    spanContext.updateLLMObsParentAgentName(llmObsSpan.getParentAgentName());

    Propagators.defaultPropagator().inject(agentSpan, headers, Map::put);
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

    AgentSpanContext extractedContext = extractedSpan.spanContext();
    CharSequence sessionId = extractedContext.getLLMObsSessionId();
    CharSequence pagentSpanId = extractedContext.getLLMObsParentAgentSpanId();
    CharSequence pagentName = extractedContext.getLLMObsParentAgentName();

    AgentScope apmScope = AgentTracer.get().activateSpan(extractedSpan);
    ContextScope llmObsScope =
        LLMObsContext.attach(
            extractedContext,
            sessionId == null ? null : sessionId.toString(),
            null,
            pagentSpanId == null ? null : pagentSpanId.toString(),
            pagentName == null ? null : pagentName.toString());

    return () -> {
      llmObsScope.close();
      apmScope.close();
    };
  }
}
