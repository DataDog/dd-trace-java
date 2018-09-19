package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
@Slf4j
public final class RatpackInstrumentation extends Instrumenter.Default {

  static final String EXEC_NAME = "ratpack";

  static final TypeDescription.Latent ACTION_TYPE_DESCRIPTION =
      new TypeDescription.Latent("ratpack.func.Action", Modifier.PUBLIC, null);

  public RatpackInstrumentation() {
    super(EXEC_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("ratpack.server.internal.ServerRegistry");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      // core helpers
      "datadog.opentracing.scopemanager.ContextualScopeManager",
      "datadog.opentracing.scopemanager.ScopeContext",
      // service registry helpers
      "datadog.trace.instrumentation.ratpack.impl.RatpackRequestExtractAdapter",
      "datadog.trace.instrumentation.ratpack.impl.RatpackScopeManager",
      "datadog.trace.instrumentation.ratpack.impl.RatpackScopeManager$RatpackScope",
      "datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice",
      "datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice$RatpackServerRegistryAdvice",
      "datadog.trace.instrumentation.ratpack.impl.TracingHandler"
    };
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    final Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(isStatic()).and(named("buildBaseRegistry")),
        RatpackServerAdvice.RatpackServerRegistryAdvice.class.getName());
    return transformers;
  }

  @AutoService(Instrumenter.class)
  public static class ExecStarterInstrumentation extends Instrumenter.Default {

    public ExecStarterInstrumentation() {
      super(EXEC_NAME);
    }

    @Override
    protected boolean defaultEnabled() {
      return false;
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return not(isInterface()).and(safeHasSuperType(named("ratpack.exec.ExecStarter")));
    }

    @Override
    public String[] helperClassNames() {
      return new String[] {
        // core helpers
        "datadog.opentracing.scopemanager.ContextualScopeManager",
        "datadog.opentracing.scopemanager.ScopeContext",
        // exec helpers
        "datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice$ExecStarterAdvice",
        "datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice$ExecStarterAction"
      };
    }

    @Override
    public Map<ElementMatcher, String> transformers() {
      final Map<ElementMatcher, String> transformers = new HashMap<>();
      transformers.put(
          named("register").and(takesArguments(ACTION_TYPE_DESCRIPTION)),
          RatpackServerAdvice.ExecStarterAdvice.class.getName());
      return transformers;
    }
  }

  @AutoService(Instrumenter.class)
  public static class ExecutionInstrumentation extends Default {

    public ExecutionInstrumentation() {
      super(EXEC_NAME);
    }

    @Override
    protected boolean defaultEnabled() {
      return false;
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named("ratpack.exec.Execution")
          .or(not(isInterface()).and(safeHasSuperType(named("ratpack.exec.Execution"))));
    }

    @Override
    public String[] helperClassNames() {
      return new String[] {
        // exec helpers
        "datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice$ExecStarterAdvice",
        "datadog.trace.instrumentation.ratpack.impl.RatpackServerAdvice$ExecStarterAction"
      };
    }

    @Override
    public Map<ElementMatcher, String> transformers() {
      final Map<ElementMatcher, String> transformers = new HashMap<>();
      transformers.put(
          named("fork").and(returns(named("ratpack.exec.ExecStarter"))),
          RatpackServerAdvice.ExecutionAdvice.class.getName());
      return transformers;
    }
  }
}
