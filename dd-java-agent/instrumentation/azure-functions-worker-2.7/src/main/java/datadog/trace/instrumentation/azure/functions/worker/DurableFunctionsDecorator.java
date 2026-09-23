package datadog.trace.instrumentation.azure.functions.worker;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public final class DurableFunctionsDecorator extends BaseDecorator {
  public static final DurableFunctionsDecorator DECORATE = new DurableFunctionsDecorator();
  public static final CharSequence AZURE_FUNCTIONS_REQUEST =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().cloud().operationForFaas("azure"));

  private static final CharSequence AZURE_FUNCTIONS = UTF8BytesString.create("azure-functions");

  private DurableFunctionsDecorator() {}

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"azure-functions"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SERVERLESS;
  }

  @Override
  protected CharSequence component() {
    return AZURE_FUNCTIONS;
  }

  @Override
  protected void doAfterStart(AgentSpan span) {
    super.doAfterStart(span);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
  }

  public void onInvoke(AgentSpan span, String functionName, String trigger) {
    span.setResourceName(trigger + " " + functionName);
    span.setTag("aas.function.name", functionName);
    span.setTag("aas.function.trigger", trigger);
  }
}
