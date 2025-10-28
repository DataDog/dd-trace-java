package datadog.trace.instrumentation.grizzly;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.glassfish.grizzly.http.util.MimeHeaders;

public abstract class ExtractAdapter<T> implements AgentPropagation.ContextVisitor<T> {
  abstract MimeHeaders getMimeHeaders(T t);

  @Override
  public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
    MimeHeaders mimeHeaders = getMimeHeaders(carrier);
    if (mimeHeaders == null) {
      return;
    }
    for (int i = 0; i < mimeHeaders.size(); ++i) {
      if (!classifier.accept(
          mimeHeaders.getName(i).toString(UTF_8), mimeHeaders.getValue(i).toString(UTF_8))) {
        return;
      }
    }
  }

  public static final class Request
      extends ExtractAdapter<org.glassfish.grizzly.http.server.Request> {
    public static final Request GETTER = new Request();

    @Override
    MimeHeaders getMimeHeaders(org.glassfish.grizzly.http.server.Request request) {
      return request.getRequest().getHeaders();
    }
  }

  public static final class Response
      extends ExtractAdapter<org.glassfish.grizzly.http.server.Response> {
    public static final Response GETTER = new Response();

    @Override
    MimeHeaders getMimeHeaders(org.glassfish.grizzly.http.server.Response response) {
      return response.getResponse().getHeaders();
    }
  }
}
