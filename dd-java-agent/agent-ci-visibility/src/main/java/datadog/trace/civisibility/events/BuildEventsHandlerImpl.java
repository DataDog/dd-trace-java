package datadog.trace.civisibility.events;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildModuleSettings;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.api.civisibility.domain.JavaAgent;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.config.JvmInfoFactory;
import datadog.trace.civisibility.domain.BuildSystemModule;
import datadog.trace.civisibility.domain.BuildSystemSession;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

public class BuildEventsHandlerImpl<SessionKey> implements BuildEventsHandler<SessionKey> {

  private final ConcurrentMap<SessionKey, BuildSystemSession> inProgressTestSessions =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<BuildTaskDescriptor<SessionKey>, AgentSpan> inProgressBuildTasks =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<BuildTaskDescriptor<SessionKey>, BuildSystemModule>
      inProgressTestModules = new ConcurrentHashMap<>();

  private final BuildSystemSession.Factory sessionFactory;
  private final JvmInfoFactory jvmInfoFactory;

  public BuildEventsHandlerImpl(
      BuildSystemSession.Factory sessionFactory, JvmInfoFactory jvmInfoFactory) {
    this.sessionFactory = sessionFactory;
    this.jvmInfoFactory = jvmInfoFactory;
  }

  @Override
  public void onTestSessionStart(
      final SessionKey sessionKey,
      final String projectName,
      final Path projectRoot,
      final String startCommand,
      final String buildSystemName,
      final String buildSystemVersion,
      Map<String, Object> additionalTags) {
    BuildSystemSession testSession =
        sessionFactory.startSession(projectName, projectRoot, startCommand, buildSystemName, null);

    if (additionalTags != null) {
      for (Map.Entry<String, Object> e : additionalTags.entrySet()) {
        String tag = e.getKey();
        Object value = e.getValue();
        testSession.setTag(tag, value);
      }
    }

    testSession.setTag(Tags.TEST_TOOLCHAIN, buildSystemName + ":" + buildSystemVersion);
    inProgressTestSessions.put(sessionKey, testSession);
  }

  @Override
  public void onTestSessionFail(final SessionKey sessionKey, final Throwable throwable) {
    BuildSystemSession testSession = getTestSession(sessionKey);
    testSession.setErrorInfo(throwable);
  }

  private BuildSystemSession getTestSession(SessionKey sessionKey) {
    BuildSystemSession testSession = inProgressTestSessions.get(sessionKey);
    if (testSession == null) {
      throw new IllegalStateException("Could not find session span for key: " + sessionKey);
    }
    return testSession;
  }

  @Override
  public void onTestSessionFinish(final SessionKey sessionKey) {
    BuildSystemSession testSession = inProgressTestSessions.remove(sessionKey);
    testSession.end(null);
  }

  @Override
  public void onBuildTaskStart(
      SessionKey sessionKey, String taskName, Map<String, Object> additionalTags) {
    BuildSystemSession testSession = inProgressTestSessions.get(sessionKey);
    if (testSession == null) {
      throw new IllegalStateException("Could not find session span for key: " + sessionKey);
    }
    AgentSpan buildTask = testSession.testTaskStart(taskName);
    for (Map.Entry<String, Object> e : additionalTags.entrySet()) {
      buildTask.setTag(e.getKey(), e.getValue());
    }
    inProgressBuildTasks.put(new BuildTaskDescriptor<>(sessionKey, taskName), buildTask);
  }

  @Override
  public void onBuildTaskFail(SessionKey sessionKey, String taskName, Throwable throwable) {
    AgentSpan buildTask = inProgressBuildTasks.get(new BuildTaskDescriptor<>(sessionKey, taskName));
    if (buildTask == null) {
      throw new IllegalStateException(
          "Could not find build task span for session key "
              + sessionKey
              + " and task name "
              + taskName);
    }
    buildTask.setError(true);
    buildTask.addThrowable(throwable);
  }

  @Override
  public void onBuildTaskFinish(SessionKey sessionKey, String taskName) {
    AgentSpan buildTask = inProgressBuildTasks.get(new BuildTaskDescriptor<>(sessionKey, taskName));
    if (buildTask == null) {
      throw new IllegalStateException(
          "Could not find build task span for session key "
              + sessionKey
              + " and task name "
              + taskName);
    }
    buildTask.finish();
  }

  @Override
  public BuildModuleSettings onTestModuleStart(
      final SessionKey sessionKey,
      final String moduleName,
      BuildModuleLayout moduleLayout,
      @Nullable Path jvmExecutable,
      @Nullable Collection<Path> classpath,
      @Nullable JavaAgent jacocoAgent,
      @Nullable Map<String, Object> additionalTags) {

    BuildSystemSession testSession = inProgressTestSessions.get(sessionKey);
    JvmInfo jvmInfo = jvmInfoFactory.getJvmInfo(jvmExecutable);
    BuildSystemModule testModule =
        testSession.testModuleStart(
            moduleName, null, moduleLayout, jvmInfo, classpath, jacocoAgent);
    testModule.setTag(Tags.TEST_STATUS, TestStatus.pass);

    if (additionalTags != null) {
      for (Map.Entry<String, Object> e : additionalTags.entrySet()) {
        String tag = e.getKey();
        Object value = e.getValue();
        testModule.setTag(tag, value);
      }
    }

    BuildTaskDescriptor<SessionKey> testModuleDescriptor =
        new BuildTaskDescriptor<>(sessionKey, moduleName);
    inProgressTestModules.put(testModuleDescriptor, testModule);

    return testModule.getSettings();
  }

  @Override
  public void onTestModuleSkip(
      final SessionKey sessionKey, final String moduleName, final String reason) {
    BuildSystemModule testModule = getTestModule(sessionKey, moduleName);
    testModule.setSkipReason(reason);
  }

  @Override
  public void onTestModuleFail(
      final SessionKey sessionKey, final String moduleName, final Throwable throwable) {
    BuildSystemModule testModule = getTestModule(sessionKey, moduleName);
    testModule.setErrorInfo(throwable);
  }

  private BuildSystemModule getTestModule(final SessionKey sessionKey, final String moduleName) {
    BuildTaskDescriptor<SessionKey> testModuleDescriptor =
        new BuildTaskDescriptor<>(sessionKey, moduleName);
    BuildSystemModule testModule = inProgressTestModules.get(testModuleDescriptor);
    if (testModule == null) {
      throw new IllegalStateException(
          "Could not find module for session key " + sessionKey + " and module name " + moduleName);
    }
    return testModule;
  }

  @Override
  public void onTestModuleFinish(SessionKey sessionKey, String moduleName) {
    BuildTaskDescriptor<SessionKey> testModuleDescriptor =
        new BuildTaskDescriptor<>(sessionKey, moduleName);
    BuildSystemModule testModule = inProgressTestModules.remove(testModuleDescriptor);
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
  public BuildSessionSettings getSessionSettings(SessionKey sessionKey) {
    return getTestSession(sessionKey).getSettings();
  }

  @Override
  public BuildModuleSettings getModuleSettings(SessionKey sessionKey, String moduleName) {
    return getTestModule(sessionKey, moduleName).getSettings();
  }
}
