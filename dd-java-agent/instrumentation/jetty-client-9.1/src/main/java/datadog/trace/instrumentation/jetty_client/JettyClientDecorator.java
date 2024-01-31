package datadog.trace.instrumentation.jetty_client;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.net.URI;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyClientDecorator extends HttpClientDecorator<Request, Response> {
  private static final MethodHandle HANDLE = createMethodHandle();
  private static final Logger LOGGER = LoggerFactory.getLogger(JettyClientDecorator.class);
  public static final CharSequence JETTY_CLIENT = UTF8BytesString.create("jetty-client");
  public static final JettyClientDecorator DECORATE = new JettyClientDecorator();
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  private static MethodHandle createMethodHandle() {
    try {
      return new MethodHandles(JettyClientDecorator.class.getClassLoader())
          .method(HttpFields.class, "get", String.class);
    } catch (Throwable t) {
      LOGGER.debug(
          "Unable to create method handle for jetty client decorator. Header extraction will fail",
          t);
    }
    return null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jetty-client"};
  }

  @Override
  protected CharSequence component() {
    return JETTY_CLIENT;
  }

  @Override
  protected String method(final Request httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final Request httpRequest) {
    return httpRequest.getURI();
  }

  @Override
  protected int status(final Response httpResponse) {
    return httpResponse.getStatus();
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    return getHeaderValue(request.getHeaders(), headerName);
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return getHeaderValue(response.getHeaders(), headerName);
  }

  private String getHeaderValue(final HttpFields fields, final String headerName) {
    try {
      return (String) HANDLE.invokeExact(fields, headerName);
    } catch (Throwable t) {
      // probably we should log here but in case of issue we'll flood
      // however muzzle should already protect us from falling here
    }
    return null;
  }
}
