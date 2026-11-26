package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProbeClassFilterTest {

  /** The default excludes, {@code datadog.trace.*:org.apache.commons.*:org.mockito.*}. */
  private static final String[] DEFAULT_EXCLUDES = {
    "datadog/trace/", "org/apache/commons/", "org/mockito/"
  };

  @Test
  void rootPackageThatIsAnExcludeIsCovered() {
    // the Mockito repository
    ProbeClassFilter filter = withDefaultExcludes("org/mockito/", "org/mockitousage/");

    assertTrue(filter.test("org/mockito/internal/creation/bytebuddy/InlineBytecodeGenerator"));
    assertTrue(filter.test("org/mockitousage/basicapi/MocksCreationTest"));
  }

  @Test
  void rootPackageInsideAnExcludeIsCoveredAndTheRestStaysExcluded() {
    // the commons-lang repository
    ProbeClassFilter filter = withDefaultExcludes("org/apache/commons/lang3/");

    assertTrue(filter.test("org/apache/commons/lang3/StringUtils"));
    assertFalse(filter.test("org/apache/commons/text/StringSubstitutor"));
  }

  @Test
  void librariesStayExcludedInOtherRepositories() {
    ProbeClassFilter filter = withDefaultExcludes("com/example/");

    assertTrue(filter.test("com/example/Calculator"));
    assertFalse(filter.test("org/mockito/Mockito"));
    assertFalse(filter.test("org/apache/commons/lang3/StringUtils"));
  }

  @Test
  void rootPackageAboveAnExcludeLiftsNothing() {
    // a root coarsened to fit the root package limit
    ProbeClassFilter filter = withDefaultExcludes("org/");

    assertTrue(filter.test("org/example/Calculator"));
    assertFalse(filter.test("org/mockito/Mockito"));
    assertFalse(filter.test("org/apache/commons/lang3/StringUtils"));
  }

  @Test
  void vendoredCopyInAnExcludedPackageIsCovered() {
    // the repository index makes the package of a copied library's sources a root package
    ProbeClassFilter filter = withDefaultExcludes("com/example/", "org/apache/commons/foo/");

    assertTrue(filter.test("org/apache/commons/foo/Bar"));
    assertFalse(filter.test("org/apache/commons/lang3/StringUtils"));
    assertFalse(filter.test("org/mockito/Mockito"));
  }

  @Test
  void noRootPackagesLiftNothing() {
    // the empty prefix that covers every class when a repository, like the tracer's own, has more
    // top-level packages than the root package limit
    ProbeClassFilter filter = withDefaultExcludes("");

    assertTrue(filter.test("com/example/Calculator"));
    assertFalse(filter.test("datadog/trace/core/CoreTracer"));
    assertFalse(filter.test("org/apache/commons/lang3/StringUtils"));
    assertFalse(filter.test("org/mockito/Mockito"));
  }

  @Test
  void tracerPackagesAreNeverCovered() {
    ProbeClassFilter filter = withDefaultExcludes("datadog/trace/civisibility/", "datadog/");

    assertFalse(filter.test("datadog/trace/civisibility/CiVisibilitySystem"));
    assertFalse(filter.test("datadog/trace/api/Config"));
    assertTrue(filter.test("datadog/other/Example"));
  }

  @Test
  void userExcludesReplaceTheDefaults() {
    // DD_CIVISIBILITY_CODE_COVERAGE_EXCLUDES=datadog.trace.*
    ProbeClassFilter filter =
        new ProbeClassFilter(new String[] {"org/mockito/"}, new String[] {"datadog/trace/"}, false);

    assertTrue(filter.test("org/mockito/Mockito"));
    assertFalse(filter.test("datadog/trace/api/Config"));
  }

  @Test
  void userExcludesApplyToRootPackagesInsideThem() {
    ProbeClassFilter filter =
        new ProbeClassFilter(
            new String[] {"org/mockito/", "org/mockitousage/"},
            new String[] {"org/mockito/"},
            false);

    assertFalse(filter.test("org/mockito/Mockito"));
    assertTrue(filter.test("org/mockitousage/basicapi/MocksCreationTest"));
  }

  @Test
  void excludesApplyToUserIncludesTheSameWay() {
    // includes that the user set replace the root packages
    ProbeClassFilter filter = withDefaultExcludes("org/", "org/apache/commons/lang3/");

    assertTrue(filter.test("org/apache/commons/lang3/StringUtils"));
    assertFalse(filter.test("org/apache/commons/text/StringSubstitutor"));
    assertFalse(filter.test("org/mockito/Mockito"));
    assertTrue(filter.test("org/example/Calculator"));
  }

  @Test
  void classesInTheDefaultPackageAreCovered() {
    assertTrue(withDefaultExcludes("com/example/").test("Calculator"));
  }

  private static ProbeClassFilter withDefaultExcludes(String... includedPackages) {
    return new ProbeClassFilter(includedPackages, DEFAULT_EXCLUDES, true);
  }
}
