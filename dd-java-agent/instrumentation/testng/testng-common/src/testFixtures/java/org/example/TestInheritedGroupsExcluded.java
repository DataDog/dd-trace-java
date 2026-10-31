package org.example;

import datadog.trace.api.civisibility.PreventFailureSuppression;
import org.testng.annotations.Test;

@Test(groups = PreventFailureSuppression.TAG)
public class TestInheritedGroupsExcluded extends InheritedGroupsBase {
  public static int executions;
}

class InheritedGroupsBase {
  @Test
  public void inherited_groups() {
    TestInheritedGroupsExcluded.executions++;
  }
}
