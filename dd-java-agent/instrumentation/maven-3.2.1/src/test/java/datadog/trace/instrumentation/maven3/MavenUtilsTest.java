package datadog.trace.instrumentation.maven3;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.google.inject.Module;
import java.io.File;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.lifecycle.internal.GoalTask;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class MavenUtilsTest {

  @Parameterized.Parameters
  public static Collection<Object[]> surefireVersions() {
    return Arrays.asList(
        new Object[][] {
          {"2.17"}, {"2.21.0"}, {"3.0.0"}, {"3.5.0"},
        });
  }

  @ClassRule public static TemporaryFolder LOCAL_REPOSITORY = new TemporaryFolder();

  private final PlexusContainer container = createContainer();
  private final DefaultRepositorySystemSession repositorySystemSession =
      createRepositorySystemSession(container);

  private final String surefirePluginVersion;

  public MavenUtilsTest(String surefirePluginVersion) {
    this.surefirePluginVersion = surefirePluginVersion;
  }

  @Test
  public void testGetMojoConfigValueReturnsNullIfValueNotSet() throws Exception {
    MavenProject project = createMavenProject("samplePom.xml");
    MavenSession session = createMavenSession(project, Collections.emptyMap());
    MojoExecution surefireExecution = getSurefireExecution(session, project);

    String forkCount = MavenUtils.getConfigurationValue(session, surefireExecution, "forkCount");
    assertThat(forkCount, equalTo(null));
  }

  @Test
  public void testGetMojoConfigValueReturnsConfiguredValue() throws Exception {
    MavenProject project = createMavenProject("samplePom.xml");
    MavenSession session = createMavenSession(project, Collections.emptyMap());
    MojoExecution surefireExecution = getSurefireExecution(session, project);

    String threadCount =
        MavenUtils.getConfigurationValue(session, surefireExecution, "threadCount");
    assertThat(threadCount, equalTo("112"));
  }

  @Test
  public void testGetMojoConfigValueResolvesPropertyPlaceholders() throws Exception {
    MavenProject project = createMavenProject("samplePom.xml");
    MavenSession session = createMavenSession(project, Collections.emptyMap());
    MojoExecution surefireExecution = getSurefireExecution(session, project);

    String forkedProcessExitTimeoutInSeconds =
        MavenUtils.getConfigurationValue(
            session, surefireExecution, "forkedProcessTimeoutInSeconds");
    assertThat(forkedProcessExitTimeoutInSeconds, equalTo("887"));
  }

  @Test
  public void testGetMojoConfigValueResolvesPropertiesSuppliedViaCmdLine() throws Exception {
    MavenProject project = createMavenProject("samplePom.xml");
    MavenSession session =
        createMavenSession(
            project, Collections.singletonMap("surefire.parallel.timeout", "112233"));
    MojoExecution surefireExecution = getSurefireExecution(session, project);

    String parallelTestsTimeoutInSeconds =
        MavenUtils.getConfigurationValue(
            session, surefireExecution, "parallelTestsTimeoutInSeconds");
    assertThat(parallelTestsTimeoutInSeconds, equalTo("112233"));
  }

  @Test
  public void testGetArgLineResolvesLatePropertyPlaceholders() throws Exception {
    MavenProject project = createMavenProject("samplePom.xml");
    MavenSession session = createMavenSession(project, Collections.emptyMap());
    MojoExecution surefireExecution = getSurefireExecution(session, project);

    String argLine = MavenUtils.getArgLine(session, project, surefireExecution);
    assertThat(argLine, equalTo("-Xms128m -Xmx2g"));
  }

  @Test
  public void testGetEffectiveJvmFallbackUsesJvmProperty() throws Exception {
    MavenProject project = createMavenProject("samplePom.xml");
    MavenSession session = createMavenSession(project, Collections.emptyMap());
    MojoExecution surefireExecution = getSurefireExecution(session, project);

    String effectiveJvm = MavenUtils.getEffectiveJvmFallback(session, surefireExecution);
    assertThat(effectiveJvm, equalTo("jvm-config-property-value"));
  }

  // FIXME nikita: only available in surefire:3.0.0 or later, and only with latestTests, so need to
  // add assumptions
  @Test
  public void testGetEffectiveJvmFallbackUsesToolchains() throws Exception {
    MavenProject project = createMavenProject("samplePomJdkToolchain.xml");
    MavenSession session = createMavenSession(project, Collections.emptyMap());
    MojoExecution surefireExecution = getSurefireExecution(session, project);

    // FIXME nikita: use request.setUserToolchainsFile() on session execution request to provide a
    // custom toolchains file

    String effectiveJvm = MavenUtils.getEffectiveJvmFallback(session, surefireExecution);
    assertThat(effectiveJvm, equalTo("jvm-config-property-value"));
  }

  private PlexusContainer createContainer() {
    try {
      ClassWorld classWorld =
          new ClassWorld("plexus.core", Thread.currentThread().getContextClassLoader());
      ContainerConfiguration cc =
          (new DefaultContainerConfiguration())
              .setClassWorld(classWorld)
              .setClassPathScanning("index")
              .setAutoWiring(true)
              .setName("maven");
      return new DefaultPlexusContainer(cc, new Module[] {});

    } catch (PlexusContainerException e) {
      throw new RuntimeException(e);
    }
  }

  private DefaultRepositorySystemSession createRepositorySystemSession(PlexusContainer container) {
    try {
      RepositorySystem repositorySystem = container.lookup(RepositorySystem.class);
      DefaultRepositorySystemSession repositorySystemSession = new DefaultRepositorySystemSession();
      LocalRepository localRepo = new LocalRepository(LOCAL_REPOSITORY.getRoot());
      LocalRepositoryManager localRepositoryManager =
          repositorySystem.newLocalRepositoryManager(repositorySystemSession, localRepo);
      repositorySystemSession.setLocalRepositoryManager(localRepositoryManager);

      // propagate actual system properties so Maven can see them
      repositorySystemSession.setSystemProperties(System.getProperties());

      return repositorySystemSession;

    } catch (ComponentLookupException e) {
      throw new RuntimeException(e);
    }
  }

  private MavenProject createMavenProject(String pomPath)
      throws ComponentLookupException, URISyntaxException, ProjectBuildingException {
    File pomFile = new File(MavenUtilsTest.class.getResource(pomPath).toURI());

    ProjectBuildingRequest projectBuildingRequest = new DefaultProjectBuildingRequest();
    projectBuildingRequest.setRepositorySession(repositorySystemSession);

    // propagate actual system properties so Maven can see them
    projectBuildingRequest.setSystemProperties(System.getProperties());

    ProjectBuilder projectBuilder = container.lookup(ProjectBuilder.class);
    ProjectBuildingResult result = projectBuilder.build(pomFile, projectBuildingRequest);
    return result.getProject();
  }

  private MavenSession createMavenSession(
      MavenProject project, Map<String, String> commandLineProperties) {
    MavenExecutionRequest request = new DefaultMavenExecutionRequest();

    Properties systemProperties = new Properties();
    systemProperties.putAll(System.getProperties());
    request.setSystemProperties(systemProperties);

    Properties userProperties = new Properties();
    userProperties.putAll(commandLineProperties);
    request.setUserProperties(userProperties);

    MavenExecutionResult result = new DefaultMavenExecutionResult();
    MavenSession session = new MavenSession(container, repositorySystemSession, request, result);
    session.setCurrentProject(project);
    session.setProjects(Collections.singletonList(project));

    return session;
  }

  private MojoExecution getSurefireExecution(MavenSession session, MavenProject project)
      throws Exception {
    List<Object> tasks =
        Collections.singletonList(
            new GoalTask(
                String.format(
                    "org.apache.maven.plugins:maven-surefire-plugin:%s:test",
                    surefirePluginVersion)));

    List<MojoExecution> mojoExecutions = getMojoExecutions(session, project, tasks);
    assertThat(mojoExecutions.size(), equalTo(1));

    MojoExecution surefireExecution = mojoExecutions.iterator().next();
    assertThat(surefireExecution.getGoal(), equalTo("test"));

    Plugin plugin = surefireExecution.getPlugin();
    assertThat(plugin.getGroupId(), equalTo("org.apache.maven.plugins"));
    assertThat(plugin.getArtifactId(), equalTo("maven-surefire-plugin"));
    return surefireExecution;
  }

  private List<MojoExecution> getMojoExecutions(
      MavenSession session, MavenProject project, List<Object> tasks) throws Exception {
    LifecycleExecutionPlanCalculator planCalculator =
        container.lookup(LifecycleExecutionPlanCalculator.class);
    MavenExecutionPlan executionPlan =
        planCalculator.calculateExecutionPlan(session, project, tasks);
    return executionPlan.getMojoExecutions();
  }
}
