package datadog.trace.instrumentation.tomcat;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.coyote.Request;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;

public class RequestExtractAdapter implements AgentPropagation.ContextVisitor<Request> {

  public static final RequestExtractAdapter GETTER = new RequestExtractAdapter();

  @Override
  public void forEachKey(Request carrier, AgentPropagation.KeyClassifier classifier) {
    MimeHeaders headers = carrier.getMimeHeaders();
    for (int i = 0; i < headers.size(); ++i) {
      MessageBytes header = headers.getName(i);
      MessageBytes value = headers.getValue(i);
      if (!classifier.accept(header.toString(), value.toString())) {
        return;
      }
    }
  }
}
