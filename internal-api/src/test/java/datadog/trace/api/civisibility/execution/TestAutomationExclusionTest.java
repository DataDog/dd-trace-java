package datadog.trace.api.civisibility.execution;

import static datadog.trace.api.civisibility.execution.TestAutomationExclusion.NO_AUTO_RETRY;
import static datadog.trace.api.civisibility.execution.TestAutomationExclusion.PREVENT_FAILURE_SUPPRESSION;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.NoAutoRetry;
import datadog.trace.api.civisibility.PreventFailureSuppression;
import datadog.trace.api.civisibility.config.TestSourceData;
import java.util.Collection;
import org.junit.jupiter.api.Test;

class TestAutomationExclusionTest {
  @Test
  void resolvesIndependentMethodDeclarations() throws Exception {
    TestSourceData retry = source("noAutoRetry");
    assertTrue(NO_AUTO_RETRY.isDeclared(retry, null));
    assertFalse(PREVENT_FAILURE_SUPPRESSION.isDeclared(retry, emptyList()));

    TestSourceData suppression = source("preventFailureSuppression");
    assertTrue(PREVENT_FAILURE_SUPPRESSION.isDeclared(suppression, null));
    assertFalse(NO_AUTO_RETRY.isDeclared(suppression, emptyList()));
  }

  @Test
  void resolvesTagsWithoutMethodMetadata() {
    Collection<String> retry = singletonList(NoAutoRetry.TAG);
    assertTrue(NO_AUTO_RETRY.isDeclared(TestSourceData.UNKNOWN, retry));
    assertFalse(PREVENT_FAILURE_SUPPRESSION.isDeclared(TestSourceData.UNKNOWN, retry));

    Collection<String> suppression = singletonList(PreventFailureSuppression.TAG);
    assertTrue(PREVENT_FAILURE_SUPPRESSION.isDeclared(TestSourceData.UNKNOWN, suppression));
    assertFalse(NO_AUTO_RETRY.isDeclared(TestSourceData.UNKNOWN, suppression));
    assertFalse(NO_AUTO_RETRY.isDeclared(TestSourceData.UNKNOWN, null));
    assertFalse(PREVENT_FAILURE_SUPPRESSION.isDeclared(TestSourceData.UNKNOWN, emptyList()));
  }

  @Test
  void ignoresUnrelatedAnnotationsAndSimilarTags() throws Exception {
    TestSourceData source = source("unrelated");
    assertFalse(NO_AUTO_RETRY.isDeclared(source, singletonList("datadog_no_auto_retry_extra")));
    assertFalse(
        PREVENT_FAILURE_SUPPRESSION.isDeclared(
            source, singletonList("prevent_failure_suppression")));
  }

  private static TestSourceData source(String name) throws Exception {
    return new TestSourceData(Declarations.class, Declarations.class.getDeclaredMethod(name));
  }

  private static class Declarations {
    @NoAutoRetry
    void noAutoRetry() {}

    @PreventFailureSuppression
    void preventFailureSuppression() {}

    @Deprecated
    void unrelated() {}
  }
}
