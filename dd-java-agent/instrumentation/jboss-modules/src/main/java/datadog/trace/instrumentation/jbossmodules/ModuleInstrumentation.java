package datadog.trace.instrumentation.jbossmodules;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.bootstrap.AgentClassLoading;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import net.bytebuddy.asm.Advice;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleLinkageHelper;

@AutoService(InstrumenterModule.class)
public final class ModuleInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ModuleInstrumentation() {
    super("classloading", "jboss-modules");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.modules.Module";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "org.jboss.modules.ModuleLinkageHelper",
      "org.jboss.modules.ModuleLinkageHelper$1",
      "org.jboss.modules.ModuleLinkageHelper$2",
      packageName + ".ModuleNameHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("getResource"))
            .and(takesArguments(1).and(takesArgument(0, String.class))),
        ModuleInstrumentation.class.getName() + "$WidenGetResourceAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("getResourceAsStream"))
            .and(takesArguments(1).and(takesArgument(0, String.class))),
        ModuleInstrumentation.class.getName() + "$WidenGetResourceAsStreamAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("loadModuleClass"))
            .and(
                takesArguments(1)
                    .and(takesArgument(0, String.class))
                    .or(
                        takesArguments(2)
                            .and(takesArgument(0, String.class))
                            .and(takesArgument(1, boolean.class)))),
        ModuleInstrumentation.class.getName() + "$WidenLoadClassAdvice");
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureModuleNameAdvice");
  }

  /**
   * Bypass local visibility rules by repeating failed requests from module linked as dependencies.
   *
   * <p>We only do this for agent requests that require this additional visibility.
   */
  public static class WidenGetResourceAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This final Module module,
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) URL result,
        @Advice.Thrown(readOnly = false) Throwable error) {
      if (null == result) {
        AgentClassLoading requestType = AgentClassLoading.type();
        if (null != requestType) {
          requestType.end(); // avoid looping back into our advice
          try {
            // widen search by peeking inside module linkage
            result = ModuleLinkageHelper.getResource(module, name);
            if (null != result) {
              error = null; // clear any error from original call
            }
          } finally {
            requestType.begin();
          }
        }
      }
    }
  }

  /**
   * Bypass local visibility rules by repeating failed requests from modules linked as dependencies.
   *
   * <p>We only do this for agent requests that require this additional visibility.
   */
  public static class WidenGetResourceAsStreamAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This final Module module,
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) InputStream result,
        @Advice.Thrown(readOnly = false) Throwable error) {
      if (null == result) {
        AgentClassLoading requestType = AgentClassLoading.type();
        if (null != requestType) {
          requestType.end(); // avoid looping back into our advice
          try {
            // widen search by peeking inside module linkage
            URL resource = ModuleLinkageHelper.getResource(module, name);
            if (null != resource) {
              result = resource.openStream();
              error = null; // clear any error from original call
            }
          } catch (IOException e) {
            // ignore missing resource
          } finally {
            requestType.begin();
          }
        }
      }
    }
  }

  /**
   * Bypass local visibility rules by repeating failed requests from modules linked as dependencies.
   *
   * <p>We only do this for agent requests that require this additional visibility.
   */
  public static class WidenLoadClassAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This final Module module,
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) Class<?> result,
        @Advice.Thrown(readOnly = false) Throwable error) {
      if (null == result) {
        AgentClassLoading requestType = AgentClassLoading.type();
        if (null != requestType) {
          requestType.end(); // avoid looping back into our advice
          try {
            // widen search by peeking inside module linkage
            result = ModuleLinkageHelper.loadClass(module, name);
            if (null != result) {
              error = null; // clear any error from original call
            }
          } finally {
            requestType.begin();
          }
        }
      }
    }
  }

  public static class CaptureModuleNameAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstruct(@Advice.This final Module module) {
      final String name = ModuleNameHelper.extractDeploymentName(module.getClassLoader());
      if (name != null && !name.isEmpty()) {
        ClassloaderConfigurationOverrides.withPinnedServiceName(module.getClassLoader(), name);
      }
    }
  }
}
