package org.example;

import datadog.trace.api.civisibility.TestAutomationExclusions;
import org.testng.annotations.Test;

@Test(groups = TestAutomationExclusions.PREVENT_FAILURE_SUPPRESSION)
public class TestInheritedGroupsExcluded extends InheritedGroupsBase {
  public static int executions;
}

class InheritedGroupsBase {
  @Test
  public void inherited_groups() {
    TestInheritedGroupsExcluded.executions++;
  }
}
