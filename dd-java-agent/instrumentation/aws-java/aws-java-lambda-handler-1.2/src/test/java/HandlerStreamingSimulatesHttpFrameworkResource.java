import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Simulates HTTP server instrumentation updating the local root span resource (e.g. route) while
 * the Lambda invocation span is active.
 */
public class HandlerStreamingSimulatesHttpFrameworkResource implements RequestStreamHandler {

  @Override
  public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context)
      throws IOException {
    AgentSpan span = AgentTracer.activeSpan();
    if (span != null) {
      span.setResourceName("POST /api/simulated", ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);
    }
    outputStream.write('O');
    outputStream.write('K');
  }
}
