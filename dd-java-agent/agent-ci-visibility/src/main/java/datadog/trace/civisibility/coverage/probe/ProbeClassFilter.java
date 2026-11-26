package datadog.trace.civisibility.coverage.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Decides which classes get line probes: those in an included package and in no excluded package.
 *
 * <p>The default excluded packages name libraries that most repositories only use, such as Mockito.
 * A default excluded package does not apply to the classes of an included package that lies inside
 * it, so that the library's own repository gets probes in its own code. The tracer's package is
 * never lifted this way. Excluded packages that the user set apply to every class.
 *
 * <p>Packages are internal-name prefixes such as {@code org/mockito/}. An empty included package
 * includes every class.
 */
public final class ProbeClassFilter implements Predicate<String> {

  private static final String TRACER_PACKAGE = "datadog/trace/";

  private static final String[] NONE = new String[0];

  private final String[] includedPackages;
  private final String[] excludedPackages;

  /** By excluded package: the included packages inside it, whose classes it does not exclude. */
  private final String[][] exemptPackages;

  /**
   * @param defaultExcludes whether the excluded packages are the default ones, rather than ones the
   *     user set
   */
  public ProbeClassFilter(
      String[] includedPackages, String[] excludedPackages, boolean defaultExcludes) {
    this.includedPackages = includedPackages;
    this.excludedPackages = excludedPackages;
    exemptPackages = new String[excludedPackages.length][];
    for (int i = 0; i < excludedPackages.length; i++) {
      exemptPackages[i] =
          defaultExcludes ? includedPackagesInside(excludedPackages[i], includedPackages) : NONE;
    }
  }

  private static String[] includedPackagesInside(
      String excludedPackage, String[] includedPackages) {
    if (excludedPackage.startsWith(TRACER_PACKAGE) || TRACER_PACKAGE.startsWith(excludedPackage)) {
      return NONE;
    }
    List<String> inside = new ArrayList<>();
    for (String includedPackage : includedPackages) {
      if (includedPackage.startsWith(excludedPackage)) {
        inside.add(includedPackage);
      }
    }
    return inside.toArray(NONE);
  }

  @Override
  public boolean test(String className) {
    if (!className.contains("/")) {
      // always include classes in default package
      return true;
    }
    for (int i = 0; i < excludedPackages.length; i++) {
      if (className.startsWith(excludedPackages[i])
          && !startsWithAny(className, exemptPackages[i])) {
        return false;
      }
    }
    return startsWithAny(className, includedPackages);
  }

  private static boolean startsWithAny(String className, String[] packages) {
    for (String prefix : packages) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
