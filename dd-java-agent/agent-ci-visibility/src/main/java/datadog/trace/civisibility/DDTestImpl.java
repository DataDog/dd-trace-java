package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.coverage.CoverageBridge;
import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDTestImpl implements DDTest {

  private static final Logger log = LoggerFactory.getLogger(DDTestImpl.class);

  private final AgentSpan span;
  private final long sessionId;
  private final long suiteId;
  private final Consumer<AgentSpan> onSpanFinish;

  public DDTestImpl(
      long sessionId,
      long moduleId,
      long suiteId,
      String moduleName,
      String testSuiteName,
      String testName,
      @Nullable Long startTime,
      @Nullable Class<?> testClass,
      @Nullable Method testMethod,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      MethodLinesResolver methodLinesResolver,
      Codeowners codeowners,
      CoverageProbeStoreFactory coverageProbeStoreFactory,
      Consumer<AgentSpan> onSpanFinish) {
    this.sessionId = sessionId;
    this.suiteId = suiteId;
    this.onSpanFinish = onSpanFinish;

    CoverageProbeStore probeStore = coverageProbeStoreFactory.create(sourcePathResolver);
    CoverageBridge.setThreadLocalCoverageProbeStore(probeStore);

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(testDecorator.component() + ".test")
            .ignoreActiveSpan()
            .asChildOf(null)
            .withRequestContextData(RequestContextSlot.CI_VISIBILITY, probeStore);

    if (startTime != null) {
      spanBuilder = spanBuilder.withStartTimestamp(startTime);
    }

    span = spanBuilder.start();

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    span.setSpanType(InternalSpanTypes.TEST);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST);

    span.setResourceName(testSuiteName + "." + testName);
    span.setTag(Tags.TEST_NAME, testName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, moduleName);

    span.setTag(Tags.TEST_SUITE_ID, suiteId);
    span.setTag(Tags.TEST_MODULE_ID, moduleId);
    span.setTag(Tags.TEST_SESSION_ID, sessionId);

    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_PASS);

    if (testClass != null && !testClass.getName().equals(testSuiteName)) {
      span.setTag(Tags.TEST_SOURCE_CLASS, testClass.getName());
    }

    if (config.isCiVisibilitySourceDataEnabled()) {
      populateSourceDataTags(
          span, testClass, testMethod, sourcePathResolver, methodLinesResolver, codeowners);
    }

    testDecorator.afterStart(span);
  }

  private void populateSourceDataTags(
      AgentSpan span,
      Class<?> testClass,
      Method testMethod,
      SourcePathResolver sourcePathResolver,
      MethodLinesResolver methodLinesResolver,
      Codeowners codeowners) {
    if (testClass == null) {
      return;
    }

    String sourcePath = sourcePathResolver.getSourcePath(testClass);
    if (sourcePath == null || sourcePath.isEmpty()) {
      return;
    }

    span.setTag(Tags.TEST_SOURCE_FILE, sourcePath);

    if (testMethod != null) {
      MethodLinesResolver.MethodLines testMethodLines = methodLinesResolver.getLines(testMethod);
      if (testMethodLines.isValid()) {
        span.setTag(Tags.TEST_SOURCE_START, testMethodLines.getStartLineNumber());
        span.setTag(Tags.TEST_SOURCE_END, testMethodLines.getFinishLineNumber());
      }
    }

    Collection<String> testCodeOwners = codeowners.getOwners(sourcePath);
    if (testCodeOwners != null) {
      span.setTag(Tags.TEST_CODEOWNERS, toJson(testCodeOwners));
    }
  }

  @Override
  public void setTag(String key, Object value) {
    span.setTag(key, value);
  }

  @Override
  public void setErrorInfo(Throwable error) {
    span.setError(true);
    span.addThrowable(error);
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_FAIL);
  }

  @Override
  public void setSkipReason(String skipReason) {
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);
    if (skipReason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, skipReason);
      if (skipReason.equals(InstrumentationBridge.ITR_SKIP_REASON)) {
        span.setTag(Tags.TEST_SKIPPED_BY_ITR, true);
      }
    }
  }

  @Override
  public void end(@Nullable Long endTime) {
    closeOutstandingSpans();

    final AgentScope scope = AgentTracer.activeScope();
    if (scope == null) {
      throw new IllegalStateException(
          "No active scope present, it is possible that end() was called multiple times");
    }

    AgentSpan scopeSpan = scope.span();
    if (scopeSpan != span) {
      throw new IllegalStateException(
          "Active scope does not correspond to the finished test, "
              + "it is possible that end() was called multiple times "
              + "or an operation that was started by the test is still in progress; "
              + "active scope span is: "
              + scopeSpan
              + "; "
              + "expected span is: "
              + span);
    }

    CoverageBridge.removeThreadLocalCoverageProbeStore();
    CoverageProbeStore probes = span.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY);
    probes.report(sessionId, suiteId, span.getSpanId());

    scope.close();

    onSpanFinish.accept(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }
  }

  /**
   * Tests often perform operations that involve APM instrumentations: sending an HTTP request,
   * executing a database query, etc. APM instrumentations create spans that correspond to those
   * operations. Ideally, the instrumentations close these spans once their corresponding operations
   * are finished.
   *
   * <p>However, this is not always the case, especially with tests: developers sometimes feel like
   * proper resources disposal, such as closing a connection, is not obligatory in tests code. Not
   * finalizing an operation properly usually results in its span remaining open. This is something
   * that we have no control over, since this happens in the clients' codebase.
   *
   * <p>This method attempts to finalize such "dangling" spans: it closes whatever is on top of the
   * spans stack until it encounters a CI Visibility span or the stack is empty.
   */
  private void closeOutstandingSpans() {
    AgentScope scope;
    while ((scope = AgentTracer.activeScope()) != null) {
      AgentSpan span = scope.span();

      if (TestDecorator.TEST_TYPE.equals(span.getTag(Tags.TEST_TYPE))) {
        // encountered a CI Visibility span (test, suite, module, session)
        break;
      }

      log.debug("Closing outstanding span: {}", span);
      scope.close();
      span.finish();
    }
  }
}
