package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.codeowners.Codeowners;
import datadog.trace.civisibility.context.TestContext;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.MethodLinesResolver;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

public abstract class DDTestModuleImpl implements DDTestModule {

  protected final String moduleName;
  protected final Config config;
  protected final TestDecorator testDecorator;
  protected final SourcePathResolver sourcePathResolver;
  protected final Codeowners codeowners;
  protected final MethodLinesResolver methodLinesResolver;
  @Nullable protected final InetSocketAddress signalServerAddress;

  protected final LongAdder testsSkipped = new LongAdder();
  private volatile Collection<SkippableTest> skippableTests;
  private final Object skippableTestsInitLock = new Object();

  protected DDTestModuleImpl(
      String moduleName,
      Config config,
      TestDecorator testDecorator,
      SourcePathResolver sourcePathResolver,
      Codeowners codeowners,
      MethodLinesResolver methodLinesResolver,
      InetSocketAddress signalServerAddress) {
    this.moduleName = moduleName;
    this.config = config;
    this.testDecorator = testDecorator;
    this.sourcePathResolver = sourcePathResolver;
    this.codeowners = codeowners;
    this.methodLinesResolver = methodLinesResolver;
    this.signalServerAddress = signalServerAddress;
  }

  protected abstract TestContext getContext();

  @Override
  public DDTestSuiteImpl testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized) {
    return new DDTestSuiteImpl(
        getContext(),
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

  @Override
  public boolean skip(SkippableTest test) {
    if (test == null) {
      return false;
    }

    if (skippableTests == null) {
      synchronized (skippableTestsInitLock) {
        if (skippableTests == null) {
          skippableTests = fetchSkippableTests();
        }
      }
    }

    if (skippableTests.contains(test)) {
      testsSkipped.increment();
      return true;
    } else {
      return false;
    }
  }

  protected abstract Collection<SkippableTest> fetchSkippableTests();

  public BuildEventsHandler.ModuleInfo getModuleInfo() {
    TestContext context = getContext();
    Long moduleId = context.getId();
    Long sessionId = context.getParentId();
    String signalServerHost =
        signalServerAddress != null ? signalServerAddress.getHostName() : null;
    int signalServerPort = signalServerAddress != null ? signalServerAddress.getPort() : 0;
    return new BuildEventsHandler.ModuleInfo(
        moduleId, sessionId, signalServerHost, signalServerPort);
  }

  public long getId() {
    return getContext().getId();
  }
}
