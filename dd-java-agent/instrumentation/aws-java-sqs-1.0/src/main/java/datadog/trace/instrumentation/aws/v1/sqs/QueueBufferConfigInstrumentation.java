package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class QueueBufferConfigInstrumentation extends AbstractSqsInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.WithTypeStructure {

  @Override
  public String instrumentedType() {
    return "com.amazonaws.services.sqs.buffered.QueueBufferConfig";
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("receiveAttributeNames"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .or(
                isMethod()
                    .and(namedOneOf("setReceiveAttributeNames", "withReceiveAttributeNames"))),
        getClass().getName() + "$QueueBufferConfigAdvice");
  }

  public static class QueueBufferConfigAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.FieldValue(value = "receiveAttributeNames", readOnly = false)
            List<String> receiveAttributeNames) {
      if (Config.get().isSqsPropagationEnabled()) {
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
}
