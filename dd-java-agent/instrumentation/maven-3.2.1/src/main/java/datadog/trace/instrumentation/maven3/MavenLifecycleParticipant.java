package datadog.trace.instrumentation.maven3;

import datadog.trace.api.Config;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

public class MavenLifecycleParticipant extends AbstractMavenLifecycleParticipant {

  private static final String MAVEN_SUREFIRE_PLUGIN_KEY =
      "org.apache.maven.plugins:maven-surefire-plugin";
  private static final String MAVEN_FAILSAFE_PLUGIN_KEY =
      "org.apache.maven.plugins:maven-failsafe-plugin";

  @Override
  public void afterSessionStart(MavenSession session) {
    if (!Config.get().isCiVisibilityEnabled()) {
      return;
    }

    ExecutionListener originalExecutionListener = session.getRequest().getExecutionListener();
    ExecutionListener spyExecutionListener = new MavenExecutionListener();

    // We cannot add an ExecutionListener to the request, we can only replace the existing one.
    // Since we want to preserve the original listener, the solution is to use a "splitter",
    // that will forward each event both to the original listener and to our custom one.
    // Since ExecutionListener may (and does) change depending on Maven version,
    // we use a dynamic proxy instead of implementing the interface
    InvocationHandler invocationHandler =
        (Object target, Method method, Object[] args) -> {
          method.invoke(spyExecutionListener, args);
          return method.invoke(originalExecutionListener, args);
        };
    ExecutionListener proxyExecutionListener =
        (ExecutionListener)
            Proxy.newProxyInstance(
                MavenLifecycleParticipant.class.getClassLoader(),
                new Class[] {ExecutionListener.class},
                invocationHandler);
    session.getRequest().setExecutionListener(proxyExecutionListener);
  }

  @Override
  public void afterProjectsRead(MavenSession session) {
    Config config = Config.get();
    if (!config.isCiVisibilityEnabled() || !config.isCiVisibilityAutoConfigurationEnabled()) {
      return;
    }

    for (MavenProject project : session.getProjects()) {
      MavenProjectConfigurator.INSTANCE.configureTracer(project, MAVEN_SUREFIRE_PLUGIN_KEY);
      MavenProjectConfigurator.INSTANCE.configureTracer(project, MAVEN_FAILSAFE_PLUGIN_KEY);

      if (config.isCiVisibilityCompilerPluginAutoConfigurationEnabled()) {
        String compilerPluginVersion = config.getCiVisibilityCompilerPluginVersion();
        MavenProjectConfigurator.INSTANCE.configureCompilerPlugin(project, compilerPluginVersion);
      }

      if (config.isCiVisibilityPerTestCodeCoverageEnabled()) {
        MavenProjectConfigurator.INSTANCE.configureJacoco(project);
      }
    }
  }
}
