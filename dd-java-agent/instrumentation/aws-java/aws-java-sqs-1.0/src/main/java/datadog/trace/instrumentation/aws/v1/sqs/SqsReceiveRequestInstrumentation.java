package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
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
        isConstructor()
            .or(
                isMethod()
                    .and(
                        namedOneOf(
                            "setAttributeNames",
                            "withAttributeNames",
                            "setMessageSystemAttributeNames",
                            "withMessageSystemAttributeNames"))),
        getClass().getName() + "$ReceiveMessageRequestAdvice");
  }

  public static class ReceiveMessageRequestAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This ReceiveMessageRequest request) {
      if (!Config.get().isSqsPropagationEnabled()) {
        return;
      }

      if (addMessageSystemAttribute(request)) {
        return;
      }

      // Older SDKs only expose attributeNames and keep the returned list mutable.
      List<String> attributeNames = request.getAttributeNames();
      if (containsTraceHeader(attributeNames)) {
        return;
      }
      attributeNames.add("AWSTraceHeader");
    }

    private static boolean addMessageSystemAttribute(ReceiveMessageRequest request) {
      try {
        Method getter = request.getClass().getMethod("getMessageSystemAttributeNames");
        List<String> attributeNames = (List<String>) getter.invoke(request);
        if (containsTraceHeader(attributeNames)) {
          return true;
        }
        try {
          attributeNames.add("AWSTraceHeader");
        } catch (UnsupportedOperationException ignored) {
          List<String> updatedAttributeNames = new ArrayList<>(attributeNames);
          updatedAttributeNames.add("AWSTraceHeader");
          Method setter =
              request
                  .getClass()
                  .getMethod("setMessageSystemAttributeNames", Collection.class);
          setter.invoke(request, updatedAttributeNames);
        }
        return true;
      } catch (ReflectiveOperationException ignored) {
        return false;
      }
    }

    private static boolean containsTraceHeader(List<String> attributeNames) {
      for (String name : attributeNames) {
        if ("AWSTraceHeader".equals(name) || "All".equals(name)) {
          return true;
        }
      }
      return false;
    }
  }
}
