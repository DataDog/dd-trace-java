package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HelpersIndexTest {

  @BeforeAll
  static void setup() {
    // some modules cache matchers in initializers while resolving helpers
    HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks());
    SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache());
  }

  @Test
  void entriesMatchModuleOrderAndResolvedHelpers() throws Exception {
    List<InstrumenterModule> modules =
        InstrumenterIndex.loadModules(HelpersIndex.instrumenterClassLoader);
    HelpersIndex index = HelpersIndex.buildIndex();

    assertTrue(modules.size() > 0, "expected instrumentation modules on the class-path");
    for (int i = 0; i < modules.size(); i++) {
      assertArrayEquals(
          HelpersIndex.resolveHelperClassNames(modules.get(i)),
          index.helperClassNames(i),
          "helpers mismatch for module " + modules.get(i).getClass().getName());
    }
    // ids outside the module range are not indexed.
    assertNull(index.helperClassNames(-1));
    assertNull(index.helperClassNames(modules.size()));
  }
}
