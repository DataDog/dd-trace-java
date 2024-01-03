package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTestSession;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.coverage.CoverageProbeStoreFactory;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.utils.SpanUtils;
import javax.annotation.Nullable;

public class DDTestSessionImpl implements DDTestSession {
  protected final AgentSpan span;
  protected final Config config;
  protected final TestDecorator testDecorator;
  protected final SourcePathResolver sourcePathResolver;
  protected final Codeowners codeowners;
  protected final MethodLinesResolver methodLinesResolver;
  protected final CoverageProbeStoreFactory coverageProbeStoreFactory;

  public DDTestSessionImpl(
      String projectName,
      @Nullable Long startTime,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      CoverageProbeStoreFactory coverageProbeStoreFactory) {
    this.config = config;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.coverageProbeStoreFactory = coverageProbeStoreFactory;

    if (startTime != null) {
      span = startSpan(testDecorator.component() + ".test_session", startTime);
    } else {
      span = startSpan(testDecorator.component() + ".test_session");
    }

    span.setSpanType(InternalSpanTypes.TEST_SESSION_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_SESSION);
    span.setTag(Tags.TEST_SESSION_ID, span.getSpanId());

    // setting status to skip initially,
    // as we do not know in advance whether the session will have any children
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);

    span.setResourceName(projectName);

    // The backend requires all session spans to have the test command tag
    // because it is used for session fingerprint calculation.
    // We're setting it here to project name as a default that works
    // reasonably well (although this is not the real command).
    // In those cases where proper command can be determined,
    // this tag will be overridden
    span.setTag(Tags.TEST_COMMAND, projectName);

    testDecorator.afterStart(span);
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
    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }

    // flushing written traces synchronously:
    // as soon as build finish event is processed,
    // the process can be killed by the CI provider
    AgentTracer.get().flush();
  }

  @Override
  public DDTestModuleImpl testModuleStart(String moduleName, @Nullable Long startTime) {
    return new DDTestModuleImpl(
        span.context(),
        span.getSpanId(),
        moduleName,
        startTime,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        coverageProbeStoreFactory,
        SpanUtils.propagateCiVisibilityTagsTo(span));
  }
}
