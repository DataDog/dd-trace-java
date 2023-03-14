package datadog.trace.instrumentation.maven3;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;

public class CiVisibilityLifecycleParticipant extends AbstractMavenLifecycleParticipant {

  private final BuildEventsHandler<MavenSession> buildEventsHandler =
      InstrumentationBridge.getBuildEventsHandler();

  @Override
  public void afterSessionStart(MavenSession session) throws MavenExecutionException {
    if (!Config.get().isCiVisibilityEnabled()) {
      return;
    }
    // needed info is not yet available at this stage
  }

  @Override
  public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
    if (!Config.get().isCiVisibilityEnabled()) {
      return;
    }

    //    MavenProject project = session.getCurrentProject();
    //    File projectRoot = project.getBasedir();
    //    TestDecorator mavenDecorator = new MavenDecorator(projectRoot.toPath());
    //    String projectName = project.getName();
    //
    //    MavenExecutionRequest request = session.getRequest();
    //    ProjectBuildingRequest projectBuildingRequest = session.getProjectBuildingRequest();
    //    Settings settings = session.getSettings();
    //
    //    buildEventsHandler.onTestSessionStart(
    //        session, mavenDecorator, projectName, null, "gradle", null);

    // FIXME instrument modules?
  }

  @Override
  public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
    if (!Config.get().isCiVisibilityEnabled()) {
      return;
    }
    // FIXME check result and call on session fail
    //    buildEventsHandler.onTestSessionFinish(session);
  }
}
