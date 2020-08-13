package datadog.trace.instrumentation.subtrace;

import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Utils;
import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.Consumer;
import datadog.trace.bootstrap.instrumentation.api.Pair;
import datadog.trace.bootstrap.instrumentation.profiler.StackSource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

// TODO: write instrumentation to apply advice to native blocking calls.
// https://github.com/reactor/BlockHound/blob/master/agent/src/main/java/reactor/blockhound/NativeWrappingClassFileTransformer.java
@Slf4j
@AutoService(Instrumenter.class)
public final class SubTraceInstrumentation
    implements Instrumenter, Consumer<Set<Pair<String, String>>> {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    if (Config.get().isIntegrationEnabled(new TreeSet<>(Arrays.asList("subtrace")), true)) {
      StackSource.INSTANCE.addConsumer(this);
    }
    return agentBuilder;
  }

  @Override
  public void accept(final Set<Pair<String, String>> identifiedTargets) {
    AgentBuilder builder = AgentInstaller.getBuilder();
    if (builder == null) {
      return;
    }
    final Map<String, ElementMatcher.Junction<? super MethodDescription>> classMethodsMatchers =
        new HashMap<>();
    for (final Pair<String, String> target : identifiedTargets) {
      final String className = target.getLeft();
      final String methodName = target.getRight();
      final ElementMatcher.Junction<? super MethodDescription> matcher =
          classMethodsMatchers.get(className);

      if (matcher == null) {
        classMethodsMatchers.put(className, named(methodName));
      } else {
        classMethodsMatchers.put(className, matcher.<MethodDescription>or(named(methodName)));
      }
    }
    for (final Map.Entry<String, ElementMatcher.Junction<? super MethodDescription>> entry :
        classMethodsMatchers.entrySet()) {
      builder =
          builder
              .type(named(entry.getKey()))
              .transform(
                  new AgentBuilder.Transformer.ForAdvice()
                      .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
                      .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
                      .advice(
                          entry.getValue(),
                          "datadog.trace.instrumentation.subtrace.SubTraceAdvice"));
    }
    log.info(
        "Applying subtrace advice to {} classes and {} methods",
        classMethodsMatchers.size(),
        identifiedTargets.size());
    AgentInstaller.patch(builder);
  }
}
