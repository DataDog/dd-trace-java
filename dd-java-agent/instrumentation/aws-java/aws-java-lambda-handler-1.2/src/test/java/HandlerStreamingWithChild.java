import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class HandlerStreamingWithChild implements RequestStreamHandler {
  @Override
  public void handleRequest(InputStream input, OutputStream output, Context context)
      throws IOException {
    if (input.read() != '{') {
      throw new IllegalStateException("Instrumentation consumed the input stream");
    }
    AgentSpan child = AgentTracer.startSpan("test", "handler.work");
    try {
      new HandlerStreamingNested().handleRequest(input, output, context);
    } finally {
      child.finish();
    }
  }
}
