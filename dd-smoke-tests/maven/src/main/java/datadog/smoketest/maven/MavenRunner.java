package datadog.smoketest.maven;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.apache.maven.wrapper.BootstrapMainStarter;
import org.apache.maven.wrapper.DefaultDownloader;
import org.apache.maven.wrapper.HashAlgorithmVerifier;
import org.apache.maven.wrapper.Installer;
import org.apache.maven.wrapper.PathAssembler;
import org.apache.maven.wrapper.WrapperExecutor;

public class MavenRunner {

  private static final Path DEFAULT_MAVEN_USER_HOME =
      Paths.get(System.getProperty("user.home")).resolve(".m2");

  private static final String MAVEN_USER_HOME_PROPERTY_KEY = "maven.user.home";

  private static final String MAVEN_USER_HOME_ENV_KEY = "MAVEN_USER_HOME";

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException(
          "2 arguments are required: project home, command to execute. "
              + "Got: "
              + Arrays.toString(args));
    }

    Path projectHome = Paths.get(args[0]);
    String[] command = Arrays.copyOfRange(args, 1, args.length);

    WrapperExecutor wrapperExecutor = WrapperExecutor.forProjectDirectory(projectHome);
    wrapperExecutor.execute(
        command,
        new Installer(
            new DefaultDownloader("mvnw", "3.2.0"),
            new HashAlgorithmVerifier(),
            new PathAssembler(mavenUserHome())),
        new BootstrapMainStarter());
  }

  private static Path mavenUserHome() {
    String mavenUserHome = System.getProperty(MAVEN_USER_HOME_PROPERTY_KEY);
    if (mavenUserHome == null) {
      mavenUserHome = System.getenv(MAVEN_USER_HOME_ENV_KEY);
    }

    return mavenUserHome == null ? DEFAULT_MAVEN_USER_HOME : Paths.get(mavenUserHome);
  }
}
