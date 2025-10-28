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

  public static final class CoyoteResponse extends ExtractAdapter<org.apache.coyote.Response> {
    public static final CoyoteResponse GETTER = new CoyoteResponse();

    @Override
    MimeHeaders getMimeHeaders(org.apache.coyote.Response response) {
      return response.getMimeHeaders();
    }

    @Override
    public void forEachKey(
        org.apache.coyote.Response carrier, AgentPropagation.KeyClassifier classifier) {
      super.forEachKey(carrier, classifier);
      // this ExtractAdapter is called before prepareResponse() is called on the COMMIT action
      // because
      // we add advice to the beginning of the handling of the COMMIT action.
      // prepareResponse() is what writes these headers into the mime headers.
      // Unfortunately, prepareResponse() is not a good instrumentation hook point
      // (would require custom instrumentation to report the headers after they have been written
      // into the mime headers but before the response is committed).
      // So we manually report these headers to AppSec. We ignore some other headers that
      // are not relevant for AppSec. Note that this doesn't affect tracing; tracing inspects
      // headers during Response#recycle()
      String contentType = carrier.getContentType();
      if (contentType != null) {
        classifier.accept("Content-type", contentType);
      }
      String contentLanguage = carrier.getContentLanguage();
      if (contentLanguage != null) {
        classifier.accept("Content-language", contentLanguage);
      }
      long contentLengthLong = carrier.getContentLengthLong();
      if (contentLengthLong != -1L) {
        classifier.accept("Content-length", Long.toString(contentLengthLong));
      }
    }
  }
}
