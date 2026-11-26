package datadog.trace.civisibility.source.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.civisibility.coverage.probe.ProbeClassFilter;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackageTreeTest {

  @Test
  void rootPackageCoarsenedAboveAnExcludeLiftsNothing() {
    Config config = mock(Config.class);
    when(config.getCiVisibilityCoverageRootPackagesLimit()).thenReturn(50);
    PackageTree packageTree = new PackageTree(config);
    packageTree.add(Paths.get("org", "mockito"));
    for (int i = 0; i < 50; i++) {
      packageTree.add(Paths.get("org", "example" + i));
    }

    List<String> rootPackages = packageTree.asList();
    ProbeClassFilter filter =
        new ProbeClassFilter(
            Config.convertJacocoExclusionFormatToPackagePrefixes(rootPackages),
            new String[] {"datadog/trace/", "org/apache/commons/", "org/mockito/"},
            true);

    assertEquals(Collections.singletonList("org.*"), rootPackages);
    assertTrue(filter.test("org/example0/Calculator"));
    assertFalse(filter.test("org/mockito/Mockito"));
  }
}
