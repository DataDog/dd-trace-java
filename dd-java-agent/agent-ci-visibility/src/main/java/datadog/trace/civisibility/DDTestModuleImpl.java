package datadog.trace.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.context.ParentProcessTestContext;
import datadog.trace.civisibility.context.SpanTestContext;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.SignalClient;
import datadog.trace.civisibility.source.MethodLinesResolver;
import datadog.trace.util.Strings;
import java.net.InetSocketAddress;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDTestModuleImpl implements DDTestModule {

  private static final Logger log = LoggerFactory.getLogger(DDTestModuleImpl.class);

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

  public DDTestModuleImpl(
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

    if (signalServerAddress != null) {
      this.signalServerAddress = signalServerAddress;
    } else {
      String host =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(
                  CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST));
      String port =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(
                  CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT));
      if (host != null && port != null) {
        this.signalServerAddress = new InetSocketAddress(host, Integer.parseInt(port));
      } else {
        this.signalServerAddress = null;
      }
    }

    // fallbacks to System.getProperty below are needed for cases when
    // system variables are set after config was initialized

    Long parentProcessSessionId = config.getCiVisibilitySessionId();
    if (parentProcessSessionId == null) {
      String systemProp =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SESSION_ID));
      if (systemProp != null) {
        parentProcessSessionId = Long.parseLong(systemProp);
      }
    }

    Long parentProcessModuleId = config.getCiVisibilityModuleId();
    if (parentProcessModuleId == null) {
      String systemProp =
          System.getProperty(
              Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_ID));
      if (systemProp != null) {
        parentProcessModuleId = Long.parseLong(systemProp);
      }
    }

    if (parentProcessSessionId != null && parentProcessModuleId != null) {
      // we do not create a local span, because it was created in the parent process
      context = new ParentProcessTestContext(parentProcessSessionId, parentProcessModuleId);
      span = null;

    } else {
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
  }

  @Override
  public void setTag(String key, Object value) {
    if (span == null) {
      log.debug(
          "Ignoring tag {} with value {}: there is no local span for test module", key, value);
      return;
    }
    span.setTag(key, value);
  }

  @Override
  public void setErrorInfo(Throwable error) {
    if (span == null) {
      log.debug("Ignoring error, there is no local span for test module", error);
      return;
    }
    span.setError(true);
    span.addThrowable(error);
    span.setTag(Tags.TEST_STATUS, CIConstants.TEST_FAIL);
  }

  @Override
  public void setSkipReason(String skipReason) {
    if (span == null) {
      log.debug("Ignoring skip reason {}: there is no local span for test module", skipReason);
      return;
    }
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

    if (span == null) {
      // we have no span locally,
      // send execution result to parent process that has the span
      sendModuleExecutionResult(testsSkipped);
      return;
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

  private void sendModuleExecutionResult(boolean testsSkipped) {
    long moduleId = context.getId();
    long sessionId = sessionContext != null ? sessionContext.getId() : context.getParentId();
    boolean coverageEnabled = config.isCiVisibilityCodeCoverageEnabled();
    boolean itrEnabled = config.isCiVisibilityItrEnabled();
    ModuleExecutionResult moduleExecutionResult =
        new ModuleExecutionResult(sessionId, moduleId, coverageEnabled, itrEnabled, testsSkipped);

    try (SignalClient signalClient = new SignalClient(signalServerAddress)) {
      signalClient.send(moduleExecutionResult);
    } catch (Exception e) {
      log.error("Error while reporting module execution result", e);
    }
  }

  @Override
  public DDTestSuiteImpl testSuiteStart(
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
