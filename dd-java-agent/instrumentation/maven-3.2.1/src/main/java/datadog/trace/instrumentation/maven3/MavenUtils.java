package datadog.trace.instrumentation.maven3;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.apache.maven.BuildFailureException;
import org.apache.maven.cli.CLIManager;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public abstract class MavenUtils {

  private static final String MAVEN_CMD_LINE_ARGS_ENVIRONMENT_VAR = "MAVEN_CMD_LINE_ARGS";

  private static final String MAVEN_VERSION_SYSTEM_PROPERTY = "maven.version";

  private static final String MVN_CMD_LINE_INVOCATION = "mvn";

  /**
   * Returns command line used to start the build. Depending on Maven version the actual command
   * line might not be available, in which case we will do our best to recreate one using the state
   * of the request object
   */
  public static String getCommandLine(MavenSession session) {
    String mavenCmdLineArgsEnvVar = System.getenv(MAVEN_CMD_LINE_ARGS_ENVIRONMENT_VAR);
    if (mavenCmdLineArgsEnvVar != null) {
      return MVN_CMD_LINE_INVOCATION + mavenCmdLineArgsEnvVar;
    }

    Properties sessionSystemProperties = session.getSystemProperties();
    String mavenCmdLineArgsProp =
        sessionSystemProperties.getProperty("env." + MAVEN_CMD_LINE_ARGS_ENVIRONMENT_VAR);
    if (mavenCmdLineArgsProp != null) {
      return MVN_CMD_LINE_INVOCATION + mavenCmdLineArgsProp;
    }

    MavenExecutionRequest request = session.getRequest();
    StringBuilder command = new StringBuilder(MVN_CMD_LINE_INVOCATION);

    if (!request.isInteractiveMode()) {
      command.append(" -").append(CLIManager.BATCH_MODE);
    }

    if (request.getGlobalChecksumPolicy() != null) {
      switch (request.getGlobalChecksumPolicy()) {
        case "fail":
          command.append(" -").append(CLIManager.CHECKSUM_FAILURE_POLICY);
          break;
        case "warn":
          command.append(" -").append(CLIManager.CHECKSUM_WARNING_POLICY);
          break;
        default:
          break;
      }
    }

    if (request.getMakeBehavior() != null) {
      switch (request.getMakeBehavior()) {
        case "make-upstream":
          command.append(" -").append(CLIManager.ALSO_MAKE);
          break;
        case "make-downstream":
          command.append(" -").append(CLIManager.ALSO_MAKE_DEPENDENTS);
          break;
        case "make-both":
          command.append(" -").append(CLIManager.ALSO_MAKE);
          command.append(" -").append(CLIManager.ALSO_MAKE_DEPENDENTS);
          break;
        default:
          break;
      }
    }

    if (request.isShowErrors()) {
      command.append(" -").append(CLIManager.ERRORS);
    }

    if (!Objects.equals(request.getPom().getParent(), request.getBaseDirectory())
        || !Objects.equals(request.getPom().getName(), "pom.xml")) {
      command
          .append(" -")
          .append(CLIManager.ALTERNATE_POM_FILE)
          .append('=')
          .append(request.getPom());
    }

    if (request.getReactorFailureBehavior() != null) {
      switch (request.getReactorFailureBehavior()) {
        case "FAIL_AT_END":
          command.append(" -").append(CLIManager.FAIL_AT_END);
          break;
        case "FAIL_NEVER":
          command.append(" -").append(CLIManager.FAIL_NEVER);
          break;
        default:
          break;
      }
    }

    int loggingLevel = request.getLoggingLevel();
    switch (loggingLevel) {
      case Logger.LEVEL_DEBUG:
        command.append(" -").append(CLIManager.DEBUG);
        break;
      case Logger.LEVEL_ERROR:
        command.append(" -").append(CLIManager.QUIET);
        break;
      default:
        break;
    }

    if (!request.isRecursive()) {
      command.append(" -").append(CLIManager.NON_RECURSIVE);
    }

    if (request.isUpdateSnapshots()) {
      command.append(" -").append(CLIManager.UPDATE_SNAPSHOTS);
    }

    if (request.isNoSnapshotUpdates()) {
      command.append(" -").append(CLIManager.SUPRESS_SNAPSHOT_UPDATES);
    }

    if (request.isOffline()) {
      command.append(" -").append(CLIManager.OFFLINE);
    }

    if (request.getDegreeOfConcurrency() != 1) {
      command.append(" -").append(CLIManager.THREADS).append(request.getDegreeOfConcurrency());
    }

    if (!request.getSelectedProjects().isEmpty()) {
      command.append(" -").append(CLIManager.PROJECT_LIST).append('=');
      Iterator<String> it = request.getSelectedProjects().iterator();
      while (it.hasNext()) {
        command.append(it.next());
        if (it.hasNext()) {
          command.append(',');
        }
      }
    }

    if (request.getResumeFrom() != null && !request.getResumeFrom().isEmpty()) {
      command
          .append(" -")
          .append(CLIManager.RESUME_FROM)
          .append('=')
          .append(request.getResumeFrom());
    }

    List<String> goals = request.getGoals();
    for (String goal : goals) {
      command.append(' ').append(goal);
    }

    Properties userProperties = request.getUserProperties();
    if (userProperties != null) {
      for (Map.Entry<Object, Object> e : userProperties.entrySet()) {
        command
            .append(" -")
            .append(CLIManager.SET_SYSTEM_PROPERTY)
            .append(e.getKey())
            .append('=')
            .append(e.getValue());
      }
    }

    if (!request.getActiveProfiles().isEmpty()) {
      command.append(" -").append(CLIManager.ACTIVATE_PROFILES);
      Iterator<String> it = request.getActiveProfiles().iterator();
      while (it.hasNext()) {
        command.append(it.next());
        if (it.hasNext()) {
          command.append(',');
        }
      }
    }

    return command.toString();
  }

  public static String getMavenVersion(MavenSession session) {
    Properties sessionSystemProperties = session.getSystemProperties();
    return sessionSystemProperties.getProperty(MAVEN_VERSION_SYSTEM_PROPERTY);
  }

  public static Throwable getException(MavenExecutionResult result) {
    if (!result.hasExceptions()) {
      return null;
    }
    List<Throwable> exceptions = result.getExceptions();
    if (exceptions.size() == 1) {
      return exceptions.iterator().next();
    } else {
      MavenProject project = result.getProject();
      Throwable t =
          new BuildFailureException(
              "Build failed" + (project != null ? " for " + project.getName() : ""));
      for (Throwable e : exceptions) {
        t.addSuppressed(e);
      }
      return t;
    }
  }

  public static boolean isTestExecution(MojoExecution mojoExecution) {
    Plugin plugin = mojoExecution.getPlugin();
    String artifactId = plugin.getArtifactId();
    String groupId = plugin.getGroupId();
    String goal = mojoExecution.getGoal();
    return "maven-surefire-plugin".equals(artifactId)
            && "org.apache.maven.plugins".equals(groupId)
            && "test".equals(goal)
        || "maven-failsafe-plugin".equals(artifactId)
            && "org.apache.maven.plugins".equals(groupId)
            && "integration-test".equals(goal)
        || "tycho-surefire-plugin".equals(artifactId)
            && "org.eclipse.tycho".equals(groupId)
            && ("test".equals(goal) || "plugin-test".equals(goal) || "bnd-test".equals(goal));
  }

  public static boolean isJacocoInstrumentationExecution(MojoExecution mojoExecution) {
    Plugin plugin = mojoExecution.getPlugin();
    String artifactId = plugin.getArtifactId();
    String groupId = plugin.getGroupId();
    String goal = mojoExecution.getGoal();
    return "jacoco-maven-plugin".equals(artifactId)
        && "org.jacoco".equals(groupId)
        && "prepare-agent".equals(goal);
  }

  public static String getConfigurationValue(Xpp3Dom configuration, String... path) {
    if (configuration == null) {
      return null;
    }

    Xpp3Dom current = getChild(configuration, path, false);
    return current != null ? current.getValue() : null;
  }

  public static Xpp3Dom setConfigurationValue(String value, Xpp3Dom configuration, String... path) {
    if (configuration == null) {
      configuration = new Xpp3Dom("configuration");
    }

    Xpp3Dom current = getChild(configuration, path, true);
    current.setValue(value);

    return configuration;
  }

  private static Xpp3Dom getChild(Xpp3Dom parent, String[] path, boolean createIfNeeded) {
    Xpp3Dom current = parent;
    for (String name : path) {
      Xpp3Dom child = current.getChild(name);
      if (child == null) {
        if (createIfNeeded) {
          child = new Xpp3Dom(name);
          current.addChild(child);
        } else {
          return null;
        }
      }
      current = child;
    }
    return current;
  }

  public static String getUniqueModuleName(MavenProject project, MojoExecution mojoExecution) {
    return project.getName()
        + " "
        + mojoExecution.getArtifactId()
        + " "
        + mojoExecution.getExecutionId();
  }

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(PlexusContainer.class.getClassLoader());
  private static final MethodHandle SESSION_FIELD =
      METHOD_HANDLES.privateFieldGetter(MavenSession.class, "session");
  private static final MethodHandle LOOKUP_FIELD =
      METHOD_HANDLES.privateFieldGetter("org.apache.maven.internal.impl.DefaultSession", "lookup");
  private static final MethodHandle LOOKUP_METHOD =
      METHOD_HANDLES.method("org.apache.maven.api.services.Lookup", "lookup", Class.class);

  public static PlexusContainer getContainer(MavenSession mavenSession) {
    PlexusContainer container = mavenSession.getContainer();
    if (container != null) {
      return container;
    }
    Object /* org.apache.maven.internal.impl.DefaultSession */ session =
        METHOD_HANDLES.invoke(SESSION_FIELD, mavenSession);
    Object /* org.apache.maven.api.services.Lookup */ lookup =
        METHOD_HANDLES.invoke(LOOKUP_FIELD, session);
    return METHOD_HANDLES.invoke(LOOKUP_METHOD, lookup, PlexusContainer.class);
  }
}
