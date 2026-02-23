package datadog.trace.instrumentation.synapse3;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static datadog.trace.instrumentation.synapse3.ExtractAdapter.Request;
import static datadog.trace.instrumentation.synapse3.ExtractAdapter.Response;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import org.apache.http.HttpInetConnection;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.nio.NHttpConnection;

public final class SynapseServerDecorator
    extends HttpServerDecorator<HttpRequest, NHttpConnection, HttpResponse, HttpRequest> {
  public static final CharSequence SYNAPSE_SERVER = UTF8BytesString.create("synapse-server");
  public static final SynapseServerDecorator DECORATE = new SynapseServerDecorator();
  private static final CharSequence SYNAPSE_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());
  private static final CharSequence LEGACY_SYNAPSE_REQUEST = UTF8BytesString.create("http.request");
  public static final String SYNAPSE_CONTEXT_KEY = "dd.trace.synapse.context";
  public static final String SYNAPSE_CONTINUATION_KEY = "dd.trace.synapse.continuation";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"synapse3"};
  }

  @Override
  protected CharSequence component() {
    return SYNAPSE_SERVER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpRequest> getter() {
    return Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpResponse> responseGetter() {
    return Response.GETTER;
  }

  @Override
  public CharSequence spanName() {
    if (Config.get().isIntegrationSynapseLegacyOperationName()) {
      return LEGACY_SYNAPSE_REQUEST;
    }
    return SYNAPSE_REQUEST;
  }

  @Override
  protected String method(final HttpRequest request) {
    return request.getRequestLine().getMethod();
  }

  @Override
  protected URIDataAdapter url(final HttpRequest request) {
    return URIDataAdapterBase.fromURI(
        request.getRequestLine().getUri(), URIDefaultDataAdapter::new);
  }

  @Override
  protected String peerHostIP(final NHttpConnection connection) {
    if (connection instanceof HttpInetConnection) {
      return ((HttpInetConnection) connection).getRemoteAddress().getHostAddress();
    }
    return null;
  }

  @Override
  protected int peerPort(final NHttpConnection connection) {
    if (connection instanceof HttpInetConnection) {
      return ((HttpInetConnection) connection).getRemotePort();
    }
    return UNSET_PORT;
  }

  @Override
  protected int status(final HttpResponse response) {
    if (null != response.getStatusLine()) {
      return response.getStatusLine().getStatusCode();
    }
    return UNSET_STATUS;
  }
}
