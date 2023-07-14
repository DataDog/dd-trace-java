package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import java.lang.reflect.Method;
import java.util.Collection;
import javax.annotation.Nullable;
import org.objectweb.asm.Type;

public class DDTestImpl implements DDTest {

  private final AgentSpan span;
  private final TestContext suiteContext;
  private final TestContext moduleContext;
  private final TestDecorator testDecorator;

  public DDTestImpl(
      TestContext suiteContext,
      TestContext moduleContext,
      String moduleName,
      String testSuiteName,
      String testName,
      @Nullable Long startTime,
      @Nullable Class<?> testClass,
      @Nullable String testMethodName,
      @Nullable Method testMethod,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      MethodLinesResolver methodLinesResolver,
      Codeowners codeowners) {
    this.suiteContext = suiteContext;
    this.moduleContext = moduleContext;

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(testDecorator.component() + ".test")
            .ignoreActiveSpan()
            .asChildOf(null)
            .withRequestContextData(
                RequestContextSlot.CI_VISIBILITY,
                InstrumentationBridge.createCoverageProbeStore(sourcePathResolver));

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

    Long suiteId = suiteContext.getId();
    Long moduleId = moduleContext.getId();
    Long sessionId = moduleContext.getParentId();

    span.setTag(Tags.TEST_SUITE_ID, suiteId);
    span.setTag(Tags.TEST_MODULE_ID, moduleId);
    span.setTag(Tags.TEST_SESSION_ID, sessionId);

    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_PASS);

    if (testClass != null && !testClass.getName().equals(testSuiteName)) {
      span.setTag(Tags.TEST_SOURCE_CLASS, testClass.getName());
    }
    if (testMethodName != null && testMethod != null) {
      span.setTag(Tags.TEST_SOURCE_METHOD, testMethodName + Type.getMethodDescriptor(testMethod));
    }

    if (config.isCiVisibilitySourceDataEnabled()) {
      populateSourceDataTags(
          span, testClass, testMethod, sourcePathResolver, methodLinesResolver, codeowners);
    }

    this.testDecorator = testDecorator;
    this.testDecorator.afterStart(span);
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
    }
  }

  @Override
  public void end(@Nullable Long endTime) {
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

    CoverageProbeStore probes = span.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY);
    probes.report(moduleContext.getParentId(), suiteContext.getId(), span.getSpanId());

    scope.close();

    String status = (String) span.getTag(Tags.TEST_STATUS);
    suiteContext.reportChildStatus(status);

    testDecorator.beforeFinish(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }
  }
}
