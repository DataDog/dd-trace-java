package datadog.trace.instrumentation.osgi43;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.AgentClassLoading.PROBING_CLASSLOADER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.AgentClassLoading;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.osgi.framework.BundleReference;

@AutoService(Instrumenter.class)
public final class BundleReferenceInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public BundleReferenceInstrumentation() {
    super("classloading", "osgi");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Avoid matching older versions of OSGi that don't have the wiring API.
    return hasClassNamed("org.osgi.framework.wiring.BundleWiring");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.osgi.framework.BundleReference";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("java.lang.ClassLoader"))
        .and(implementsInterface(named(hierarchyMarkerType())));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".BundleWiringHelper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("getResource"))
            .and(takesArguments(1).and(takesArgument(0, String.class))),
        BundleReferenceInstrumentation.class.getName() + "$WidenGetResourceAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("getResourceAsStream"))
            .and(takesArguments(1).and(takesArgument(0, String.class))),
        BundleReferenceInstrumentation.class.getName() + "$WidenGetResourceAsStreamAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("loadClass"))
            .and(
                takesArguments(1)
                    .and(takesArgument(0, String.class))
                    .or(
                        takesArguments(2)
                            .and(takesArgument(0, String.class))
                            .and(takesArgument(1, boolean.class)))),
        BundleReferenceInstrumentation.class.getName() + "$WidenLoadClassAdvice");
  }

  /**
   * Bypass local visibility rules by repeating failed requests from bundles wired as dependencies.
   * Also supports light probing of class-loaders without triggering further resolution of bundles.
   *
   * <p>We only do this for agent requests that require this additional visibility.
   */
  public static class WidenGetResourceAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static Object onEnter(
        @Advice.This final BundleReference thiz, @Advice.Argument(0) final String name) {
      AgentClassLoading requestType = AgentClassLoading.type();
      // avoid probing "java/..." class resources, use standard lookup for them
      if (PROBING_CLASSLOADER == requestType && !name.startsWith("java/")) {
        return BundleWiringHelper.probeResource(thiz.getBundle(), name);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This final BundleReference thiz,
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) URL result,
        @Advice.Thrown(readOnly = false) Throwable error,
        @Advice.Enter final Object resultFromProbe) {
      if (null != resultFromProbe) {
        if (resultFromProbe instanceof URL) {
          result = (URL) resultFromProbe;
        } else {
          // probe returned SKIP_REQUEST
        }
      } else if (null == result) {
        AgentClassLoading requestType = AgentClassLoading.type();
        if (null != requestType) {
          requestType.end(); // avoid looping back into our advice
          try {
            // widen search by peeking inside bundle wiring
            result = BundleWiringHelper.getResource(thiz.getBundle(), name);
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
   * Bypass local visibility rules by repeating failed requests from bundles wired as dependencies.
   *
   * <p>We only do this for agent requests that require this additional visibility.
   */
  public static class WidenGetResourceAsStreamAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This final BundleReference thiz,
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) InputStream result,
        @Advice.Thrown(readOnly = false) Throwable error) {
      if (null == result) {
        AgentClassLoading requestType = AgentClassLoading.type();
        if (null != requestType) {
          requestType.end(); // avoid looping back into our advice
          try {
            // widen search by peeking inside bundle wiring
            URL resource = BundleWiringHelper.getResource(thiz.getBundle(), name);
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
   * Bypass local visibility rules by repeating failed requests from bundles wired as dependencies.
   *
   * <p>We only do this for agent requests that require this additional visibility.
   */
  public static class WidenLoadClassAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This final BundleReference thiz,
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) Class<?> result,
        @Advice.Thrown(readOnly = false) Throwable error) {
      if (null == result) {
        AgentClassLoading requestType = AgentClassLoading.type();
        if (null != requestType) {
          requestType.end(); // avoid looping back into our advice
          try {
            // widen search by peeking inside bundle wiring
            result = BundleWiringHelper.loadClass(thiz.getBundle(), name);
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
}
