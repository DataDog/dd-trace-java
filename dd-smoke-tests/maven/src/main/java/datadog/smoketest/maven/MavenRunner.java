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

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException(
          "2 arguments are required: maven user home, project home. Got: " + Arrays.toString(args));
    }

    Path mavenUserHome = Paths.get(args[0]);
    Path projectHome = Paths.get(args[1]);

    WrapperExecutor wrapperExecutor = WrapperExecutor.forProjectDirectory(projectHome);
    wrapperExecutor.execute(
        new String[] {"test"},
        new Installer(
            new DefaultDownloader("mvnw", "3.2.0"),
            new HashAlgorithmVerifier(),
            new PathAssembler(mavenUserHome)),
        new BootstrapMainStarter());
  }
}
