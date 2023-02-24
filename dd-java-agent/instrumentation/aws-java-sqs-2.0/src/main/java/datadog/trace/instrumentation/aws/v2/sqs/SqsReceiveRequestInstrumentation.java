package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class SqsReceiveRequestInstrumentation extends AbstractSqsInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.WithTypeStructure {

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest$BuilderImpl";
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("attributeNames"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("build")), getClass().getName() + "$ReceiveMessageRequestAdvice");
  }

  public static class ReceiveMessageRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEntry(
        @Advice.FieldValue(value = "attributeNames", readOnly = false)
            List<String> attributeNames) {
      // ReceiveMessageRequest.BuilderImpl maintains an immutable list which we may need to replace
      if (Config.get().isSqsPropagationEnabled()) {
        for (String name : attributeNames) {
          if ("AWSTraceHeader".equals(name) || "All".equals(name)) {
            return;
          }
        }
        int oldLength = attributeNames.size();
        String[] nameArray = attributeNames.toArray(new String[oldLength + 1]);
        nameArray[oldLength] = "AWSTraceHeader";
        attributeNames = asList(nameArray);
      }
    }
  }
}
