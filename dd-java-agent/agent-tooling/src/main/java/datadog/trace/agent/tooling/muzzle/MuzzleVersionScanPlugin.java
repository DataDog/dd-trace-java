package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenters;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache());
    HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks());
  }

  public static void assertInstrumentationMuzzled(
      final ClassLoader instrumentationLoader,
      final ClassLoader testApplicationLoader,
      final boolean assertPass,
      final String muzzleDirective)
      throws Exception {
    List<Instrumenter.Default> toBeTested = toBeTested(instrumentationLoader, muzzleDirective);
    for (Instrumenter.Default instrumenter : toBeTested) {

      // verify muzzle result matches expectation
      final ReferenceMatcher muzzle = instrumenter.getInstrumentationMuzzle();
      final List<Reference.Mismatch> mismatches =
          muzzle.getMismatchedReferenceSources(testApplicationLoader);

      ClassLoaderMatchers.resetState();

      final boolean classLoaderMatch =
          instrumenter.classLoaderMatcher().matches(testApplicationLoader);

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
    }

    if (assertPass) {
      for (Instrumenter.Default instrumenter : toBeTested) {
        try {
          // verify helper injector works
          final String[] helperClassNames = instrumenter.helperClassNames();
          if (helperClassNames.length > 0) {
            new HelperInjector(
                    MuzzleVersionScanPlugin.class.getSimpleName(), createHelperMap(instrumenter))
                .transform(null, null, testApplicationLoader, null, null);
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

  // build instrumenters while single-threaded to match installer assumptions
  private static synchronized List<Instrumenter.Default> toBeTested(
      ClassLoader instrumentationLoader, String muzzleDirective) throws Exception {
    List<Instrumenter.Default> toBeTested = new ArrayList<>();
    for (Instrumenter instrumenter : Instrumenters.load(instrumentationLoader)) {
      if (instrumenter.getClass().getName().endsWith("TraceConfigInstrumentation")) {
        // special handling to test TraceConfigInstrumentation's inner instrumenter
        instrumenter =
            (Instrumenter)
                instrumentationLoader
                    .loadClass(instrumenter.getClass().getName() + "$TracerClassInstrumentation")
                    .getDeclaredConstructor()
                    .newInstance();
      }
      // only default Instrumenters use muzzle. Skip custom instrumenters.
      if (instrumenter instanceof Instrumenter.Default) {
        String directiveToTest = ((Instrumenter.Default) instrumenter).muzzleDirective();
        if (null == directiveToTest || directiveToTest.equals(muzzleDirective)) {
          // pre-build class-loader matcher while single-threaded
          ((Instrumenter.Default) instrumenter).classLoaderMatcher();
          toBeTested.add((Instrumenter.Default) instrumenter);
        } // instrumenter wants to validate against a different named directive
      }
    }
    return toBeTested;
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
    for (final Instrumenter instrumenter : Instrumenters.load(instrumentationLoader)) {
      if (instrumenter instanceof Instrumenter.Default) {
        final ReferenceMatcher muzzle =
            ((Instrumenter.Default) instrumenter).getInstrumentationMuzzle();
        System.out.println(instrumenter.getClass().getName());
        for (final Reference ref : muzzle.getReferences()) {
          System.out.println(prettyPrint("  ", ref));
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
