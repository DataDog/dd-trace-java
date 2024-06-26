package datadog.trace.instrumentation.jetty10;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;

// source code is identical to the one in jetty9, but result is not
// binary compatible because HttpFields became an interface
public abstract class ExtractAdapter<T> implements AgentPropagation.ContextVisitor<T> {
  abstract HttpFields getHttpFields(T t);

  @Override
  public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
    HttpFields headers = getHttpFields(carrier);
    if (headers == null) {
      return;
    }
    for (int i = 0; i < headers.size(); ++i) {
      HttpField field = headers.getField(i);
      if (field != null && !classifier.accept(field.getName(), field.getValue())) {
        return;
      }
    }
  }

  public static final class Request extends ExtractAdapter<org.eclipse.jetty.server.Request> {
    public static final Request GETTER = new Request();

    @Override
    HttpFields getHttpFields(org.eclipse.jetty.server.Request request) {
      return request.getHttpFields();
    }
  }

  public static final class Response extends ExtractAdapter<org.eclipse.jetty.server.Response> {
    public static final Response GETTER = new Response();

    @Override
    HttpFields getHttpFields(org.eclipse.jetty.server.Response response) {
      return response.getHttpFields();
    }
  }
}
