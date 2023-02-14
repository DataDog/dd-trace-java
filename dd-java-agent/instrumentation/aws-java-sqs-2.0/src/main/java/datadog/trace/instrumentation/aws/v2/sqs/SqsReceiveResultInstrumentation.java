package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.services.sqs.model.Message;

@AutoService(Instrumenter.class)
public class SqsReceiveResultInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public SqsReceiveResultInstrumentation() {
    super("aws-sdk");
  }

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse";
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        // we don't need to instrument messages when we're doing legacy AWS-SDK tracing
        && !InstrumenterConfig.get().isLegacyInstrumentationEnabled(false, "aws-sdk");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MessageExtractAdapter",
      packageName + ".SqsDecorator",
      packageName + ".TracingIterator",
      packageName + ".TracingList",
      packageName + ".TracingListIterator"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("messages")), getClass().getName() + "$GetMessagesAdvice");
  }

  public static class GetMessagesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return(readOnly = false) List<Message> messages) {
      if (messages != null
          && !messages.isEmpty()
          && !(messages instanceof TracingList)
          && !(activeSpan() instanceof AgentTracer.NoopAgentSpan)) {
        messages = new TracingList(messages);
      }
    }
  }
}
