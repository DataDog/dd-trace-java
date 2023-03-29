package datadog.trace.api.civisibility.events.impl;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.Strings;
import javax.annotation.Nullable;

public class DDTestModuleImpl implements DDTestModule {

  private final TestContext context;
  private final AgentSpan span;
  private final TestDecorator testDecorator;

  public DDTestModuleImpl(String moduleName, Config config, TestDecorator testDecorator) {
    this.testDecorator = testDecorator;

    // fallbacks to System.getProperty below are needed for cases when
    // system variables are set after config was initialized

    Long sessionId = config.getCiVisibilitySessionId();
    if (sessionId == null) {
      String systemProp =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
      if (systemProp != null) {
        sessionId = Long.parseLong(systemProp);
      }
    }

    Long moduleId = config.getCiVisibilityModuleId();
    if (moduleId == null) {
      String systemProp =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
      if (systemProp != null) {
        moduleId = Long.parseLong(systemProp);
      }
    }

    if (sessionId != null && moduleId != null) {
      context = new ParentProcessTestContext(sessionId, moduleId);
      span = null;

    } else {
      span = startSpan(testDecorator.component() + ".test_module");
      context = new SpanTestContext(span);

      span.setSpanType(InternalSpanTypes.TEST_MODULE_END);
      span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_MODULE);

      span.setResourceName(moduleName);
      span.setTag(Tags.TEST_MODULE, moduleName);

      span.setTag(Tags.TEST_MODULE_ID, context.getId());

      testDecorator.afterStart(span);
    }
  }

  // FIXME move setTag / setErrorInfo / setSkipReason to a common superclass? (they're the same in
  // test, and probably module/session too)

  @Override
  public void setTag(String key, Object value) {
    if (span == null) {
      return;
    }
    span.setTag(key, value);
  }

  @Override
  public void setErrorInfo(Throwable error) {
    if (span == null) {
      return;
    }
    span.setError(true);
    span.addThrowable(error);
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_FAIL);
  }

  @Override
  public void setSkipReason(String skipReason) {
    if (span == null) {
      return;
    }
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);
    if (skipReason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, skipReason);
    }
  }

  @Override
  public void end(@Nullable Long endTime) {
    if (span == null) {
      return;
    }
    span.setTag(Tags.TEST_STATUS, context.getStatus());
    testDecorator.beforeFinish(span);
    span.finish();
  }

  @Override
  public DDTestSuite testSuiteStart(
      String testSuiteName, @Nullable Class<?> testClass, @Nullable Long startTime) {
    String modulePath = testDecorator.getModulePath();
    SourcePathResolver sourcePathResolver = testDecorator.getSourcePathResolver();
    return new DDTestSuiteImpl(
        context,
        modulePath,
        testSuiteName,
        testClass,
        null,
        Config.get(), // FIXME use config that was supplied in module constructor
        testDecorator,
        sourcePathResolver);
  }
}
