package datadog.trace.api.civisibility.config;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.NoAutoRetry;
import datadog.trace.api.civisibility.PreventFailureSuppression;
import java.util.Collection;
import org.junit.jupiter.api.Test;

class TestExclusionsTest {
  @Test
  void resolvesIndependentMethodDeclarations() throws Exception {
    TestSourceData retry = source("noAutoRetry");
    assertTrue(TestExclusions.noAutoRetry(retry, null));
    assertFalse(TestExclusions.preventFailureSuppression(retry, emptyList()));

    TestSourceData suppression = source("preventFailureSuppression");
    assertTrue(TestExclusions.preventFailureSuppression(suppression, null));
    assertFalse(TestExclusions.noAutoRetry(suppression, emptyList()));
  }

  @Test
  void resolvesTagsWithoutMethodMetadata() {
    Collection<String> retry = singletonList("datadog_no_auto_retry");
    assertTrue(TestExclusions.noAutoRetry(TestSourceData.UNKNOWN, retry));
    assertFalse(TestExclusions.preventFailureSuppression(TestSourceData.UNKNOWN, retry));

    Collection<String> suppression = singletonList("datadog_prevent_failure_suppression");
    assertTrue(TestExclusions.preventFailureSuppression(TestSourceData.UNKNOWN, suppression));
    assertFalse(TestExclusions.noAutoRetry(TestSourceData.UNKNOWN, suppression));
    assertFalse(TestExclusions.noAutoRetry(TestSourceData.UNKNOWN, null));
    assertFalse(TestExclusions.preventFailureSuppression(TestSourceData.UNKNOWN, emptyList()));
  }

  @Test
  void ignoresUnrelatedAnnotationsAndSimilarTags() throws Exception {
    TestSourceData source = source("unrelated");
    assertFalse(TestExclusions.noAutoRetry(source, singletonList("datadog_no_auto_retry_extra")));
    assertFalse(
        TestExclusions.preventFailureSuppression(
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
