package datadog.trace.civisibility.events;

import datadog.trace.api.civisibility.CIVisibility;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.DDTestSession;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.DDTestModuleImpl;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BuildEventsHandlerImpl<T> implements BuildEventsHandler<T> {

  private final ConcurrentMap<T, DDTestSession> inProgressTestSessions = new ConcurrentHashMap<>();

  private final ConcurrentMap<TestModuleDescriptor<T>, DDTestModule> inProgressTestModules =
      new ConcurrentHashMap<>();

  @Override
  public void onTestSessionStart(
      final T sessionKey,
      final String projectName,
      final Path projectRoot,
      final String startCommand,
      final String buildSystemName,
      final String buildSystemVersion) {
    DDTestSession testSession =
        CIVisibility.startSession(projectName, projectRoot, buildSystemName, null);
    testSession.setTag(Tags.TEST_COMMAND, startCommand);
    testSession.setTag(Tags.TEST_TOOLCHAIN, buildSystemName + ":" + buildSystemVersion);
    inProgressTestSessions.put(sessionKey, testSession);
  }

  @Override
  public void onTestFrameworkDetected(
      final T sessionKey, final String frameworkName, final String frameworkVersion) {
    DDTestSession testSession = getTestSession(sessionKey);
    testSession.setTag(Tags.TEST_FRAMEWORK, frameworkName);
    testSession.setTag(Tags.TEST_FRAMEWORK_VERSION, frameworkVersion);
  }

  @Override
  public void onTestSessionFail(final T sessionKey, final Throwable throwable) {
    DDTestSession testSession = getTestSession(sessionKey);
    testSession.setErrorInfo(throwable);
  }

  private DDTestSession getTestSession(T sessionKey) {
    DDTestSession testSession = inProgressTestSessions.get(sessionKey);
    if (testSession == null) {
      throw new IllegalStateException("Could not find session span for key: " + sessionKey);
    }
    return testSession;
  }

  @Override
  public void onTestSessionFinish(final T sessionKey) {
    DDTestSession testSession = inProgressTestSessions.remove(sessionKey);
    testSession.end(null);
  }

  @Override
  public ModuleInfo onTestModuleStart(
      final T sessionKey,
      final String moduleName,
      String startCommand,
      Map<String, Object> additionalTags) {

    DDTestSession testSession = inProgressTestSessions.get(sessionKey);
    DDTestModule testModule = testSession.testModuleStart(moduleName, null);
    testModule.setTag(Tags.TEST_COMMAND, startCommand);

    if (additionalTags != null) {
      for (Map.Entry<String, Object> e : additionalTags.entrySet()) {
        String tag = e.getKey();
        Object value = e.getValue();
        testModule.setTag(tag, value);
      }
    }

    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    inProgressTestModules.put(testModuleDescriptor, testModule);

    return ((DDTestModuleImpl) testModule).getModuleInfo();
  }

  @Override
  public void onModuleTestFrameworkDetected(
      final T sessionKey,
      final String moduleName,
      final String frameworkName,
      final String frameworkVersion) {
    DDTestModule testModule = getTestModule(sessionKey, moduleName);
    testModule.setTag(Tags.TEST_FRAMEWORK, frameworkName);
    testModule.setTag(Tags.TEST_FRAMEWORK_VERSION, frameworkVersion);
  }

  @Override
  public void onTestModuleSkip(final T sessionKey, final String moduleName, final String reason) {
    DDTestModule testModule = getTestModule(sessionKey, moduleName);
    testModule.setSkipReason(reason);
  }

  @Override
  public void onTestModuleFail(
      final T sessionKey, final String moduleName, final Throwable throwable) {
    DDTestModule testModule = getTestModule(sessionKey, moduleName);
    testModule.setErrorInfo(throwable);
  }

  private DDTestModule getTestModule(final T sessionKey, final String moduleName) {
    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    DDTestModule testModule = inProgressTestModules.get(testModuleDescriptor);
    if (testModule == null) {
      throw new IllegalStateException(
          "Could not find module for session key " + sessionKey + " and module name " + moduleName);
    }
    return testModule;
  }

  @Override
  public void onTestModuleFinish(T sessionKey, String moduleName) {
    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    DDTestModule testModule = inProgressTestModules.remove(testModuleDescriptor);
    if (testModule == null) {
      throw new IllegalStateException(
          "Could not find module span for session key "
              + sessionKey
              + " and module name "
              + moduleName);
    }
    testModule.end(null, false);
  }

  @Override
  public ModuleExecutionSettings getModuleExecutionSettings(T sessionKey, Path jvmExecutablePath) {
    DDTestSession testSession = getTestSession(sessionKey);
    return testSession.getModuleExecutionSettings(jvmExecutablePath);
  }
}
