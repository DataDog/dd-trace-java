package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.amazonaws.handlers.RequestHandler2;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This instrumentation might work with versions before 1.11.0, but this was the first version that
 * is tested. It could possibly be extended earlier.
 */
@AutoService(Instrumenter.class)
public final class AWSClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.WithTypeStructure {

  public AWSClientInstrumentation() {
    super("aws-sdk");
  }

  @Override
  public String instrumentedType() {
    return "com.amazonaws.AmazonWebServiceClient";
  }

  @Override
  public ElementMatcher<? extends ByteCodeElement> structureMatcher() {
    return declaresField(named("requestHandler2s"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AwsSdkClientDecorator",
      packageName + ".AwsSdkClientDecorator$1",
      packageName + ".AwsSdkClientDecorator$2",
      packageName + ".RequestAccess",
      packageName + ".RequestAccess$1",
      packageName + ".TracingRequestHandler",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor(), AWSClientInstrumentation.class.getName() + "$AWSClientAdvice");
  }

  public static class AWSClientAdvice {
    // Since we're instrumenting the constructor, we can't add onThrowable.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addHandler(
        @Advice.FieldValue("requestHandler2s") final List<RequestHandler2> handlers) {
      boolean hasDDHandler = false;
      for (final RequestHandler2 handler : handlers) {
        if (handler instanceof TracingRequestHandler) {
          hasDDHandler = true;
          break;
        }
      }
      if (!hasDDHandler) {
        handlers.add(new TracingRequestHandler());
      }
    }
  }
}
