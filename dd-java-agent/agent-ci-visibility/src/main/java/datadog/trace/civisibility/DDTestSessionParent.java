package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.JvmInfo;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory;
import datadog.trace.civisibility.context.SpanTestContext;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.ipc.ErrorResponse;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.RepoIndexRequest;
import datadog.trace.civisibility.ipc.RepoIndexResponse;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalServer;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.ipc.SkippableTestsRequest;
import datadog.trace.civisibility.ipc.SkippableTestsResponse;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.civisibility.source.index.RepoIndex;
import datadog.trace.civisibility.source.index.RepoIndexBuilder;
import java.util.Collection;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

public class DDTestSessionParent extends DDTestSessionImpl {

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
  private final RepoIndexBuilder repoIndexBuilder;
  protected final LongAdder testsSkipped = new LongAdder();
  private volatile boolean codeCoverageEnabled;
  private volatile boolean itrEnabled;

  public DDTestSessionParent(
      String projectName,
      @Nullable Long startTime,
      Config config,
      TestModuleRegistry testModuleRegistry,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      ModuleExecutionSettingsFactory moduleExecutionSettingsFactory,
      SignalServer signalServer,
      RepoIndexBuilder repoIndexBuilder) {
    this.config = config;
    this.testModuleRegistry = testModuleRegistry;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.moduleExecutionSettingsFactory = moduleExecutionSettingsFactory;
    this.signalServer = signalServer;
    this.repoIndexBuilder = repoIndexBuilder;

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
    signalServer.registerSignalHandler(
        SignalType.REPO_INDEX_REQUEST, this::onRepoIndexRequestReceived);
    signalServer.registerSignalHandler(
        SignalType.SKIPPABLE_TESTS_REQUEST, this::onSkippableTestsRequestReceived);
    signalServer.start();
  }

  private SignalResponse onModuleExecutionResultReceived(ModuleExecutionResult result) {
    if (result.isCoverageEnabled()) {
      codeCoverageEnabled = true;
    }
    if (result.isItrEnabled()) {
      itrEnabled = true;
    }
    String testFramework = result.getTestFramework();
    if (testFramework != null) {
      setTag(Tags.TEST_FRAMEWORK, testFramework);
    }
    String testFrameworkVersion = result.getTestFrameworkVersion();
    if (testFrameworkVersion != null) {
      setTag(Tags.TEST_FRAMEWORK_VERSION, testFrameworkVersion);
    }

    testsSkipped.add(result.getTestsSkippedTotal());
    return testModuleRegistry.onModuleExecutionResultReceived(result);
  }

  private SignalResponse onRepoIndexRequestReceived(RepoIndexRequest request) {
    try {
      RepoIndex index = repoIndexBuilder.getIndex();
      return new RepoIndexResponse(index);
    } catch (Exception e) {
      return new ErrorResponse("Error while building repo index: " + e.getMessage());
    }
  }

  private SignalResponse onSkippableTestsRequestReceived(
      SkippableTestsRequest skippableTestsRequest) {
    try {
      String relativeModulePath = skippableTestsRequest.getRelativeModulePath();
      JvmInfo jvmInfo = skippableTestsRequest.getJvmInfo();
      ModuleExecutionSettings moduleExecutionSettings = getModuleExecutionSettings(jvmInfo);
      Collection<SkippableTest> tests =
          moduleExecutionSettings.getSkippableTests(relativeModulePath);
      return new SkippableTestsResponse(tests);
    } catch (Exception e) {
      return new ErrorResponse("Error while getting skippable tests: " + e.getMessage());
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
    signalServer.stop();

    String status = context.getStatus();
    span.setTag(Tags.TEST_STATUS, status != null ? status : CIConstants.TEST_SKIP);
    testDecorator.beforeFinish(span);

    if (codeCoverageEnabled) {
      setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }
    if (itrEnabled) {
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_TYPE, "test");

      long testsSkippedTotal = testsSkipped.sum();
      setTag(Tags.TEST_ITR_TESTS_SKIPPING_COUNT, testsSkippedTotal);
      if (testsSkippedTotal > 0) {
        setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
      }
    }

    if (endTime != null) {
      span.finish(endTime);
    } else {
      span.finish();
    }
  }

  @Override
  public DDTestModuleImpl testModuleStart(String moduleName, @Nullable Long startTime) {
    DDTestModuleParent module =
        new DDTestModuleParent(
            context,
            moduleName,
            startTime,
            config,
            testModuleRegistry,
            testDecorator,
            sourcePathResolver,
            codeowners,
            methodLinesResolver,
            moduleExecutionSettingsFactory,
            signalServer.getAddress());
    testModuleRegistry.addModule(module);
    return module;
  }

  @Override
  public ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo) {
    return moduleExecutionSettingsFactory.create(jvmInfo, null);
  }
}
