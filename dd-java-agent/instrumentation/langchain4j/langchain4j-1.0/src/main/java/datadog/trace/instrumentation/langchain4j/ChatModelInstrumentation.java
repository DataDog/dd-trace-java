package datadog.trace.instrumentation.langchain4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.llm.LlmObsHandle;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ChatModelInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "dev.langchain4j.model.chat.ChatModel";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("dev.langchain4j.model.chat.ChatModel"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(net.bytebuddy.matcher.ElementMatchers.named("chat"))
            .and(takesArgument(0, named("dev.langchain4j.model.chat.request.ChatRequest"))),
        ChatModelInstrumentation.class.getName() + "$ChatAdvice");
  }

  public static final class ChatAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(0) ChatRequest request, @Advice.Local("handle") LlmObsHandle handle) {
      if (request == null) return;
      String modelId = request.parameters() != null ? request.parameters().modelName() : null;
      handle = LangChain4jLlmObsIntegration.INSTANCE.startLlm(modelId);
      if (request.messages() != null && !request.messages().isEmpty()) {
        List<LLMObs.LLMMessage> inputs = new ArrayList<>();
        for (ChatMessage msg : request.messages()) {
          switch (msg.type()) {
            case SYSTEM:
              inputs.add(LLMObs.LLMMessage.from("system", ((SystemMessage) msg).text()));
              break;
            case USER:
              UserMessage um = (UserMessage) msg;
              inputs.add(
                  LLMObs.LLMMessage.from(
                      "user", um.hasSingleText() ? um.singleText() : um.toString()));
              break;
            case AI:
              inputs.add(LLMObs.LLMMessage.from("assistant", ((AiMessage) msg).text()));
              break;
            case TOOL_EXECUTION_RESULT:
              inputs.add(LLMObs.LLMMessage.from("tool", ((ToolExecutionResultMessage) msg).text()));
              break;
            default:
              inputs.add(LLMObs.LLMMessage.from("unknown", msg.toString()));
          }
        }
        handle.withInput(inputs);
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(
        @Advice.Local("handle") LlmObsHandle handle,
        @Advice.Return ChatResponse response,
        @Advice.Thrown Throwable err) {
      if (handle == null) return;
      if (response != null) {
        AiMessage aiMsg = response.aiMessage();
        if (aiMsg != null) {
          handle.withOutput(
              Collections.singletonList(LLMObs.LLMMessage.from("assistant", aiMsg.text())));
        }
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
          handle.withTokenMetrics(usage.inputTokenCount(), usage.outputTokenCount());
        }
      }
      if (err != null) handle.withError(err);
      handle.finish();
    }
  }
}
