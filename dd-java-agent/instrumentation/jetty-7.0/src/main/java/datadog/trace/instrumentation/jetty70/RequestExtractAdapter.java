package datadog.trace.instrumentation.jetty70;

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
      // field might be null due to versioning and recycling
      if (field != null && !classifier.accept(field.getName(), field.getValue())) {
        return;
      }
    }
  }
}
