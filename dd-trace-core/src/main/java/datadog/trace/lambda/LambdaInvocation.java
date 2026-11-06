package datadog.trace.lambda;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.core.CoreTracer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the real invocation span when universal instrumentation is disabled. */
public final class LambdaInvocation implements AgentTracer.LambdaInvocation {
  private final CoreTracer tracer;
  private final AgentSpan invocation;
  private final Context context;
  private final String functionName;
  private final AtomicBoolean finished = new AtomicBoolean();

  private LambdaInvocation(
      CoreTracer tracer, AgentSpan invocation, Context context, String functionName) {
    this.tracer = tracer;
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
    AgentTracer.SpanBuilder builder =
        tracer
            .buildSpan("aws-lambda", "aws.lambda")
            .ignoreActiveSpan()
            .asChildOf((AgentSpanContext) null)
            .withSpanType(DDSpanTypes.SERVERLESS)
            .withResourceName(functionName)
            .withServiceName(config.getServiceName())
            .withTag("request_id", requestId)
            .withTag("functionname", functionName.toLowerCase(Locale.ROOT))
            .withTag("resource_names", functionName)
            .withTag("function_arn", invokedArn)
            .withTag("executedversion", functionVersion)
            .withTag(DDTags.ORIGIN_KEY, "lambda")
            .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    AgentSpanContext appSec = LambdaAppSecHandler.processRequestStart(event);
    if (appSec instanceof TagContext) {
      TagContext data = (TagContext) appSec;
      builder.withRequestContextData(RequestContextSlot.APPSEC, data.getRequestContextDataAppSec());
      if (data.getTags() != null) {
        for (TagMap.EntryReader tag : data.getTags()) {
          builder.withTag(tag.tag(), tag.stringValue());
        }
      }
    }
    AgentSpan span = null;
    try {
      span = builder.start();
      return new LambdaInvocation(tracer, span, Context.root().with(span), functionName);
    } catch (RuntimeException | Error failure) {
      try {
        LambdaAppSecHandler.processRequestEnd(span);
      } finally {
        if (span != null) {
          span.finish();
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
        LambdaAppSecHandler.processResponseData(invocation, result);
      }
    } finally {
      try {
        LambdaAppSecHandler.processRequestEnd(invocation);
      } finally {
        invocation.setResourceName(functionName, ResourceNamePriorities.TAG_INTERCEPTOR);
      }
    }
  }

  @Override
  public void finish() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    try {
      invocation.finish();
    } finally {
      tracer.flush();
    }
  }
}
