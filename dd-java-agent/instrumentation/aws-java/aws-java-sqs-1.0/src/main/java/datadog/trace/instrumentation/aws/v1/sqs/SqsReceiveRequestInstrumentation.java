package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.List;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class SqsReceiveRequestInstrumentation extends AbstractSqsInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.amazonaws.services.sqs.model.ReceiveMessageRequest";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().or(isMethod().and(namedOneOf("setAttributeNames", "withAttributeNames"))),
        getClass().getName() + "$ReceiveMessageRequestAdvice");
  }

  public static class ReceiveMessageRequestAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This ReceiveMessageRequest request) {
      if (Config.get().isSqsPropagationEnabled()) {
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
}
