package datadog.trace.instrumentation.tomcat;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;

public abstract class ExtractAdapter<T> implements AgentPropagation.ContextVisitor<T> {
  abstract MimeHeaders getMimeHeaders(T t);

  @Override
  public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
    MimeHeaders headers = getMimeHeaders(carrier);
    if (headers == null) {
      return;
    }
    for (int i = 0; i < headers.size(); ++i) {
      MessageBytes header = headers.getName(i);
      MessageBytes value = headers.getValue(i);
      if (!classifier.accept(header.toString(), value.toString())) {
        return;
      }
    }
  }

  public static final class Request extends ExtractAdapter<org.apache.coyote.Request> {
    public static final Request GETTER = new Request();

    @Override
    MimeHeaders getMimeHeaders(org.apache.coyote.Request request) {
      return request.getMimeHeaders();
    }
  }

  public static final class Response
      extends ExtractAdapter<org.apache.catalina.connector.Response> {
    public static final Response GETTER = new Response();

    @Override
    MimeHeaders getMimeHeaders(org.apache.catalina.connector.Response response) {
      return response.getCoyoteResponse().getMimeHeaders();
    }
  }
}
