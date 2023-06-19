package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.DDTestSession;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.context.SpanTestContext;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.source.MethodLinesResolver;
import java.nio.file.Path;
import javax.annotation.Nullable;

public class DDTestSessionImpl implements DDTestSession {

  private final AgentSpan span;
  private final TestContext context;
  private final TestModuleRegistry testModuleRegistry;
  private final Config config;
  private final TestDecorator testDecorator;
  private final SourcePathResolver sourcePathResolver;
  private final Codeowners codeowners;
  private final MethodLinesResolver methodLinesResolver;
  private final ModuleExecutionSettingsFactory moduleExecutionSettingsFactory;
  private final SignalServer signalServer;

  public DDTestSessionImpl(
      String projectName,
      @Nullable Long startTime,
      Config config,
      TestModuleRegistry testModuleRegistry,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory,
      SignalServer signalServer) {
    this.config = config;
    this.testModuleRegistry = testModuleRegistry;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.moduleExecutionSettingsFactory = moduleExecutionSettingsFactory;
    this.signalServer = signalServer;

    if (startTime != null) {
      span = startSpan(testDecorator.component() + ".test_session", startTime);
    } else {
      span = startSpan(testDecorator.component() + ".test_session");
    }

    context = new SpanTestContext(span, null);

    span.setSpanType(InternalSpanTypes.TEST_SESSION_END);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_TEST_SESSION);
    span.setTag(Tags.TEST_SESSION_ID, context.getId());

    span.setResourceName(projectName);

    testDecorator.afterStart(span);

    signalServer.registerSignalHandler(
        SignalType.MODULE_EXECUTION_RESULT, this::onModuleExecutionResultReceived);
    signalServer.start();
  }

  private void onModuleExecutionResultReceived(ModuleExecutionResult result) {
    // We need to set coverage enabled to true on session span
    // if at least one of the children module has it enabled.
    // The same is true for the other flags below
    if (result.isCoverageEnabled()) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }
    if (result.isItrEnabled()) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
    }
    if (result.isItrTestsSkipped()) {
      setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
    }
    testModuleRegistry.onModuleExecutionResultReceived(result);
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
    signalServer.stop();

    String status = context.getStatus();
    span.setTag(Tags.TEST_STATUS, status != null ? status : CIConstants.TEST_SKIP);
    testDecorator.beforeFinish(span);

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }
  }

  @Override
  public DDTestModule testModuleStart(String moduleName, @Nullable Long startTime) {
    DDTestModuleImpl module =
        new DDTestModuleImpl(
            context,
            moduleName,
            startTime,
            config,
            testModuleRegistry,
            testDecorator,
            sourcePathResolver,
            codeowners,
            methodLinesResolver,
            signalServer.getAddress());
    testModuleRegistry.addModule(module);
    return module;
  }

  @Override
  public ModuleExecutionSettings getModuleExecutionSettings(@Nullable Path jvmExecutablePath) {
    return moduleExecutionSettingsFactory.create(jvmExecutablePath);
  }
}
