package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import java.util.Map;
import javax.jms.Message;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class MessageInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public MessageInstrumentation() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.jms.Message";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("javax.jms.Message", SessionState.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        nameStartsWith("acknowledge").and(isMethod()).and(isPublic()).and(takesNoArguments()),
        getClass().getName() + "$Acknowledge");
  }

  public static final class Acknowledge {
    @Advice.OnMethodExit
    public static void acknowledge(@Advice.This Message message) {
      SessionState sessionState =
          InstrumentationContext.get(Message.class, SessionState.class).get(message);
      if (null != sessionState && sessionState.isClientAcknowledge()) {
        sessionState.onAcknowledgeOrRecover();
      }
    }
  }
}
