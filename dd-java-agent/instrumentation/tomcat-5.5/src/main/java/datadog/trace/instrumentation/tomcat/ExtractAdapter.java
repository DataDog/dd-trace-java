package datadog.trace.instrumentation.tomcat;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;

public abstract class ExtractAdapter<T> implements AgentPropagation.ContextVisitor<T> {
  abstract MimeHeaders getMimeHeaders(T t);

  static String messageBytesToString(final MessageBytes messageBytes) {
    switch (messageBytes.getType()) {
      case MessageBytes.T_BYTES:
        return messageBytes.getByteChunk().toString();
      case MessageBytes.T_CHARS:
        return messageBytes.getCharChunk().toString();
      default:
        return messageBytes.toString();
    }
  }

  @Override
  public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
    MimeHeaders headers = getMimeHeaders(carrier);
    if (headers == null) {
      return;
    }
    for (int i = 0; i < headers.size(); ++i) {
      MessageBytes header = headers.getName(i);
      MessageBytes value = headers.getValue(i);
      if (!classifier.accept(messageBytesToString(header), messageBytesToString(value))) {
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
