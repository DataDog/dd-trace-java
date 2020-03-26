package datadog.trace.instrumentation.mongo;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.mongodb.MongoClientOptions;
import datadog.trace.agent.tooling.Instrumenter;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class MongoClientInstrumentation extends Instrumenter.Default {

  public MongoClientInstrumentation() {
    super("mongo");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.mongodb.MongoClientOptions$Builder")
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
      packageName + ".MongoClientDecorator", packageName + ".TracingCommandListener"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(isPublic()).and(named("build")).and(takesArguments(0)),
        MongoClientInstrumentation.class.getName() + "$MongoClientAdvice");
  }

  public static class MongoClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectTraceListener(@Advice.This final Object dis) {
      // referencing "this" in the method args causes the class to load under a transformer.
      // This bypasses the Builder instrumentation. Casting as a workaround.
      final MongoClientOptions.Builder builder = (MongoClientOptions.Builder) dis;
      final TracingCommandListener listener = new TracingCommandListener();
      builder.addCommandListener(listener);
    }
  }
}
