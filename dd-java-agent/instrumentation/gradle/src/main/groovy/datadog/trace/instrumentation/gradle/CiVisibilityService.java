package datadog.trace.instrumentation.gradle;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.Strings;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
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
    return config.isCiVisibilityJacocoPluginVersionProvided();
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

  public List<String> getCoverageEnabledPackages(Path jvmExecutable) {
    ModuleExecutionSettings moduleExecutionSettings =
        buildEventsHandler.getModuleExecutionSettings(SESSION_KEY, jvmExecutable);
    return moduleExecutionSettings.getCoverageEnabledPackages();
  }

  @SuppressForbidden
  public Collection<String> getTracerJvmArgs(String taskPath, Path jvmExecutable) {
    List<String> jvmArgs = new ArrayList<>();

    ModuleExecutionSettings moduleExecutionSettings =
        buildEventsHandler.getModuleExecutionSettings(SESSION_KEY, jvmExecutable);
    Map<String, String> propagatedSystemProperties = moduleExecutionSettings.getSystemProperties();
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

    jvmArgs.add(arg(CiVisibilityConfig.CIVISIBILITY_MODULE_NAME, taskPath));

    jvmArgs.add("-javaagent:" + config.getCiVisibilityAgentJarFile().toPath());

    BuildEventsHandler.ModuleInfo moduleInfo =
        buildEventsHandler.getModuleInfo(SESSION_KEY, taskPath);
    jvmArgs.add(arg(CiVisibilityConfig.CIVISIBILITY_SESSION_ID, moduleInfo.sessionId));
    jvmArgs.add(arg(CiVisibilityConfig.CIVISIBILITY_MODULE_ID, moduleInfo.moduleId));
    jvmArgs.add(
        arg(CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST, moduleInfo.signalServerHost));
    jvmArgs.add(
        arg(CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT, moduleInfo.signalServerPort));
    return jvmArgs;
  }

  private String arg(String propertyName, Object value) {
    return "-D" + Strings.propertyNameToSystemPropertyName(propertyName) + "=" + value;
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

  public void onModuleStart(String taskPath, Collection<File> compiledClassFolders) {
    buildEventsHandler.onTestModuleStart(SESSION_KEY, taskPath, compiledClassFolders, null);
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
