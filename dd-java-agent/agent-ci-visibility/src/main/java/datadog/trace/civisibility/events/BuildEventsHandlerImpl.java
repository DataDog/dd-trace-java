package datadog.trace.civisibility.events;

import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.DDBuildSystemModule;
import datadog.trace.civisibility.DDBuildSystemSession;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.config.JvmInfoFactory;
import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

public class BuildEventsHandlerImpl<T> implements BuildEventsHandler<T> {

  private final ConcurrentMap<T, DDBuildSystemSession> inProgressTestSessions =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestModuleDescriptor<T>, DDBuildSystemModule> inProgressTestModules =
      new ConcurrentHashMap<>();

  private final DDBuildSystemSession.Factory sessionFactory;
  private final JvmInfoFactory jvmInfoFactory;

  public BuildEventsHandlerImpl(
      DDBuildSystemSession.Factory sessionFactory, JvmInfoFactory jvmInfoFactory) {
    this.sessionFactory = sessionFactory;
    this.jvmInfoFactory = jvmInfoFactory;
  }

  @Override
  public void onTestSessionStart(
      final T sessionKey,
      final String projectName,
      final Path projectRoot,
      final String startCommand,
      final String buildSystemName,
      final String buildSystemVersion) {
    DDBuildSystemSession testSession =
        sessionFactory.startSession(projectName, projectRoot, startCommand, buildSystemName, null);
    testSession.setTag(Tags.TEST_TOOLCHAIN, buildSystemName + ":" + buildSystemVersion);
    inProgressTestSessions.put(sessionKey, testSession);
  }

  @Override
  public void onTestSessionFail(final T sessionKey, final Throwable throwable) {
    DDBuildSystemSession testSession = getTestSession(sessionKey);
    testSession.setErrorInfo(throwable);
  }

  private DDBuildSystemSession getTestSession(T sessionKey) {
    DDBuildSystemSession testSession = inProgressTestSessions.get(sessionKey);
    if (testSession == null) {
      throw new IllegalStateException("Could not find session span for key: " + sessionKey);
    }
    return testSession;
  }

  @Override
  public void onTestSessionFinish(final T sessionKey) {
    DDBuildSystemSession testSession = inProgressTestSessions.remove(sessionKey);
    testSession.end(null);
  }

  @Override
  public ModuleInfo onTestModuleStart(
      final T sessionKey,
      final String moduleName,
      Collection<File> outputClassesDirs,
      @Nullable Map<String, Object> additionalTags) {

    DDBuildSystemSession testSession = inProgressTestSessions.get(sessionKey);
    DDBuildSystemModule testModule =
        testSession.testModuleStart(moduleName, null, outputClassesDirs);
    testModule.setTag(Tags.TEST_STATUS, CIConstants.TEST_PASS);

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

    return testModule.getModuleInfo();
  }

  @Override
  public void onTestModuleSkip(final T sessionKey, final String moduleName, final String reason) {
    DDBuildSystemModule testModule = getTestModule(sessionKey, moduleName);
    testModule.setSkipReason(reason);
  }

  @Override
  public void onTestModuleFail(
      final T sessionKey, final String moduleName, final Throwable throwable) {
    DDBuildSystemModule testModule = getTestModule(sessionKey, moduleName);
    testModule.setErrorInfo(throwable);
  }

  private DDBuildSystemModule getTestModule(final T sessionKey, final String moduleName) {
    TestModuleDescriptor<T> testModuleDescriptor =
        new TestModuleDescriptor<>(sessionKey, moduleName);
    DDBuildSystemModule testModule = inProgressTestModules.get(testModuleDescriptor);
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
    DDBuildSystemModule testModule = inProgressTestModules.remove(testModuleDescriptor);
    if (testModule == null) {
      throw new IllegalStateException(
          "Could not find module span for session key "
              + sessionKey
              + " and module name "
              + moduleName);
    }
    testModule.end(null);
  }

  @Override
  public ModuleExecutionSettings getModuleExecutionSettings(T sessionKey, Path jvmExecutablePath) {
    DDBuildSystemSession testSession = getTestSession(sessionKey);
    JvmInfo jvmInfo = jvmInfoFactory.getJvmInfo(jvmExecutablePath);
    return testSession.getModuleExecutionSettings(jvmInfo);
  }
}
