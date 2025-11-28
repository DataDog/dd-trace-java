package datadog.trace.instrumentation.azure.functions;

import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;

public class AzureFunctionsDecorator
    extends HttpServerDecorator<
        HttpRequestMessage, HttpRequestMessage, HttpResponseMessage, HttpRequestMessage> {
  public static final CharSequence AZURE_FUNCTIONS = UTF8BytesString.create("azure-functions");

  public static final AzureFunctionsDecorator DECORATE = new AzureFunctionsDecorator();
  public static final CharSequence AZURE_FUNCTIONS_REQUEST =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().cloud().operationForFaas("azure"));

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"azure-functions"};
  }

  @Override
  protected CharSequence component() {
    return AZURE_FUNCTIONS;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpRequestMessage> getter() {
    return HttpRequestMessageExtractAdapter.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpResponseMessage> responseGetter() {
    return null;
  }

  @Override
  public CharSequence spanName() {
    return AZURE_FUNCTIONS_REQUEST;
  }

  @Override
  protected String method(final HttpRequestMessage request) {
    return request.getHttpMethod().name();
  }

  @Override
  protected URIDataAdapter url(final HttpRequestMessage request) {
    return new URIDefaultDataAdapter(request.getUri());
  }

  @Override
  protected String peerHostIP(final HttpRequestMessage request) {
    return null;
  }

  @Override
  protected int peerPort(final HttpRequestMessage request) {
    return 0;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SERVERLESS;
  }

  @Override
  protected int status(final HttpResponseMessage response) {
    return response.getStatusCode();
  }

  public AgentSpan afterStart(final AgentSpan span, final String functionName) {
    span.setTag("aas.function.name", functionName);
    span.setTag("aas.function.trigger", "Http");
    return super.afterStart(span);
  }
}
