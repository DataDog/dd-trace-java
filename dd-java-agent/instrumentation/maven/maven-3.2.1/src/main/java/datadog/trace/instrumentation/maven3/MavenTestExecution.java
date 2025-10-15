package datadog.trace.instrumentation.maven3;

import java.nio.file.Path;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

public final class MavenTestExecution {
  private final MavenProject project;
  private final MojoExecution execution;
  private final Path forkedJvmPath;
  private final boolean runsWithJacoco;

  public MavenTestExecution(
      MavenProject project, MojoExecution execution, Path forkedJvmPath, boolean runsWithJacoco) {
    this.project = project;
    this.execution = execution;
    this.forkedJvmPath = forkedJvmPath;
    this.runsWithJacoco = runsWithJacoco;
  }

  public MavenProject getProject() {
    return project;
  }

  public MojoExecution getExecution() {
    return execution;
  }

  public Path getForkedJvmPath() {
    return forkedJvmPath;
  }

  public boolean isRunsWithJacoco() {
    return runsWithJacoco;
  }
}
