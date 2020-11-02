package datadog.trace.instrumentation.mongo4;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.mongodb.MongoClientSettings;
import datadog.trace.agent.tooling.Instrumenter;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class Mongo4ClientInstrumentation extends Instrumenter.Default {

  public Mongo4ClientInstrumentation() {
    super("mongo");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.mongodb.MongoClientSettings$Builder")
        .and(
            declaresMethod(
                named("addCommandListener")
                    .and(
                        takesArguments(
                            new TypeDescription.Latent(
                                "com.mongodb.event.CommandListener",
                                Modifier.PUBLIC,
                                null,
                                Collections.<TypeDescription.Generic>emptyList())))
                    .and(isPublic())));
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
    public static Tracing4CommandListener injectTraceListener(@Advice.This final Object dis) {
      // referencing "this" in the method args causes the class to load under a transformer.
      // This bypasses the Builder instrumentation. Casting as a workaround.
      final MongoClientSettings.Builder builder = (MongoClientSettings.Builder) dis;
      final Tracing4CommandListener listener = new Tracing4CommandListener();
      builder.addCommandListener(listener);
      return listener;
    }

    @Advice.OnMethodExit
    public static void injectApplicationName(
        @Advice.Enter final Tracing4CommandListener listener,
        @Advice.Return final MongoClientSettings settings) {
      // record this clients application name so we can apply it to command spans
      listener.setApplicationName(settings.getApplicationName());
    }
  }
}
