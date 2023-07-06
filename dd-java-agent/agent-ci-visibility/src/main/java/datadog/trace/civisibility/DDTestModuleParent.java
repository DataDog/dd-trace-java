package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.context.SpanTestContext;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import java.net.InetSocketAddress;
import javax.annotation.Nullable;

/** Representation of a test module in a parent process (JVM that runs the build system) */
public class DDTestModuleParent implements DDTestModule {

  private final String moduleName;
  private final AgentSpan span;
  private final TestContext context;
  @Nullable private final TestContext sessionContext;
  private final Config config;
  private final TestDecorator testDecorator;
  @Nullable private final TestModuleRegistry testModuleRegistry;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  @Nullable private final InetSocketAddress signalServerAddress;

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
      @Nullable InetSocketAddress signalServerAddress) {
    this.sessionContext = sessionContext;
    this.moduleName = moduleName;
    this.config = config;
    this.testModuleRegistry = testModuleRegistry;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.signalServerAddress = signalServerAddress;

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
  public void end(@Nullable Long endTime, boolean testsSkipped) {
    if (testModuleRegistry != null) {
      testModuleRegistry.removeModule(this);
    }

    if (sessionContext != null) {
      sessionContext.reportChildStatus(context.getStatus());
    }
    span.setTag(Tags.TEST_STATUS, context.getStatus());
    testDecorator.beforeFinish(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }
  }

  @Override
  public DDTestSuite testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized) {
    return new DDTestSuiteImpl(
        context,
        moduleName,
        testSuiteName,
        testClass,
        startTime,
        config,
        testDecorator,
        sourcePathResolver,
        codeowners,
        methodLinesResolver,
        parallelized);
  }

  public BuildEventsHandler.ModuleInfo getModuleInfo() {
    Long moduleId = context.getId();
    Long sessionId = sessionContext != null ? sessionContext.getId() : context.getParentId();
    String signalServerHost =
        signalServerAddress != null ? signalServerAddress.getHostName() : null;
    int signalServerPort = signalServerAddress != null ? signalServerAddress.getPort() : 0;
    return new BuildEventsHandler.ModuleInfo(
        moduleId, sessionId, signalServerHost, signalServerPort);
  }

  long getId() {
    return context.getId();
  }
}
