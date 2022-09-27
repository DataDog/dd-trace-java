package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.List;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class SqsReceiveRequestInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public SqsReceiveRequestInstrumentation() {
    super("aws-sdk");
  }

  @Override
  protected boolean defaultEnabled() {
    return super.defaultEnabled() && Config.get().isSqsPropagationEnabled();
  }

  @Override
  public String instrumentedType() {
    return "com.amazonaws.services.sqs.model.ReceiveMessageRequest";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().or(isMethod().and(namedOneOf("setAttributeNames", "withAttributeNames"))),
        getClass().getName() + "$ReceiveMessageRequestAdvice");
  }

  public static class ReceiveMessageRequestAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This ReceiveMessageRequest request) {
      // ReceiveMessageRequest always returns a mutable list which we can append to
      List<String> attributeNames = request.getAttributeNames();
      for (String name : attributeNames) {
        if ("AWSTraceHeader".equals(name) || "All".equals(name)) {
          return;
        }
      }
      attributeNames.add("AWSTraceHeader");
    }
  }
}
