package datadog.trace.instrumentation.gradle;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildModuleSettings;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.api.civisibility.domain.JavaAgent;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;

public abstract class CiVisibilityService
    implements BuildService<BuildServiceParameters.None>, OperationCompletionListener {

  // using constant session key, since the service is already build-scoped
  private static final Object SESSION_KEY = new Object();
  private final Config config = Config.get();
  private final BuildEventsHandler<Object> buildEventsHandler =
      InstrumentationBridge.createBuildEventsHandler();

  public boolean isCompilerPluginEnabled() {
    return config.isCiVisibilityCompilerPluginAutoConfigurationEnabled();
  }

  public String getCompilerPluginVersion() {
    return config.getCiVisibilityCompilerPluginVersion();
  }

  public boolean isJacocoInjectionEnabled() {
    return config.isCiVisibilityJacocoPluginVersionProvided()
        || buildEventsHandler.getSessionSettings(SESSION_KEY).isCoverageReportUploadEnabled();
  }

  public String getJacocoVersion() {
    return config.getCiVisibilityJacocoPluginVersion();
  }

  public List<String> getExcludeClassLoaders() {
    return Collections.singletonList(DatadogClassLoader.class.getName());
  }

  public List<String> getCoverageEnabledSourceSets() {
    return config.getCiVisibilityJacocoGradleSourceSets();
  }

  public List<String> getCoverageIncludedPackages() {
    BuildSessionSettings sessionSettings = buildEventsHandler.getSessionSettings(SESSION_KEY);
    return sessionSettings.getCoverageIncludedPackages();
  }

  public List<String> getCoverageExcludedPackages() {
    BuildSessionSettings sessionSettings = buildEventsHandler.getSessionSettings(SESSION_KEY);
    return sessionSettings.getCoverageExcludedPackages();
  }

  @SuppressForbidden
  public Collection<String> getTracerJvmArgs(String taskPath) {
    List<String> jvmArgs = new ArrayList<>();

    BuildModuleSettings moduleSettings =
        buildEventsHandler.getModuleSettings(SESSION_KEY, taskPath);
    Map<String, String> propagatedSystemProperties = moduleSettings.getSystemProperties();
    // propagate to child process all "dd." system properties available in current process
    for (Map.Entry<String, String> e : propagatedSystemProperties.entrySet()) {
      jvmArgs.add("-D" + e.getKey() + '=' + e.getValue());
    }

    Integer ciVisibilityDebugPort = config.getCiVisibilityDebugPort();
    if (ciVisibilityDebugPort != null) {
      jvmArgs.add(
          "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + ciVisibilityDebugPort);
    }

    String additionalArgs = config.getCiVisibilityAdditionalChildProcessJvmArgs();
    if (additionalArgs != null) {
      List<String> splitArgs = Arrays.asList(additionalArgs.split(" "));
      jvmArgs.addAll(splitArgs);
    }

    jvmArgs.add("-javaagent:" + config.getCiVisibilityAgentJarFile().toPath());

    return jvmArgs;
  }

  public void onBuildStart(
      String buildPath,
      Path projectRoot,
      String startCommand,
      String gradleVersion,
      boolean nestedBuild) {
    Map<String, Object> additionalTags =
        nestedBuild
            ? Collections.singletonMap(Tags.TEST_GRADLE_NESTED_BUILD, true)
            : Collections.emptyMap();
    buildEventsHandler.onTestSessionStart(
        SESSION_KEY, buildPath, projectRoot, startCommand, "gradle", gradleVersion, additionalTags);
  }

  public void onBuildTaskStart(String taskPath) {
    buildEventsHandler.onBuildTaskStart(SESSION_KEY, taskPath, Collections.emptyMap());
  }

  public void onBuildTaskFinish(String taskPath, @Nullable Throwable failure) {
    if (failure != null) {
      buildEventsHandler.onBuildTaskFail(SESSION_KEY, taskPath, failure);
    }
    buildEventsHandler.onBuildTaskFinish(SESSION_KEY, taskPath);
  }

  public void onModuleStart(
      String taskPath,
      BuildModuleLayout moduleLayout,
      Path jvmExecutable,
      Collection<Path> taskClasspath,
      JavaAgent jacocoAgent) {
    buildEventsHandler.onTestModuleStart(
        SESSION_KEY, taskPath, moduleLayout, jvmExecutable, taskClasspath, jacocoAgent, null);
  }

  public void onModuleFinish(
      String taskPath, @Nullable Throwable failure, @Nullable String skipReason) {
    if (failure != null) {
      buildEventsHandler.onTestModuleFail(SESSION_KEY, taskPath, failure);
    } else if (skipReason != null) {
      buildEventsHandler.onTestModuleSkip(SESSION_KEY, taskPath, skipReason);
    }
    buildEventsHandler.onTestModuleFinish(SESSION_KEY, taskPath);
  }

  public void onBuildFinish(@Nullable Throwable failure) {
    if (failure != null) {
      buildEventsHandler.onTestSessionFail(SESSION_KEY, failure);
    }
    buildEventsHandler.onTestSessionFinish(SESSION_KEY);
  }

  @Override
  public void onFinish(FinishEvent event) {
    // do nothing
  }
}
