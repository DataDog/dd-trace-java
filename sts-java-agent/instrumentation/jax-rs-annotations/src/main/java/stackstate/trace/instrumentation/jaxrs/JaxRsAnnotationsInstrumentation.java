package stackstate.trace.instrumentation.jaxrs;

import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedList;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import stackstate.trace.agent.tooling.DDAdvice;
import stackstate.trace.agent.tooling.DDTransformers;
import stackstate.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class JaxRsAnnotationsInstrumentation extends Instrumenter.Configurable {

  public JaxRsAnnotationsInstrumentation() {
    super("jax-rs", "jaxrs", "jax-rs-annotations");
  }

  @Override
  protected AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            hasSuperType(
                isAnnotatedWith(named("javax.ws.rs.Path"))
                    .or(
                        failSafe(
                            hasSuperType(
                                declaresMethod(isAnnotatedWith(named("javax.ws.rs.Path"))))))))
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    isAnnotatedWith(
                        named("javax.ws.rs.Path")
                            .or(named("javax.ws.rs.DELETE"))
                            .or(named("javax.ws.rs.GET"))
                            .or(named("javax.ws.rs.HEAD"))
                            .or(named("javax.ws.rs.OPTIONS"))
                            .or(named("javax.ws.rs.POST"))
                            .or(named("javax.ws.rs.PUT"))),
                    JaxRsAnnotationsAdvice.class.getName()))
        .asDecorator();
  }

  public static class JaxRsAnnotationsAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void nameSpan(@Advice.This final Object obj, @Advice.Origin final Method method) {
      // TODO: do we need caching for this?

      final LinkedList<Path> classPaths = new LinkedList<>();
      Class<?> target = obj.getClass();
      while (target != Object.class) {
        final Path annotation = target.getAnnotation(Path.class);
        if (annotation != null) {
          classPaths.push(annotation);
        }
        target = target.getSuperclass();
      }
      final Path methodPath = method.getAnnotation(Path.class);
      String httpMethod = null;
      for (final Annotation ann : method.getDeclaredAnnotations()) {
        if (ann.annotationType().getAnnotation(HttpMethod.class) != null) {
          httpMethod = ann.annotationType().getSimpleName();
        }
      }

      final StringBuilder resourceNameBuilder = new StringBuilder();
      if (httpMethod != null) {
        resourceNameBuilder.append(httpMethod);
        resourceNameBuilder.append(" ");
      }
      for (final Path classPath : classPaths) {
        resourceNameBuilder.append(classPath.value());
      }
      if (methodPath != null) {
        resourceNameBuilder.append(methodPath.value());
      }
      final String resourceName = resourceNameBuilder.toString().trim();

      final Scope scope = GlobalTracer.get().scopeManager().active();
      if (scope != null && !resourceName.isEmpty()) {
        scope.span().setTag(DDTags.RESOURCE_NAME, resourceName);
        Tags.COMPONENT.set(scope.span(), "jax-rs");
      }
    }
  }
}
