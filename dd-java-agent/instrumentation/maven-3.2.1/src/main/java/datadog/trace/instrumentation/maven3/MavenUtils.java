package datadog.trace.instrumentation.maven3;

import datadog.trace.util.MethodHandles;
import datadog.trace.util.Strings;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import javax.annotation.Nullable;
import org.apache.maven.BuildFailureException;
import org.apache.maven.cli.CLIManager;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.LoggerFactory;

public abstract class MavenUtils {

  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(MavenUtils.class);

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
      METHOD_HANDLES.privateFieldGetter("org.apache.maven.internal.impl.AbstractSession", "lookup");
  private static final MethodHandle ALTERNATIVE_LOOKUP_FIELD =
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
    Object /* org.apache.maven.api.services.Lookup */ lookup;
    if (LOOKUP_FIELD != null) {
      lookup = METHOD_HANDLES.invoke(LOOKUP_FIELD, session);
    } else {
      lookup = METHOD_HANDLES.invoke(ALTERNATIVE_LOOKUP_FIELD, session);
    }
    return METHOD_HANDLES.invoke(LOOKUP_METHOD, lookup, PlexusContainer.class);
  }

  public static PlexusConfiguration getPomConfiguration(MojoExecution mojoExecution) {
    Xpp3Dom configuration = mojoExecution.getConfiguration();
    if (configuration == null) {
      return new XmlPlexusConfiguration("configuration");
    } else {
      return new XmlPlexusConfiguration(configuration);
    }
  }

  @Nullable
  public static Path getForkedJvmPath(MavenSession session, MojoExecution mojoExecution) {
    if (!MavenUtils.isTestExecution(mojoExecution)) {
      return null;
    }
    try {
      Mojo mojo = getConfiguredMojo(session, mojoExecution);
      String forkedJvm = getEffectiveJvm(mojoExecution, mojo);
      if (forkedJvm == null) {
        forkedJvm = getEffectiveJvmFallback(session, mojoExecution);
      }
      return forkedJvm != null ? Paths.get(forkedJvm) : null;

    } catch (Exception e) {
      LOGGER.debug("Error while getting effective JVM for mojoExecution {}", mojoExecution, e);
      return null;
    }
  }

  private static Mojo getConfiguredMojo(MavenSession session, MojoExecution mojoExecution)
      throws ComponentLookupException, PluginResolutionException, PluginManagerException {
    PlexusContainer container = getContainer(session);

    BuildPluginManager buildPluginManager = container.lookup(BuildPluginManager.class);
    MojoDescriptor mojoDescriptor = mojoExecution.getMojoDescriptor();
    PluginDescriptor pluginDescriptor = mojoDescriptor.getPluginDescriptor();
    // ensure plugin realm is loaded in container
    buildPluginManager.getPluginRealm(session, pluginDescriptor);

    MavenPluginManager mavenPluginManager = container.lookup(MavenPluginManager.class);
    try {
      return mavenPluginManager.getConfiguredMojo(Mojo.class, session, mojoExecution);
    } catch (Exception e) {
      LOGGER.debug("Error while getting effective JVM for mojoExecution {}", mojoExecution, e);
      return null;
    }
  }

  private static String getEffectiveJvm(MojoExecution mojoExecution, Mojo mojo) {
    if (mojo == null) {
      return null;
    }

    MojoDescriptor mojoDescriptor = mojoExecution.getMojoDescriptor();
    PluginDescriptor pluginDescriptor = mojoDescriptor.getPluginDescriptor();
    ClassRealm pluginRealm = pluginDescriptor.getClassRealm();
    MethodHandles methodHandles = new MethodHandles(pluginRealm);

    MethodHandle getEffectiveJvmMethod = findGetEffectiveJvmMethod(methodHandles, mojo.getClass());
    if (getEffectiveJvmMethod == null) {
      LOGGER.debug("Could not find getEffectiveJvm method in {} class", mojo.getClass().getName());
      return null;
    }
    try {
      // result type differs based on Surefire plugin version
      Object effectiveJvm = methodHandles.invoke(getEffectiveJvmMethod, mojo);

      if (effectiveJvm instanceof String) {
        return (String) effectiveJvm;
      } else if (effectiveJvm instanceof File) {
        return ((File) effectiveJvm).getAbsolutePath();
      }

      Class<?> effectiveJvmClass = effectiveJvm.getClass();
      Method getJvmExecutableMethod = effectiveJvmClass.getMethod("getJvmExecutable");
      Object jvmExecutable = getJvmExecutableMethod.invoke(effectiveJvm);

      if (jvmExecutable instanceof String) {
        return (String) jvmExecutable;
      } else if (jvmExecutable instanceof File) {
        return ((File) jvmExecutable).getAbsolutePath();
      } else if (jvmExecutable == null) {
        LOGGER.debug("Configured JVM executable is null");
        return null;
      } else {
        LOGGER.debug(
            "Unexpected JVM executable type {}, returning null",
            jvmExecutable.getClass().getName());
        return null;
      }

    } catch (Exception e) {
      LOGGER.debug("Error while getting effective JVM for mojo {}", mojo, e);
      return null;
    }
  }

  private static MethodHandle findGetEffectiveJvmMethod(
      MethodHandles methodHandles, Class<?> mojoClass) {
    do {
      MethodHandle getEffectiveJvm = methodHandles.method(mojoClass, "getEffectiveJvm");
      if (getEffectiveJvm != null) {
        return getEffectiveJvm;
      }
      mojoClass = mojoClass.getSuperclass();
    } while (mojoClass != null);
    return null;
  }

  /** Fallback method that attempts to recreate the logic used by Maven Surefire plugin */
  private static String getEffectiveJvmFallback(MavenSession session, MojoExecution mojoExecution) {
    try {
      PlexusConfiguration configuration = getPomConfiguration(mojoExecution);
      PlexusConfiguration jvm = configuration.getChild("jvm");
      if (jvm != null) {
        String value = jvm.getValue();
        if (Strings.isNotBlank(value)) {
          ExpressionEvaluator expressionEvaluator =
              new PluginParameterExpressionEvaluator(session, mojoExecution);
          Object evaluatedValue = expressionEvaluator.evaluate(value);
          if (evaluatedValue != null && Strings.isNotBlank(String.valueOf(evaluatedValue))) {
            return String.valueOf(evaluatedValue);
          }
        }
      }

      PlexusConfiguration jdkToolchain = configuration.getChild("jdkToolchain");
      if (jdkToolchain != null) {
        ExpressionEvaluator expressionEvaluator =
            new PluginParameterExpressionEvaluator(session, mojoExecution);
        Map<String, String> toolchainConfig = new HashMap<>();
        for (PlexusConfiguration child : jdkToolchain.getChildren()) {
          Object value = expressionEvaluator.evaluate(child.getValue());
          if (value != null && Strings.isNotBlank(String.valueOf(value))) {
            toolchainConfig.put(child.getName(), String.valueOf(value));
          }
        }

        if (!toolchainConfig.isEmpty()) {
          PlexusContainer container = MavenUtils.getContainer(session);
          ToolchainManager toolchainManager = container.lookup(ToolchainManager.class);

          MojoDescriptor mojoDescriptor = mojoExecution.getMojoDescriptor();
          PluginDescriptor pluginDescriptor = mojoDescriptor.getPluginDescriptor();
          ClassRealm pluginRealm = pluginDescriptor.getClassRealm();
          MethodHandles methodHandles = new MethodHandles(pluginRealm);
          MethodHandle getToolchains =
              methodHandles.method(
                  ToolchainManager.class,
                  "getToolchains",
                  MavenSession.class,
                  String.class,
                  Map.class);
          List<Toolchain> toolchains =
              methodHandles.invoke(
                  getToolchains, toolchainManager, session, "jdk", toolchainConfig);
          if (toolchains.isEmpty()) {
            LOGGER.debug("Could not find toolchains for {}", toolchainConfig);
            return null;
          }

          Toolchain toolchain = toolchains.iterator().next();
          return toolchain.findTool("java");
        }
      }
      return null;

    } catch (Exception e) {
      LOGGER.debug("Error while getting effective JVM for mojo {}", mojoExecution, e);
      return null;
    }
  }
}
