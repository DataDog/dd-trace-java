package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.instrumentation.aws.v2.AwsContextRestorationTest.requestContext;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.context.ContextKey;
import datadog.context.ContextManager;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
@WithConfig(key = "aws-sdk.legacy.tracing.enabled", value = "true")
class MissingContextRestorationForkedTest extends DDJavaSpecification {
  @Test
  void missingAwsContextSuppressesHttpTracingAndRestoresTheCaller() {
    ContextManager.allowTesting();
    ContextHandle original = Context.current().checkpoint();
    try {
      assertTrue(AwsSdkClientDecorator.AWS_LEGACY_TRACING);
      Context caller = Context.root().with(ContextKey.named("aws-caller"), "caller");
      caller.swap();
      Context.setCaptureEnabled(false);

      ContextHandle previous =
          AwsHttpClientInstrumentation.SyncHttpRequestAdvice.methodEnter(
              requestContext(new ExecutionAttributes()));
      try {
        assertSame(AgentTracer.noopSpan(), Context.current());
      } finally {
        AwsHttpClientInstrumentation.SyncHttpRequestAdvice.methodExit(previous);
      }

      assertSame(caller, Context.current());
      assertFalse(Context.isCaptureEnabled());
    } finally {
      original.swap();
      AgentTracer.installLegacyContextManager();
    }
  }
}
