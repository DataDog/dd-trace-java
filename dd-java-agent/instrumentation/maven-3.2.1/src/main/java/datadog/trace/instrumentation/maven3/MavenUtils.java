package datadog.trace.instrumentation.maven3;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.apache.maven.BuildFailureException;
import org.apache.maven.cli.CLIManager;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public abstract class MavenUtils {

  private static final String MAVEN_CMD_LINE_ARGS_ENVIRONMENT_VAR = "MAVEN_CMD_LINE_ARGS";

  private static final String MAVEN_VERSION_SYSTEM_PROPERTY = "maven.version";

  /**
   * Returns command line used to start the build. Depending on Maven version the actual command
   * line might not be available, in which case we will recreate one using the state of the request
   * object
   */
  public static String getCommandLine(MavenSession session) {
    // FIXME uncomment
    //    String mavenCmdLineArgsEnvVar = System.getenv(MAVEN_CMD_LINE_ARGS_ENVIRONMENT_VAR);
    //    if (mavenCmdLineArgsEnvVar != null) {
    //      return "mvn " + mavenCmdLineArgsEnvVar;
    //    }
    //    Properties sessionSystemProperties = session.getSystemProperties();
    //    String mavenCmdLineArgsProp = sessionSystemProperties.getProperty("env." +
    // MAVEN_CMD_LINE_ARGS_ENVIRONMENT_VAR);
    //    if (mavenCmdLineArgsProp != null) {
    //      return "mvn " + mavenCmdLineArgsProp;
    //    }

    MavenExecutionRequest request = session.getRequest();
    StringBuilder command = new StringBuilder("mvn");

    // https://maven.apache.org/ref/3.2.1/maven-embedder/cli.html
    // we're inferring the properties available in 3.2.1, since this is the version of the Maven
    // Embedder we use for compilation
    /*
    -b,--builder <arg>	The id of the build strategy to use.
    -C,--strict-checksums	Fail the build if checksums don't match
    -c,--lax-checksums	Warn if checksums don't match
    -cpu,--check-plugin-updates	Ineffective, only kept for backward compatibility
    -D,--define <arg>	Define a system property
    -e,--errors	Produce execution error messages
    -emp,--encrypt-master-password <arg>	Encrypt master security password
    -ep,--encrypt-password <arg>	Encrypt server password
    -f,--file <arg>	Force the use of an alternate POM file (or directory with pom.xml).
    -fae,--fail-at-end	Only fail the build afterwards; allow all non-impacted builds to continue
    -ff,--fail-fast	Stop at first failure in reactorized builds
    -fn,--fail-never	NEVER fail the build, regardless of project result
    -gs,--global-settings <arg>	Alternate path for the global settings file
    -h,--help	Display help information
    -l,--log-file <arg>	Log file to where all build output will go.
    -llr,--legacy-local-repository	Use Maven 2 Legacy Local Repository behaviour, ie no use of _remote.repositories. Can also be activated by using -Dmaven.legacyLocalRepo=true
    -N,--non-recursive	Do not recurse into sub-projects
    -npr,--no-plugin-registry	Ineffective, only kept for backward compatibility
    -npu,--no-plugin-updates	Ineffective, only kept for backward compatibility
    -nsu,--no-snapshot-updates	Suppress SNAPSHOT updates
    -o,--offline	Work offline
    -P,--activate-profiles <arg>	Comma-delimited list of profiles to activate
    -pl,--projects <arg>	Comma-delimited list of specified reactor projects to build instead of all projects. A project can be specified by [groupId]:artifactId or by its relative path.
    -q,--quiet	Quiet output - only show errors
    -rf,--resume-from <arg>	Resume reactor from specified project
    -s,--settings <arg>	Alternate path for the user settings file
    -T,--threads <arg>	Thread count, for instance 2.0C where C is core multiplied
    -t,--toolchains <arg>	Alternate path for the user toolchains file
    -U,--update-snapshots	Forces a check for missing releases and updated snapshots on remote repositories
    -up,--update-plugins	Ineffective, only kept for backward compatibility
    -V,--show-version	Display version information WITHOUT stopping build
    -v,--version	Display version information
    -X,--debug	Produce execution debug output
         */

    // FIXME infer arguments ^^
    if (!request.isInteractiveMode()) {
      command.append(" -").append(CLIManager.BATCH_MODE);
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

    List<String> goals = request.getGoals();
    for (String goal : goals) {
      command.append(' ').append(goal);
    }

    // FIXME infer system properties (e.g. -Dmaven.test.skip=true -DskipTests=true)

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

  public static boolean isMavenSurefireTest(MojoExecution mojoExecution) {
    Plugin plugin = mojoExecution.getPlugin();
    return "maven-surefire-plugin".equals(plugin.getArtifactId())
        && "org.apache.maven.plugins".equals(plugin.getGroupId())
        && "test".equals(mojoExecution.getGoal());
  }

  public static boolean isMavenFailsafeTest(MojoExecution mojoExecution) {
    Plugin plugin = mojoExecution.getPlugin();
    return "maven-failsafe-plugin".equals(plugin.getArtifactId())
        && "org.apache.maven.plugins".equals(plugin.getGroupId())
        && "integration-test".equals(mojoExecution.getGoal());
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

  public static Collection<TestFramework> collectTestFrameworks(MavenProject project) {
    Collection<TestFramework> testFrameworks = new HashSet<>();

    for (Dependency dependency : project.getDependencies()) {
      String group = dependency.getGroupId();
      String name = dependency.getArtifactId();
      if ("junit".equals(group) && "junit".equals(name)) {
        testFrameworks.add(new TestFramework("junit4", dependency.getVersion()));

      } else if ("org.junit.jupiter".equals(group)) {
        testFrameworks.add(new TestFramework("junit5", dependency.getVersion()));

      } else if ("org.testng".equals(group) && "testng".equals(name)) {
        testFrameworks.add(new TestFramework("testng", dependency.getVersion()));
      }
    }
    return testFrameworks;
  }

  public static final class TestFramework {
    public final String name;
    public final String version;

    TestFramework(String name, String version) {
      this.name = name;
      this.version = version;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TestFramework that = (TestFramework) o;
      return Objects.equals(name, that.name) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, version);
    }
  }
}
