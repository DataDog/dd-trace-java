package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.DDCachingPoolStrategy;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import net.bytebuddy.dynamic.ClassFileLocator;

/**
 * Entry point for muzzle version scan gradle plugin.
 *
 * <p>For each instrumenter on the classpath, run muzzle validation and throw an exception if any
 * mismatches are detected.
 *
 * <p>Additionally, after a successful muzzle validation run each instrumenter's helper injector.
 */
public class MuzzleVersionScanPlugin {
  static {
    DDCachingPoolStrategy.registerAsSupplier();
  }

  public static void assertInstrumentationMuzzled(
      final ClassLoader instrumentationLoader,
      final ClassLoader userClassLoader,
      final boolean assertPass)
      throws Exception {
    // muzzle validate all instrumenters
    for (Instrumenter instrumenter :
        ServiceLoader.load(Instrumenter.class, instrumentationLoader)) {
      if (instrumenter.getClass().getName().endsWith("TraceConfigInstrumentation")) {
        // TraceConfigInstrumentation doesn't do muzzle checks
        // check on TracerClassInstrumentation instead
        instrumenter =
            (Instrumenter)
                instrumentationLoader
                    .loadClass(instrumenter.getClass().getName() + "$TracerClassInstrumentation")
                    .getDeclaredConstructor()
                    .newInstance();
      }
      if (!(instrumenter instanceof Instrumenter.Default)) {
        // only default Instrumenters use muzzle. Skip custom instrumenters.
        continue;
      }
      Method m = null;
      try {
        m = instrumenter.getClass().getDeclaredMethod("getInstrumentationMuzzle");
        m.setAccessible(true);
        final IReferenceMatcher muzzle = (IReferenceMatcher) m.invoke(instrumenter);
        final List<Reference.Mismatch> mismatches =
            muzzle.getMismatchedReferenceSources(userClassLoader);

        final boolean classLoaderMatch =
            ((Instrumenter.Default) instrumenter).classLoaderMatcher().matches(userClassLoader);
        final boolean passed = mismatches.isEmpty() && classLoaderMatch;

        if (passed && !assertPass) {
          System.err.println(
              "MUZZLE PASSED "
                  + instrumenter.getClass().getSimpleName()
                  + " BUT FAILURE WAS EXPECTED");
          throw new RuntimeException("Instrumentation unexpectedly passed Muzzle validation");
        } else if (!passed && assertPass) {
          System.err.println(
              "FAILED MUZZLE VALIDATION: " + instrumenter.getClass().getName() + " mismatches:");

          if (!classLoaderMatch) {
            System.err.println("-- classloader mismatch");
          }

          for (final Reference.Mismatch mismatch : mismatches) {
            System.err.println("-- " + mismatch);
          }
          throw new RuntimeException("Instrumentation failed Muzzle validation");
        }
      } finally {
        if (null != m) {
          m.setAccessible(false);
        }
      }
    }
    // run helper injector on all instrumenters
    if (assertPass) {
      for (Instrumenter instrumenter :
          ServiceLoader.load(Instrumenter.class, instrumentationLoader)) {
        if (instrumenter.getClass().getName().endsWith("TraceConfigInstrumentation")) {
          // TraceConfigInstrumentation doesn't do muzzle checks
          // check on TracerClassInstrumentation instead
          instrumenter =
              (Instrumenter)
                  instrumentationLoader
                      .loadClass(instrumenter.getClass().getName() + "$TracerClassInstrumentation")
                      .getDeclaredConstructor()
                      .newInstance();
        }
        if (!(instrumenter instanceof Instrumenter.Default)) {
          // only default Instrumenters use muzzle. Skip custom instrumenters.
          continue;
        }
        final Instrumenter.Default defaultInstrumenter = (Instrumenter.Default) instrumenter;
        try {
          // verify helper injector works
          final String[] helperClassNames = defaultInstrumenter.helperClassNames();
          if (helperClassNames.length > 0) {
            new HelperInjector(
                    MuzzleVersionScanPlugin.class.getSimpleName(),
                    createHelperMap(defaultInstrumenter))
                .transform(null, null, userClassLoader, null);
          }
        } catch (final Exception e) {
          System.err.println(
              "FAILED HELPER INJECTION. Are Helpers being injected in the correct order?");
          System.err.println(e.getMessage());
          throw e;
        }
      }
    }
  }

  private static Map<String, byte[]> createHelperMap(final Instrumenter.Default instrumenter)
      throws IOException {
    String[] helperClasses = instrumenter.helperClassNames();
    final Map<String, byte[]> helperMap = new LinkedHashMap<>(helperClasses.length);
    Set<String> helperClassNames = new HashSet<>(Arrays.asList(helperClasses));
    for (final String helperName : helperClasses) {
      int nestedClassIndex = helperName.lastIndexOf('$');
      if (nestedClassIndex > 0) {
        final int scalaParentIndex = helperName.indexOf("$anonfun$");
        if (scalaParentIndex > 0) {
          nestedClassIndex = scalaParentIndex;
        }
        final String parent = helperName.substring(0, nestedClassIndex);
        if (!helperClassNames.contains(parent)) {
          throw new IllegalArgumentException(
              "Nested helper "
                  + helperName
                  + " must have the parent class "
                  + parent
                  + " also defined as a helper");
        }
      }
      final ClassFileLocator locator =
          ClassFileLocator.ForClassLoader.of(instrumenter.getClass().getClassLoader());
      final byte[] classBytes = locator.locate(helperName).resolve();
      helperMap.put(helperName, classBytes);
    }
    return helperMap;
  }

  public static void printMuzzleReferences(final ClassLoader instrumentationLoader) {
    for (final Instrumenter instrumenter :
        ServiceLoader.load(Instrumenter.class, instrumentationLoader)) {
      if (instrumenter instanceof Instrumenter.Default) {
        try {
          final Method getMuzzleMethod =
              instrumenter.getClass().getDeclaredMethod("getInstrumentationMuzzle");
          final ReferenceMatcher muzzle;
          try {
            getMuzzleMethod.setAccessible(true);
            muzzle = (ReferenceMatcher) getMuzzleMethod.invoke(instrumenter);
          } finally {
            getMuzzleMethod.setAccessible(false);
          }
          System.out.println(instrumenter.getClass().getName());
          for (final Reference ref : muzzle.getReferences()) {
            System.out.println(prettyPrint("  ", ref));
          }
        } catch (final Exception e) {
          System.out.println(
              "Unexpected exception printing references for " + instrumenter.getClass().getName());
          throw new RuntimeException(e);
        }
      } else {
        throw new RuntimeException(
            "class "
                + instrumenter.getClass().getName()
                + " is not a default instrumenter. No refs to print.");
      }
    }
  }

  private static String prettyPrint(final String prefix, final Reference ref) {
    final StringBuilder builder = new StringBuilder(prefix);
    builder.append(Reference.prettyPrint(ref.flags));
    builder.append(ref.className);
    if (ref.superName != null) {
      builder.append(" extends<").append(ref.superName).append(">");
    }
    if (ref.interfaces.length > 0) {
      builder.append(" implements ");
      for (final String iface : ref.interfaces) {
        builder.append(" <").append(iface).append(">");
      }
    }
    for (final String source : ref.sources) {
      builder.append("\n").append(prefix).append(prefix);
      builder.append("Source: ").append(source);
    }
    for (final Reference.Field field : ref.fields) {
      builder.append("\n").append(prefix).append(prefix);
      builder.append("Field: ");
      builder.append(Reference.prettyPrint(field.flags));
      builder.append(field);
    }
    for (final Reference.Method method : ref.methods) {
      builder.append("\n").append(prefix).append(prefix);
      builder.append("Method: ");
      builder.append(Reference.prettyPrint(method.flags));
      builder.append(method);
    }
    return builder.toString();
  }

  private MuzzleVersionScanPlugin() {}
}
