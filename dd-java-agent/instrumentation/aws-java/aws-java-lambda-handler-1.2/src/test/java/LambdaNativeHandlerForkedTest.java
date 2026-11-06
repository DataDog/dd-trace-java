import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.Context;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.InstrumentationErrors;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

@WithConfig(key = "_HANDLER", value = "Handler", env = true, addPrefix = false)
@WithConfig(key = "universal.instrumentation", value = "false")
class LambdaNativeHandlerForkedTest extends AbstractInstrumentationTest {
  private Context context() {
    return (Context)
        java.lang.reflect.Proxy.newProxyInstance(
            Context.class.getClassLoader(),
            new Class<?>[] {Context.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getAwsRequestId":
                  return "native-request";
                case "getFunctionName":
                  return "MyFunction";
                case "getFunctionVersion":
                  return "1";
                default:
                  return method.getReturnType() == int.class ? 0 : null;
              }
            });
  }

  @Test
  void nativeHandlerPreservesInputAndParentsChildrenAcrossNestedAdapters() throws Exception {
    new HandlerStreamingWithChild()
        .handleRequest(
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayOutputStream(),
            context());
    assertNull(AgentTracer.activeSpan());
    assertTrue(InstrumentationErrors.noErrors());
    assertTraces(
        trace(
            span()
                .operationName("aws.lambda")
                .resourceName("MyFunction")
                .type(DDSpanTypes.SERVERLESS),
            span().operationName("handler.work").childOfPrevious()));
  }

  @Test
  void throwingHandlerCleansUpForTheNextInvocation() throws Exception {
    assertThrows(
        Error.class,
        () ->
            new HandlerStreamingWithError()
                .handleRequest(
                    new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), context()));
    assertNull(AgentTracer.activeSpan());
    new HandlerStreamingNested()
        .handleRequest(
            new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), context());
    assertTrue(InstrumentationErrors.noErrors());
    assertTraces(
        trace(
            span()
                .operationName("aws.lambda")
                .resourceName("MyFunction")
                .type(DDSpanTypes.SERVERLESS)
                .error(true)),
        trace(
            span()
                .operationName("aws.lambda")
                .resourceName("MyFunction")
                .type(DDSpanTypes.SERVERLESS)
                .error(false)));
  }
}
