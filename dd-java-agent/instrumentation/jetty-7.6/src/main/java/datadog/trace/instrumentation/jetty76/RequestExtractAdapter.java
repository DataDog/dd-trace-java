package datadog.trace.instrumentation.jetty76;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;

public class RequestExtractAdapter implements AgentPropagation.ContextVisitor<Request> {

  public static final RequestExtractAdapter GETTER = new RequestExtractAdapter();

  @Override
  public void forEachKey(Request carrier, AgentPropagation.KeyClassifier classifier) {
    HttpFields headers = carrier.getConnection().getRequestFields();
    for (int i = 0; i < headers.size(); ++i) {
      HttpFields.Field field = headers.getField(i);
      if (!classifier.accept(field.getName(), field.getValue())) {
        return;
      }
    }
  }
}
