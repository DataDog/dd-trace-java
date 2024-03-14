package test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.local.LocalTransportSender;

/** Test sender that checks the outgoing TRANSPORT_HEADERS have the expected propagation headers. */
public class TestSender extends LocalTransportSender {
  private static final Set<String> EXPECTED_HEADERS =
      new HashSet<>(
          Arrays.asList(
              "x-datadog-trace-id",
              "x-datadog-parent-id",
              "x-datadog-sampling-priority",
              "x-datadog-tags",
              "traceparent",
              "tracestate"));

  @Override
  public InvocationResponse invoke(MessageContext messageContext) {
    @SuppressWarnings("unchecked")
    Map<String, Object> headers =
        (Map<String, Object>) messageContext.getProperty("TRANSPORT_HEADERS");
    if (null == headers) {
      throw new RuntimeException("Missing TRANSPORT_HEADERS");
    }
    Set<String> missing = new HashSet<>(EXPECTED_HEADERS);
    missing.removeAll(headers.keySet());
    if (!missing.isEmpty()) {
      throw new RuntimeException("Missing propagation headers: " + missing);
    }
    messageContext.setProperty("transport.http.statusCode", 200);
    return InvocationResponse.CONTINUE;
  }
}
