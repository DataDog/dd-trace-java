package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.JvmInfo;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.context.SpanTestContext;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Representation of a test module in a parent process:
 *
 * <ul>
 *   <li>JVM that runs the build system, if build system is instrumented
 *   <li>JVM that runs the tests, if build system is not instrumented
 * </ul>
 */
public class DDTestModuleParent extends DDTestModuleImpl {

  private final AgentSpan span;
  private final SpanTestContext context;
  @Nullable private final TestContext sessionContext;
  @Nullable private final TestModuleRegistry testModuleRegistry;
  private final ModuleExecutionSettingsFactory moduleExecutionSettingsFactory;

  public DDTestModuleParent(
      @Nullable TestContext sessionContext,
      String moduleName,
      @Nullable Long startTime,
      Config config,
      @Nullable TestModuleRegistry testModuleRegistry,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory,
      @Nullable InetSocketAddress signalServerAddress) {
    super(
        moduleName,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        signalServerAddress);
    this.sessionContext = sessionContext;
    this.testModuleRegistry = testModuleRegistry;
    this.moduleExecutionSettingsFactory = moduleExecutionSettingsFactory;

    AgentSpan sessionSpan = sessionContext != null ? sessionContext.getSpan() : null;
    AgentSpan.Context sessionSpanContext = sessionSpan != null ? sessionSpan.context() : null;

    if (startTime != null) {
      span = startSpan(testDecorator.component() + ".test_module", sessionSpanContext, startTime);
    } else {
      span = startSpan(testDecorator.component() + ".test_module", sessionSpanContext);
    }

    Long sessionId = sessionContext != null ? sessionContext.getId() : null;
    context = new SpanTestContext(span, sessionId);

    span.setSpanType(InternalSpanTypes.TEST_MODULE_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_MODULE);

    span.setResourceName(moduleName);
    span.setTag(Tags.TEST_MODULE, moduleName);

    span.setTag(Tags.TEST_MODULE_ID, context.getId());
    span.setTag(Tags.TEST_SESSION_ID, sessionId);

    if (sessionContext != null) {
      span.setTag(Tags.TEST_STATUS, CIConstants.TEST_PASS);
    }

    testDecorator.afterStart(span);
  }

  @Override
  protected SpanTestContext getContext() {
    return context;
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
    if (testModuleRegistry != null) {
      testModuleRegistry.removeModule(this);
    }

    if (sessionContext != null) {
      sessionContext.reportChildStatus(context.getStatus());
    }
    span.setTag(Tags.TEST_STATUS, context.getStatus());

    if (testsSkipped) {
      setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
    }

    Object testFramework = context.getChildTag(Tags.TEST_FRAMEWORK);
    if (testFramework != null) {
      span.setTag(Tags.TEST_FRAMEWORK, testFramework);
    }
    Object testFrameworkVersion = context.getChildTag(Tags.TEST_FRAMEWORK_VERSION);
    if (testFrameworkVersion != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
    }

    testDecorator.beforeFinish(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }
  }

  @Override
  protected Collection<SkippableTest> fetchSkippableTests() {
    ModuleExecutionSettings moduleExecutionSettings =
        moduleExecutionSettingsFactory.create(JvmInfo.CURRENT_JVM, moduleName);
    Map<String, String> systemProperties = moduleExecutionSettings.getSystemProperties();
    if (propertyEnabled(systemProperties, CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ENABLED)) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }
    if (propertyEnabled(systemProperties, CiVisibilityConfig.CIVISIBILITY_ITR_ENABLED)) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
    }

    return new HashSet<>(moduleExecutionSettings.getSkippableTests(moduleName));
  }

  private boolean propertyEnabled(Map<String, String> systemProperties, String propertyName) {
    String property = systemProperties.get(propertyName);
    return Boolean.parseBoolean(property);
  }
}
