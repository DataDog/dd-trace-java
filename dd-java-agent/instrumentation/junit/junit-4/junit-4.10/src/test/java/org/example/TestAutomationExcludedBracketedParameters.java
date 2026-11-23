package org.example;

import static java.util.Arrays.asList;

import datadog.trace.api.civisibility.NoAutoRetry;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestAutomationExcludedBracketedParameters {
  public static int excludedExecutions;
  public static int controlExecutions;

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> parameters() {
    return asList(new Object[] {"[value]"}, new Object[] {"line\nvalue"});
  }

  public TestAutomationExcludedBracketedParameters(String label) {}

  @Test
  @NoAutoRetry
  public void excluded_failure() {
    excludedExecutions++;
    throw new AssertionError("excluded failure");
  }

  @Test
  public void control_failure() {
    controlExecutions++;
    throw new AssertionError("control failure");
  }
}
