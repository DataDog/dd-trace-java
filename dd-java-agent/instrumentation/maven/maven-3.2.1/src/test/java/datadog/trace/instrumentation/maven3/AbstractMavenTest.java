package datadog.trace.instrumentation.maven3;

import static java.util.Collections.addAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.maven.cli.MavenCli;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.codehaus.plexus.PlexusContainer;
import org.junit.jupiter.api.io.TempDir;

public abstract class AbstractMavenTest {
  @TempDir static Path WORKING_DIRECTORY;

  protected AbstractMavenTest() {
    System.setProperty(
        "maven.multiModuleProjectDirectory", WORKING_DIRECTORY.toAbsolutePath().toString());
  }

  protected void executeMaven(
      Function<ExecutionEvent, Boolean> mojoStartedHandler,
      String pomPath,
      String goal,
      String... additionalArgs)
      throws Exception {
    executeMaven(mojoStartedHandler, pomPath, goal, null, null, additionalArgs);
  }

  protected void executeMaven(
      Function<ExecutionEvent, Boolean> mojoStartedHandler,
      String pomPath,
      String goal,
      PrintStream stdOut,
      PrintStream stderr,
      String... additionalArgs)
      throws Exception {
    MojoStartedSpy spy = new MojoStartedSpy(mojoStartedHandler);
    MavenCli mavenCli =
        new MavenCli() {
          @Override
          protected void customizeContainer(PlexusContainer container) {
            container.addComponent(spy, EventSpy.class, null);
          }
        };

    File pomFile = new File(AbstractMavenTest.class.getResource(pomPath).toURI());

    List<String> arguments = new ArrayList<>();
    addAll(arguments, "-f", pomFile.getAbsolutePath(), goal);
    // Cap Aether's HTTP read timeout so a stalled Maven Central fetch fails fast.
    // Default is 30 min, which exceeds the 20-min Gradle test task timeout and turns
    // a network stall into an opaque "Timeout has been exceeded" task abort.
    arguments.add("-Daether.connector.requestTimeout=60000");
    if (System.getenv("MAVEN_REPOSITORY_PROXY") != null) {
      File settingsFile =
          new File(AbstractMavenTest.class.getResource("/settings.mirror.xml").toURI());
      addAll(arguments, "-s", settingsFile.getAbsolutePath());
    }
    addAll(arguments, additionalArgs);

    mavenCli.doMain(
        arguments.toArray(new String[0]),
        WORKING_DIRECTORY.toAbsolutePath().toString(),
        stdOut,
        stderr);

    Exception error = spy.handlerError.get();
    if (error != null) {
      throw error;
    }
    assertTrue(spy.handlerRan.get());
  }

  private static final class MojoStartedSpy extends AbstractEventSpy {
    private final Function<ExecutionEvent, Boolean> mojoStartedHandler;
    private final AtomicBoolean handlerRan = new AtomicBoolean(false);
    private final AtomicReference<Exception> handlerError = new AtomicReference<>(null);

    private MojoStartedSpy(Function<ExecutionEvent, Boolean> mojoStartedHandler) {
      this.mojoStartedHandler = mojoStartedHandler;
    }

    @Override
    public void onEvent(Object o) {
      if (!(o instanceof ExecutionEvent)) {
        return;
      }
      ExecutionEvent executionEvent = (ExecutionEvent) o;
      if (executionEvent.getType() != ExecutionEvent.Type.MojoStarted) {
        return;
      }
      try {
        handlerRan.compareAndSet(false, mojoStartedHandler.apply(executionEvent));
      } catch (Exception error) {
        handlerError.compareAndSet(null, error);
      }
    }
  }
}
