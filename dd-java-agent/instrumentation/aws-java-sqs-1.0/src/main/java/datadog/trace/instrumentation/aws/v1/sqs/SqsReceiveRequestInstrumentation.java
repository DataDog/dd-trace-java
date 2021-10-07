package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class SqsReceiveRequestInstrumentation extends Instrumenter.Tracing {
  public SqsReceiveRequestInstrumentation() {
    super("aws-sdk");
  }

  @Override
  protected boolean defaultEnabled() {
    return super.defaultEnabled() && Config.get().isSqsPropagationEnabled();
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("com.amazonaws.services.sqs.model.ReceiveMessageRequest");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf(
        "com.amazonaws.services.sqs.model.ReceiveMessageRequest",
        "com.amazonaws.services.sqs.buffered.QueueBufferConfig");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        (isConstructor().or(isMethod().and(namedOneOf("setAttributeNames", "withAttributeNames"))))
            .and(isDeclaredBy(named("com.amazonaws.services.sqs.model.ReceiveMessageRequest"))),
        getClass().getName() + "$ReceiveMessageRequestAdvice");
    transformation.applyAdvice(
        (isConstructor()
                .or(
                    isMethod()
                        .and(namedOneOf("setReceiveAttributeNames", "withReceiveAttributeNames"))))
            .and(isDeclaredBy(named("com.amazonaws.services.sqs.buffered.QueueBufferConfig"))),
        getClass().getName() + "$QueueBufferConfigAdvice");
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

  public static class QueueBufferConfigAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.FieldValue(value = "receiveAttributeNames", readOnly = false)
            List<String> receiveAttributeNames) {
      // QueueBufferConfig maintains an immutable list which we may need to replace
      for (String name : receiveAttributeNames) {
        if ("AWSTraceHeader".equals(name) || "All".equals(name)) {
          return;
        }
      }
      int oldLength = receiveAttributeNames.size();
      String[] nameArray = receiveAttributeNames.toArray(new String[oldLength + 1]);
      nameArray[oldLength] = "AWSTraceHeader";
      receiveAttributeNames = asList(nameArray);
    }
  }
}
