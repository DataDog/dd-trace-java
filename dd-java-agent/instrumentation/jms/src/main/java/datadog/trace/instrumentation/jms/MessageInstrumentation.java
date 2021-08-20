package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
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
public class MessageInstrumentation extends Instrumenter.Tracing {
  public MessageInstrumentation() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("javax.jms.Message");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return implementsInterface(named("javax.jms.Message"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("javax.jms.Message", SessionState.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("acknowledge").and(isMethod()).and(takesNoArguments()),
        getClass().getName() + "$Acknowledge");
  }

  public static final class Acknowledge {
    @Advice.OnMethodExit
    public static void acknowledge(@Advice.This Message message) {
      SessionState sessionState =
          InstrumentationContext.get(Message.class, SessionState.class).get(message);
      if (null != sessionState && sessionState.isClientAcknowledge()) {
        sessionState.onAcknowledge();
      }
    }
  }
}
