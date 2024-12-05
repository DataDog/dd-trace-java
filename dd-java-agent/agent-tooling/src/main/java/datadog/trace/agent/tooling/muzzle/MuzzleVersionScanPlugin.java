package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.InstrumenterModule;
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
import java.util.ServiceLoader;
import java.util.Set;
import net.bytebuddy.dynamic.ClassFileLocator;

/**
 * Entry point for muzzle version scan gradle plugin.
 *
 * <p>For each {@link InstrumenterModule} on the classpath, run muzzle validation and throw an
 * exception if any mismatches are detected.
 *
 * <p>Additionally, after a successful muzzle validation run each module's helper injector.
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
    List<InstrumenterModule> toBeTested = toBeTested(instrumentationLoader, muzzleDirective);
    for (InstrumenterModule module : toBeTested) {

      // verify muzzle result matches expectation
      final ReferenceMatcher muzzle = module.getInstrumentationMuzzle();
      final List<Reference.Mismatch> mismatches =
          muzzle.getMismatchedReferenceSources(testApplicationLoader);

      ClassLoaderMatchers.resetState();

      final boolean classLoaderMatch = module.classLoaderMatcher().matches(testApplicationLoader);

      final boolean passed = mismatches.isEmpty() && classLoaderMatch;
      if (passed && !assertPass) {
        System.err.println(
            "MUZZLE PASSED " + module.getClass().getSimpleName() + " BUT FAILURE WAS EXPECTED");
        throw new RuntimeException("Instrumentation unexpectedly passed Muzzle validation");
      } else if (!passed && assertPass) {
        System.err.println(
            "FAILED MUZZLE VALIDATION: " + module.getClass().getName() + " mismatches:");
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
      for (InstrumenterModule module : toBeTested) {
        try {
          // verify helper injector works
          final String[] helperClassNames = module.helperClassNames();
          if (helperClassNames.length > 0) {
            new HelperInjector(
                    module.useAgentCodeSource(),
                    MuzzleVersionScanPlugin.class.getSimpleName(),
                    createHelperMap(module))
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

  // build modules to test while single-threaded to match installer assumptions
  private static synchronized List<InstrumenterModule> toBeTested(
      ClassLoader instrumentationLoader, String muzzleDirective) {
    List<InstrumenterModule> toBeTested = new ArrayList<>();
    for (InstrumenterModule module :
        ServiceLoader.load(InstrumenterModule.class, instrumentationLoader)) {
      String directiveToTest = module.muzzleDirective();
      if (null == directiveToTest || directiveToTest.equals(muzzleDirective)) {
        // pre-build class-loader matcher while single-threaded
        module.classLoaderMatcher();
        toBeTested.add(module);
      } // this module wants to validate against a different named directive
    }
    return toBeTested;
  }

  private static Map<String, byte[]> createHelperMap(final InstrumenterModule module)
      throws IOException {
    String[] helperClasses = module.helperClassNames();
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
          ClassFileLocator.ForClassLoader.of(module.getClass().getClassLoader());
      final byte[] classBytes = locator.locate(helperName).resolve();
      helperMap.put(helperName, classBytes);
    }
    return helperMap;
  }

  public static void printMuzzleReferences(final ClassLoader instrumentationLoader) {
    for (InstrumenterModule module :
        ServiceLoader.load(InstrumenterModule.class, instrumentationLoader)) {
      final ReferenceMatcher muzzle = module.getInstrumentationMuzzle();
      System.out.println(module.getClass().getName());
      for (final Reference ref : muzzle.getReferences()) {
        System.out.println(prettyPrint("  ", ref));
      }
    }
  }

  public static Set<String> listInstrumentationNames(
      final ClassLoader instrumentationLoader, String directive) {
    final Set<String> ret = new HashSet<>();
    for (final InstrumenterModule module : toBeTested(instrumentationLoader, directive)) {
      ret.add(module.name());
    }
    return ret;
  }

  private static String prettyPrint(final String prefix, final Reference ref) {
    final StringBuilder builder = new StringBuilder(prefix);
    builder.append(Reference.prettyPrint(ref.flags));
    builder.append(ref.className);
    if (ref.superName != null) {
      builder.append(" extends<").append(ref.superName).append('>');
    }
    if (ref.interfaces.length > 0) {
      builder.append(" implements ");
      for (final String iface : ref.interfaces) {
        builder.append(" <").append(iface).append('>');
      }
    }
    for (final String source : ref.sources) {
      builder.append('\n').append(prefix).append(prefix);
      builder.append("Source: ").append(source);
    }
    for (final Reference.Field field : ref.fields) {
      builder.append('\n').append(prefix).append(prefix);
      builder.append("Field: ");
      builder.append(Reference.prettyPrint(field.flags));
      builder.append(field);
    }
    for (final Reference.Method method : ref.methods) {
      builder.append('\n').append(prefix).append(prefix);
      builder.append("Method: ");
      builder.append(Reference.prettyPrint(method.flags));
      builder.append(method);
    }
    return builder.toString();
  }

  private MuzzleVersionScanPlugin() {}
}
