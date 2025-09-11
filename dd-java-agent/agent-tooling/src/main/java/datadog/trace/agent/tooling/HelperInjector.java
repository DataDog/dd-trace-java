package datadog.trace.agent.tooling;

import static datadog.trace.bootstrap.AgentClassLoading.INJECTING_HELPERS;

import datadog.trace.bootstrap.instrumentation.api.EagerHelper;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Injects instrumentation helper classes into the user's classloader. */
public class HelperInjector implements Instrumenter.TransformingAdvice {
  private static final Logger log = LoggerFactory.getLogger(HelperInjector.class);

  private static final ClassFileLocator classFileLocator =
      ClassFileLocator.ForClassLoader.of(Utils.getExtendedClassLoader());

  private final boolean useAgentCodeSource;
  private final AdviceShader adviceShader;
  private final String requestingName;

  private final Set<String> helperClassNames;
  private final Map<String, byte[]> dynamicTypeMap = new LinkedHashMap<>();

  private final Map<ClassLoader, Boolean> injectedClassLoaders =
      Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());

  private final List<WeakReference<Object>> helperModules = new CopyOnWriteArrayList<>();

  /**
   * Construct HelperInjector.
   *
   * @param useAgentCodeSource whether helper classes should be injected with the agent's {@link
   *     CodeSource}.
   * @param helperClassNames binary names of the helper classes to inject. These class names must be
   *     resolvable by the classloader returned by
   *     datadog.trace.agent.tooling.Utils#getAgentClassLoader(). Classes are injected in the order
   *     provided. This is important if there is interdependency between helper classes that
   *     requires them to be injected in a specific order.
   */
  public HelperInjector(
      final boolean useAgentCodeSource,
      final String requestingName,
      final String... helperClassNames) {
    this(useAgentCodeSource, null, requestingName, helperClassNames);
  }

  public HelperInjector(
      final boolean useAgentCodeSource,
      final AdviceShader adviceShader,
      final String requestingName,
      final String... helperClassNames) {
    this.useAgentCodeSource = useAgentCodeSource;
    this.requestingName = requestingName;
    this.adviceShader = adviceShader;

    this.helperClassNames = new LinkedHashSet<>(Arrays.asList(helperClassNames));
  }

  public HelperInjector(
      final boolean useAgentCodeSource,
      final String requestingName,
      final Map<String, byte[]> helperMap) {
    this.useAgentCodeSource = useAgentCodeSource;
    this.requestingName = requestingName;
    this.adviceShader = null;

    helperClassNames = helperMap.keySet();
    dynamicTypeMap.putAll(helperMap);
  }

  private Map<String, byte[]> getHelperMap() throws IOException {
    if (dynamicTypeMap.isEmpty()) {
      final Map<String, byte[]> classnameToBytes = new LinkedHashMap<>();
      for (String helperName : helperClassNames) {
        byte[] classBytes = classFileLocator.locate(helperName).resolve();
        if (adviceShader != null) {
          classBytes = adviceShader.shadeClass(classBytes);
          helperName = adviceShader.uniqueHelper(helperName);
        }
        classnameToBytes.put(helperName, classBytes);
      }

      return classnameToBytes;
    } else {
      return dynamicTypeMap;
    }
  }

  @Override
  public DynamicType.Builder<?> transform(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      ClassLoader classLoader,
      final JavaModule module,
      final ProtectionDomain pd) {
    if (!helperClassNames.isEmpty()) {
      if (classLoader == null) {
        throw new UnsupportedOperationException(
            "Cannot inject helper classes onto boot-class-path; move "
                + String.join(",", helperClassNames)
                + " to agent-bootstrap");
      }

      if (!injectedClassLoaders.containsKey(classLoader)) {
        try {
          if (log.isDebugEnabled()) {
            log.debug(
                "Injecting helper classes - instrumentation.class={} instrumentation.target.classloader={} instrumentation.helper_classes=[{}]",
                requestingName,
                classLoader,
                String.join(",", helperClassNames));
          }

          final Map<String, byte[]> classnameToBytes = getHelperMap();
          final Map<String, Class<?>> classes = injectClassLoader(classLoader, classnameToBytes);

          // All datadog helper classes are in the unnamed module
          // And there's exactly one unnamed module per classloader
          // Use the module of the first class for convenience
          if (JavaModule.isSupported()) {
            final JavaModule javaModule = JavaModule.ofType(classes.values().iterator().next());
            helperModules.add(new WeakReference<>(javaModule.unwrap()));
          }

          // forcibly initialize any eager helpers
          for (Class<?> clazz : classes.values()) {
            if (EagerHelper.class.isAssignableFrom(clazz)) {
              try {
                clazz.getMethod("init").invoke(null);
              } catch (Throwable e) {
                log.debug("Problem initializing {}", clazz, e);
              }
            }
          }

        } catch (final Exception e) {
          if (log.isErrorEnabled()) {
            // requestingName is concatenated to ensure it is sent to telemetry
            log.error(
                "Failed to inject helper classes - instrumentation.class="
                    + requestingName
                    + " instrumentation.target.classloader={} instrumentation.target.class={}",
                classLoader,
                typeDescription,
                e);
          }
          throw new RuntimeException(e);
        }

        injectedClassLoaders.put(classLoader, true);
      }

      ensureModuleCanReadHelperModules(module);
    }
    return builder;
  }

  private Map<String, Class<?>> injectClassLoader(
      final ClassLoader classLoader, final Map<String, byte[]> classnameToBytes) {
    INJECTING_HELPERS.begin();
    try {
      ProtectionDomain protectionDomain = createProtectionDomain(classLoader);
      return new ClassInjector.UsingReflection(classLoader, protectionDomain)
          .injectRaw(classnameToBytes);
    } finally {
      INJECTING_HELPERS.end();
    }
  }

  private ProtectionDomain createProtectionDomain(final ClassLoader classLoader) {
    if (useAgentCodeSource) {
      CodeSource codeSource = HelperInjector.class.getProtectionDomain().getCodeSource();
      return new ProtectionDomain(codeSource, null, classLoader, null);
    } else {
      return ClassLoadingStrategy.NO_PROTECTION_DOMAIN;
    }
  }

  private void ensureModuleCanReadHelperModules(final JavaModule target) {
    if (JavaModule.isSupported() && target != JavaModule.UNSUPPORTED && target.isNamed()) {
      for (final WeakReference<Object> helperModuleReference : helperModules) {
        final Object realModule = helperModuleReference.get();
        if (realModule != null) {
          final JavaModule helperModule = JavaModule.of(realModule);

          if (!target.canRead(helperModule)) {
            log.debug("Adding module read from {} to {}", target, helperModule);
            ClassInjector.UsingInstrumentation.redefineModule(
                Utils.getInstrumentation(),
                target,
                Collections.singleton(helperModule),
                Collections.<String, Set<JavaModule>>emptyMap(),
                Collections.<String, Set<JavaModule>>emptyMap(),
                Collections.<Class<?>>emptySet(),
                Collections.<Class<?>, List<Class<?>>>emptyMap());
          }
        }
      }
    }
  }
}
