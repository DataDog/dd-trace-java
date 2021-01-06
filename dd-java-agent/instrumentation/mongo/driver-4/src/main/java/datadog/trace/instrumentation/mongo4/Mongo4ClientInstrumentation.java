package datadog.trace.instrumentation.mongo4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.declaresField;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.mongodb.MongoClientSettings;
import com.mongodb.event.CommandListener;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class Mongo4ClientInstrumentation extends Instrumenter.Tracing {

  public Mongo4ClientInstrumentation() {
    super("mongo");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.mongodb.MongoClientSettings$Builder")
        .and(declaresField(named("commandListeners")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Mongo4ClientDecorator", packageName + ".Tracing4CommandListener"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(isPublic()).and(named("build")).and(takesArguments(0)),
        Mongo4ClientInstrumentation.class.getName() + "$Mongo4ClientAdvice");
  }

  public static class Mongo4ClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Tracing4CommandListener injectTraceListener(
        @Advice.FieldValue("commandListeners") List<CommandListener> listeners) {
      if (!listeners.isEmpty()
          && listeners.get(listeners.size() - 1).getClass().getName().startsWith("datadog.")) {
        // we'll replace it, since it could be the 3.x async driver which is bundled with driver 4
        listeners.remove(listeners.size() - 1);
      }
      Tracing4CommandListener listener = new Tracing4CommandListener();
      listeners.add(listener);
      return listener;
    }

    @Advice.OnMethodExit
    public static void injectApplicationName(
        @Advice.Enter final Tracing4CommandListener listener,
        @Advice.Return final MongoClientSettings settings) {
      // record this clients application name so we can apply it to command spans
      if (null != listener) {
        listener.setApplicationName(settings.getApplicationName());
      }
    }
  }
}
