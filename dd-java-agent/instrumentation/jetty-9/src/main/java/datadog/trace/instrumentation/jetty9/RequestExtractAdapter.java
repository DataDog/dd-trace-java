package datadog.trace.instrumentation.jetty9;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;

public class RequestExtractAdapter implements AgentPropagation.ContextVisitor<Request> {

  public static final RequestExtractAdapter GETTER = new RequestExtractAdapter();

  @Override
  public void forEachKey(Request carrier, AgentPropagation.KeyClassifier classifier) {
    HttpFields headers = carrier.getHttpFields();
    for (int i = 0; i < headers.size(); ++i) {
      HttpField field = headers.getField(i);
      if (!classifier.accept(field.getName(), field.getValue())) {
        return;
      }
    }
  }
}
