package datadog.trace.lambda;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.TracePropagationBehaviorExtract;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.core.CoreTracer;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the real invocation span when universal instrumentation is disabled. */
public final class LambdaInvocation implements AgentTracer.LambdaInvocation {
  private final CoreTracer tracer;
  private final LambdaInferredSpans inferred;
  private final AgentSpan invocation;
  private final Context context;
  private final String functionName;
  private final AtomicBoolean finished = new AtomicBoolean();

  private LambdaInvocation(
      CoreTracer tracer,
      LambdaInferredSpans inferred,
      AgentSpan invocation,
      Context context,
      String functionName) {
    this.tracer = tracer;
    this.inferred = inferred;
    this.invocation = invocation;
    this.context = context;
    this.functionName = functionName;
  }

  public static LambdaInvocation start(
      CoreTracer tracer,
      Config config,
      Object event,
      String requestId,
      String functionName,
      String functionVersion,
      String invokedArn) {
    functionName =
        valueOr(functionName, valueOr(ConfigHelper.env("AWS_LAMBDA_FUNCTION_NAME"), "lambda"));
    Map<String, Object> payload = LambdaEventData.payload(event);
    String region =
        valueOr(
            ConfigHelper.env("AWS_REGION"), LambdaEventData.part(valueOr(invokedArn, ""), ":", 3));
    LambdaTrigger trigger =
        new LambdaTriggerExtractor(region, functionName, config.getServiceMapping())
            .extract(payload);
    LambdaPropagation extracted = LambdaPropagation.extract(config, trigger, payload);
    AgentSpanContext parent = extracted.parent;
    CharSequence origin =
        parent instanceof TagContext
                && config.getTracePropagationBehaviorExtract()
                    == TracePropagationBehaviorExtract.CONTINUE
            ? ((TagContext) parent).getOrigin()
            : null;
    long started = System.currentTimeMillis() * 1_000;
    LambdaInferredSpans inferred =
        new LambdaInferredSpans(tracer, config, trigger, parent, started);
    AgentSpan span = null;
    try {
      AgentTracer.SpanBuilder builder =
          tracer
              .buildSpan("aws-lambda", "aws.lambda")
              .ignoreActiveSpan()
              .asChildOf(inferred.parent)
              .withStartTimestamp(started)
              .withSpanType(DDSpanTypes.SERVERLESS)
              .withResourceName(functionName)
              .withServiceName(config.getServiceName())
              .withTag("request_id", requestId)
              .withTag("functionname", functionName.toLowerCase(Locale.ROOT))
              .withTag("resource_names", functionName)
              .withTag("function_arn", invokedArn)
              .withTag("executedversion", functionVersion)
              .withTag(DDTags.ORIGIN_KEY, origin == null ? "lambda" : origin.toString())
              .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
      trigger.tags.forEach(builder::withTag);
      AgentSpanContext appSec = LambdaAppSecHandler.processRequestStart(event);
      if (appSec instanceof TagContext) {
        TagContext data = (TagContext) appSec;
        builder.withRequestContextData(
            RequestContextSlot.APPSEC, data.getRequestContextDataAppSec());
        if (data.getTags() != null) {
          for (TagMap.EntryReader tag : data.getTags()) {
            builder.withTag(tag.tag(), tag.stringValue());
          }
        }
      }
      span = builder.start();
      span.setServiceName(config.getServiceName());
      return new LambdaInvocation(
          tracer,
          inferred,
          span,
          extracted.context.with(span),
          functionName,
          client.begin(span, ((DDSpan) span).getParentId(), requestId));
    } catch (RuntimeException | Error failure) {
      try {
        LambdaAppSecHandler.processRequestEnd(span);
      } finally {
        try {
          if (span != null) {
            span.finish();
          }
        } finally {
          inferred.finish(System.currentTimeMillis() * 1_000);
        }
      }
      throw failure;
    }
  }

  private static String valueOr(String value, String fallback) {
    return value == null || value.isEmpty() ? fallback : value;
  }

  @Override
  public AgentSpan span() {
    return invocation;
  }

  @Override
  public ContextScope activate() {
    ContextScope contextScope = context.attach();
    try {
      ContextScope spanScope = tracer.activateSpan(invocation);
      return new ContextScope() {
        @Override
        public Context context() {
          return context;
        }

        @Override
        public void close() {
          try {
            spanScope.close();
          } finally {
            contextScope.close();
          }
        }
      };
    } catch (RuntimeException | Error failure) {
      contextScope.close();
      throw failure;
    }
  }

  @Override
  public void onExit(Object result, Throwable error) {
    try {
      if (error != null) {
        invocation.addThrowable(error);
      } else {
        recordResponseStatus(result);
        LambdaAppSecHandler.processResponseData(invocation, result);
      }
    } finally {
      try {
        LambdaAppSecHandler.processRequestEnd(invocation);
      } finally {
        invocation.setResourceName(functionName, ResourceNamePriorities.TAG_INTERCEPTOR);
        inferred.enrich(invocation);
      }
    }
  }

  private void recordResponseStatus(Object response) {
    Object status = LambdaEventData.payload(response).get("statusCode");
    if (!(status instanceof Number)) {
      return;
    }
    try {
      long code = new BigDecimal(status.toString()).longValueExact();
      invocation.setTag(Tags.HTTP_STATUS, Long.toString(code));
      if (code >= 500 && code <= 599) {
        invocation.setError(true);
      }
    } catch (NumberFormatException | ArithmeticException ignored) {
      // Match the extension's integer statusCode contract.
    }
  }

  @Override
  public void finish() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    try {
      try {
        invocation.finish();
      } finally {
        inferred.finish((invocation.getStartTime() + invocation.getDurationNano()) / 1_000);
      }
    } finally {
      tracer.flush();
    }
  }
}
