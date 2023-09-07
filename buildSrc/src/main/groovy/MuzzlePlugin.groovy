import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.version.Version
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.BuildInvocationDetails
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

import java.lang.reflect.Method
import java.util.regex.Pattern

/**
 * muzzle task plugin which runs muzzle validation against a range of dependencies.
 */
class MuzzlePlugin implements Plugin<Project> {
  /**
   * Select a random set of versions to test
   */
  private static final int RANGE_COUNT_LIMIT = 25
  /**
   * Remote repositories used to query version ranges and fetch dependencies
   */
  private static final List<RemoteRepository> MUZZLE_REPOS

  static {
    RemoteRepository central = new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build()
    // Only needed for restlet
    RemoteRepository restlet = new RemoteRepository.Builder("restlet", "default", "https://maven.restlet.talend.com/").build()
    // Only needed  for play-2.3
    RemoteRepository typesafe = new RemoteRepository.Builder("typesafe", "default", "https://repo.typesafe.com/typesafe/maven-releases/").build()
    MUZZLE_REPOS = Collections.unmodifiableList(Arrays.asList(central, restlet, typesafe))
  }

  @Override
  void apply(Project project) {
    def childProjects = project.rootProject.getChildProjects().get('dd-java-agent').getChildProjects()
    def bootstrapProject = childProjects.get('agent-bootstrap')
    def toolingProject = childProjects.get('agent-tooling')
    project.extensions.create("muzzle", MuzzleExtension, project.objects)

    def muzzleBootstrap = project.configurations.create('muzzleBootstrap', {
      canBeConsumed: false
      canBeResolved: true
    })
    def muzzleTooling = project.configurations.create('muzzleTooling', {
      canBeConsumed: false
      canBeResolved: true
    })
    project.dependencies.add('muzzleBootstrap', bootstrapProject)
    project.dependencies.add('muzzleTooling', toolingProject)

    project.evaluationDependsOn ':dd-java-agent:agent-bootstrap'
    project.evaluationDependsOn ':dd-java-agent:agent-tooling'

    // compileMuzzle compiles all projects required to run muzzle validation.
    // Not adding group and description to keep this task from showing in `gradle tasks`.
    def compileMuzzle = project.task('compileMuzzle')
    compileMuzzle.dependsOn(toolingProject.tasks.named("compileJava"))
    project.afterEvaluate {
      project.tasks.matching {
        it.name =~ /\Ainstrument(Main)?(_.+)?(Java|Scala|Kotlin)/
      }.all {
        compileMuzzle.dependsOn(it)
      }
    }
    compileMuzzle.dependsOn bootstrapProject.tasks.compileJava
    compileMuzzle.dependsOn bootstrapProject.tasks.compileMain_java11Java
    compileMuzzle.dependsOn toolingProject.tasks.compileJava

    project.task(['type': MuzzleTask], 'muzzle') {
      description = "Run instrumentation muzzle on compile time dependencies"
      doLast {
        if (!project.muzzle.directives.any { it.assertPass }) {
          project.getLogger().info('No muzzle pass directives configured. Asserting pass against instrumentation compile-time dependencies')
          assertMuzzle(muzzleBootstrap, muzzleTooling, project)
        }
      }
      dependsOn compileMuzzle
    }

    project.task(['type': MuzzleTask],'printReferences') {
      description = "Print references created by instrumentation muzzle"
      doLast {
        printMuzzle(project)
      }
      dependsOn compileMuzzle
    }

    def hasRelevantTask = project.gradle.startParameter.taskNames.any { taskName ->
      // removing leading ':' if present
      taskName = taskName.replaceFirst('^:', '')
      String muzzleTaskPath = project.path.replaceFirst('^:', '')
      return 'muzzle' == taskName || "${muzzleTaskPath}:muzzle" == taskName
    }
    if (!hasRelevantTask) {
      // Adding muzzle dependencies has a large config overhead. Stop unless muzzle is explicitly run.
      return
    }

    // We only get here if we are running muzzle, so let's start timing things
    long startTime = System.currentTimeMillis()

    final RepositorySystem system = newRepositorySystem()
    final RepositorySystemSession session = newRepositorySystemSession(system)

    project.afterEvaluate {
      // use runAfter to set up task finalizers in version order
      Task runAfter = project.tasks.muzzle
      // runLast is the last task to finish, so we can time the execution
      Task runLast = runAfter

      for (MuzzleDirective muzzleDirective : project.muzzle.directives) {
        project.getLogger().info("configured $muzzleDirective")

        if (muzzleDirective.coreJdk) {
          runLast = runAfter = addMuzzleTask(muzzleDirective, null, project, runAfter, muzzleBootstrap, muzzleTooling)
        } else {
          runLast = muzzleDirectiveToArtifacts(muzzleDirective, system, session).inject(runLast) { last, Artifact singleVersion ->
            runAfter = addMuzzleTask(muzzleDirective, singleVersion, project, runAfter, muzzleBootstrap, muzzleTooling)
          }
          if (muzzleDirective.assertInverse) {
            runLast = inverseOf(muzzleDirective, system, session).inject(runLast) { last1, MuzzleDirective inverseDirective ->
              muzzleDirectiveToArtifacts(inverseDirective, system, session).inject(last1) { last2, Artifact singleVersion ->
                runAfter = addMuzzleTask(inverseDirective, singleVersion, project, runAfter, muzzleBootstrap, muzzleTooling)
              }
            }
          }
        }
      }
      def timingTask = project.task("muzzle-end") {
        doLast {
          long endTime = System.currentTimeMillis()
          generateResultsXML(project, endTime - startTime)
        }
      }
      runLast.finalizedBy(timingTask)
    }
  }

  private static void generateResultsXML(Project project, long millis) {
    def seconds = (millis * 1.0) / 1000
    def name = "${project.path}:muzzle"
    def dirname = name.replaceFirst('^:', '').replace(':', '_')
    def dir = project.file("${project.rootProject.buildDir}/muzzle-test-results/$dirname")
    dir.mkdirs()
    def file = project.file("$dir/results.xml")
    file.text = """|<?xml version="1.0" encoding="UTF-8"?>
                   |<testsuite name="${name}" tests="1" id="0" time="${seconds}">
                   |  <testcase name="${name}" time="${seconds}">
                   |  </testcase>
                   |</testsuite>\n""".stripMargin()
  }

  static FileCollection createAgentClassPath(Project project) {
    FileCollection cp = project.files()
    project.getLogger().info("Creating agent classpath for $project")
    for (SourceSet sourceSet : project.sourceSets) {
      if (sourceSet.name.startsWith('main')) {
        cp += sourceSet.runtimeClasspath
      }
    }
    if (project.getLogger().isInfoEnabled()) {
      cp.forEach { project.getLogger().info("-- $it") }
    }
    return cp
  }

  static FileCollection createMuzzleClassPath(Project project, String muzzleTaskName) {
    FileCollection cp = project.files()
    project.getLogger().info("Creating muzzle classpath for $muzzleTaskName")
    if ('muzzle' == muzzleTaskName) {
      cp += project.configurations.compileClasspath
    } else {
      cp += project.configurations.getByName(muzzleTaskName)
    }
    if (project.getLogger().isInfoEnabled()) {
      cp.forEach { project.getLogger().info("-- $it") }
    }
    return cp
  }

  /**
   * Convert a muzzle directive to a list of artifacts
   */
  private static Set<Artifact> muzzleDirectiveToArtifacts(MuzzleDirective muzzleDirective, RepositorySystem system, RepositorySystemSession session) {
    final Artifact directiveArtifact = new DefaultArtifact(muzzleDirective.group, muzzleDirective.module, muzzleDirective.classifier ?: "", "jar", muzzleDirective.versions)

    final VersionRangeRequest rangeRequest = new VersionRangeRequest()
    rangeRequest.setRepositories(muzzleDirective.getRepositories(MUZZLE_REPOS))
    rangeRequest.setArtifact(directiveArtifact)
    final VersionRangeResult rangeResult = system.resolveVersionRange(session, rangeRequest)
    final Set<Version> versions = filterAndLimitVersions(rangeResult, muzzleDirective.skipVersions)

//    println "Range Request: " + rangeRequest
//    println "Range Result: " + rangeResult

    final Set<Artifact> allVersionArtifacts = versions.collect { version ->
      new DefaultArtifact(muzzleDirective.group, muzzleDirective.module, muzzleDirective.classifier ?: "", "jar", version.toString())
    }.toSet()

    if (allVersionArtifacts.isEmpty()) {
      throw new GradleException("No muzzle artifacts found for $muzzleDirective.group:$muzzleDirective.module $muzzleDirective.versions $muzzleDirective.classifier")
    }

    return allVersionArtifacts
  }

  /**
   * Create a list of muzzle directives which assert the opposite of the given MuzzleDirective.
   */
  private static Set<MuzzleDirective> inverseOf(MuzzleDirective muzzleDirective, RepositorySystem system, RepositorySystemSession session) {
    final Artifact allVersionsArtifact = new DefaultArtifact(muzzleDirective.group, muzzleDirective.module, "jar", "[,)")
    final Artifact directiveArtifact = new DefaultArtifact(muzzleDirective.group, muzzleDirective.module, "jar", muzzleDirective.versions)

    def repos = muzzleDirective.getRepositories(MUZZLE_REPOS)
    final VersionRangeRequest allRangeRequest = new VersionRangeRequest()
    allRangeRequest.setRepositories(repos)
    allRangeRequest.setArtifact(allVersionsArtifact)
    final VersionRangeResult allRangeResult = system.resolveVersionRange(session, allRangeRequest)

    final VersionRangeRequest rangeRequest = new VersionRangeRequest()
    rangeRequest.setRepositories(repos)
    rangeRequest.setArtifact(directiveArtifact)
    final VersionRangeResult rangeResult = system.resolveVersionRange(session, rangeRequest)
    final Set<Version> versions = rangeResult.versions.toSet()
    allRangeResult.versions.removeAll(versions)

    return filterAndLimitVersions(allRangeResult, muzzleDirective.skipVersions).collect { version ->
      final MuzzleDirective inverseDirective = new MuzzleDirective()
      inverseDirective.name = muzzleDirective.name
      inverseDirective.group = muzzleDirective.group
      inverseDirective.module = muzzleDirective.module
      inverseDirective.versions = "$version"
      inverseDirective.assertPass = !muzzleDirective.assertPass
      inverseDirective.excludedDependencies = muzzleDirective.excludedDependencies
      inverseDirective
    }.toSet()
  }

  private static Set<Version> filterAndLimitVersions(VersionRangeResult result, Set<String> skipVersions) {
    return limitLargeRanges(result, filterVersion(result.versions.toSet(), skipVersions), skipVersions)
  }

  private static Set<Version> limitLargeRanges(VersionRangeResult result, Set<Version> versions, Set<String> skipVersions) {
    if (versions.size() <= 1) {
      return versions
    }

    List<Version> versionsCopy = new ArrayList<>(versions)
    def beforeSize = versionsCopy.size()
    versionsCopy.removeAll(skipVersions)
    VersionSet versionSet = new VersionSet(versionsCopy)
    versionsCopy = versionSet.lowAndHighForMajorMinor.toList()
    Collections.shuffle(versionsCopy)
    def afterSize = versionsCopy.size()
    while (RANGE_COUNT_LIMIT <= afterSize) {
      Version version = versionsCopy.pop()
      if (version == result.lowestVersion || version == result.highestVersion) {
        versionsCopy.add(version)
      } else {
        afterSize -= 1
      }
    }
    if (beforeSize - afterSize > 0) {
      println "Muzzle skipping ${beforeSize - afterSize} versions"
    }

    return versionsCopy.toSet()
  }

  /**
   * Configure a muzzle task to pass or fail a given version.
   *
   * @param assertPass If true, assert that muzzle validation passes
   * @param versionArtifact version to assert against.
   * @param instrumentationProject instrumentation being asserted against.
   * @param runAfter Task which runs before the new muzzle task.
   * @param bootstrapProject Agent bootstrap project.
   * @param toolingProject Agent tooling project.
   *
   * @return The created muzzle task.
   */
  private static Task addMuzzleTask(MuzzleDirective muzzleDirective, Artifact versionArtifact, Project instrumentationProject, Task runAfter, Configuration muzzleBootstrap, Configuration muzzleTooling) {
    def taskName
    if (muzzleDirective.coreJdk) {
      taskName = "muzzle-Assert$muzzleDirective"
    } else {
      taskName = "muzzle-Assert${muzzleDirective.assertPass ? "Pass" : "Fail"}-$versionArtifact.groupId-$versionArtifact.artifactId-$versionArtifact.version${muzzleDirective.name ? "-${muzzleDirective.getNameSlug()}" : ""}"
    }
    def config = instrumentationProject.configurations.create(taskName)

    if (!muzzleDirective.coreJdk) {
      def depId = "$versionArtifact.groupId:$versionArtifact.artifactId:$versionArtifact.version"
      if (versionArtifact.classifier) {
        depId += ":" + versionArtifact.classifier
      }
      def dep = instrumentationProject.dependencies.create(depId) {
        transitive = true
      }
      // The following optional transitive dependencies are brought in by some legacy module such as log4j 1.x but are no
      // longer bundled with the JVM and have to be excluded for the muzzle tests to be able to run.
      dep.exclude group: 'com.sun.jdmk', module: 'jmxtools'
      dep.exclude group: 'com.sun.jmx', module: 'jmxri'
      // Also exclude specifically excluded dependencies
      for (String excluded : muzzleDirective.excludedDependencies) {
        String[] parts = excluded.split(':')
        dep.exclude group: parts[0], module: parts[1]
      }

      config.dependencies.add(dep)
    }
    for (String additionalDependency : muzzleDirective.additionalDependencies) {
      config.dependencies.add(instrumentationProject.dependencies.create(additionalDependency) {
        transitive = true
      })
    }

    def muzzleTask = instrumentationProject.task(['type': MuzzleTask], taskName) {
      doLast {
        assertMuzzle(muzzleBootstrap, muzzleTooling, instrumentationProject, muzzleDirective)
      }
    }

    runAfter.finalizedBy(muzzleTask)
    muzzleTask
  }

  /**
   * Create muzzle's repository system
   */
  private static RepositorySystem newRepositorySystem() {
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator()
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class)
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class)

    return locator.getService(RepositorySystem.class)
  }

  /**
   * Create muzzle's repository system session
   */
  private static RepositorySystemSession newRepositorySystemSession(RepositorySystem system) {
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession()

    def tempDir = File.createTempDir()
    tempDir.deleteOnExit()
    LocalRepository localRepo = new LocalRepository(tempDir)
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo))

    return session
  }

  private static final Pattern GIT_SHA_PATTERN = Pattern.compile('^.*-[0-9a-f]{7,}$')
  private static final Pattern END_NMN_PATTERN = Pattern.compile('^.*\\.[0-9]+[mM][0-9]+$')

  /**
   * Filter out snapshot-type builds from versions list.
   */
  private static filterVersion(Set<Version> list, Set<String> skipVersions) {
    list.removeIf {
      def version = it.toString().toLowerCase(Locale.ROOT)
      return version.endsWith("-snapshot") ||
        version.contains("rc") ||
        version.contains(".cr") ||
        version.contains("alpha") ||
        version.contains("beta") ||
        version.contains("-b") ||
        version.contains(".m") ||
        version.contains("-m") ||
        version.contains("-dev") ||
        version.contains("-ea") ||
        version.contains("-atlassian-") ||
        version.contains("public_draft") ||
        version.contains("-cr") ||
        version.contains("-preview") ||
        skipVersions.contains(version) ||
        version.matches(END_NMN_PATTERN) ||
        version.matches(GIT_SHA_PATTERN)
    }
    return list
  }
}

// plugin extension classes

/**
 * A pass or fail directive for a single dependency.
 */
class MuzzleDirective {

  /**
   * Name is optional and is used to further define the scope of a directive. The motivation for this is that this
   * plugin creates a config for each of the dependencies under test with name '...-<group_id>-<artifact_id>-<version>'.
   * The problem is that if we want to test multiple times the same configuration under different conditions, e.g.
   * with different extra dependencies, the plugin would throw an error as it would try to create several times the
   * same config. This property can be used to differentiate those config names for different directives.
   */
  String name

  String group
  String module
  String classifier
  String versions
  Set<String> skipVersions = new HashSet<>()
  List<String> additionalDependencies = new ArrayList<>()
  List<RemoteRepository> additionalRepositories = new ArrayList<>()
  List<String> excludedDependencies = new ArrayList<>()
  boolean assertPass
  boolean assertInverse = false
  boolean coreJdk = false
  String javaVersion

  void coreJdk(version = null) {
    coreJdk = true
    javaVersion = version
  }

  /**
   * Adds extra dependencies to the current muzzle test.
   *
   * @param compileString An extra dependency in the gradle canonical form: '<group_id>:<artifact_id>:<version_id>'.
   */
  void extraDependency(String compileString) {
    additionalDependencies.add(compileString)
  }

  /**
   * Adds extra repositories to the current muzzle test.
   *
   * @param id the repository id
   * @param url the url of the repository
   * @param type the type of repository, defaults to "default"
   */
  void extraRepository(String id, String url, String type = "default") {
    additionalRepositories.add(new RemoteRepository.Builder(id, type, url).build())
  }

  /**
   * Adds transitive dependencies to exclude from the current muzzle test.
   *
   * @param excludeString A dependency in the gradle canonical form: '<group_id>:<artifact_id>'.
   */
  void excludeDependency(String excludeString) {
    excludedDependencies.add(excludeString)
  }

  /**
   * Get the list of repositories to use for this muzzle directive.
   *
   * @param defaults the default repositories
   * @return a list of the default repositories followed by any additional repositories
   */
  List<RemoteRepository> getRepositories(List<RemoteRepository> defaults) {
    if (additionalRepositories.isEmpty()) {
      return defaults
    } else {
      def repositories = new ArrayList<RemoteRepository>(defaults.size() + additionalRepositories.size())
      repositories.addAll(defaults)
      repositories.addAll(additionalRepositories)
      return repositories
    }
  }

  /**
   * Slug of directive name.
   *
   * @return A slug of the name or an empty string if name is empty. E.g. 'My Directive' --> 'My-Directive'
   */
  String getNameSlug() {
    if (null == name) {
      return ""
    }

    return name.trim().replaceAll("[^a-zA-Z0-9]+", "-")
  }

  String toString() {
    if (coreJdk) {
      return "${assertPass ? 'Pass' : 'Fail'}-core-jdk"
    } else {
      return "${assertPass ? 'pass' : 'fail'} $group:$module:$versions"
    }
  }
}

/**
 * Muzzle extension containing all pass and fail directives.
 */
class MuzzleExtension {
  final List<MuzzleDirective> directives = new ArrayList<>()
  private final ObjectFactory objectFactory
  private final List<RemoteRepository> additionalRepositories = new ArrayList<>()

  @javax.inject.Inject
  MuzzleExtension(final ObjectFactory objectFactory) {
    this.objectFactory = objectFactory
  }

  void pass(Action<? super MuzzleDirective> action) {
    final MuzzleDirective pass = objectFactory.newInstance(MuzzleDirective)
    action.execute(pass)
    postConstruct(pass)
    pass.assertPass = true
    directives.add(pass)
  }

  void fail(Action<? super MuzzleDirective> action) {
    final MuzzleDirective fail = objectFactory.newInstance(MuzzleDirective)
    action.execute(fail)
    postConstruct(fail)
    fail.assertPass = false
    directives.add(fail)
  }

  /**
   * Adds extra repositories to the current muzzle section. Repositories will only be added to directives
   * created after this.
   *
   * @param id the repository id
   * @param url the url of the repository
   * @param type the type of repository, defaults to "default"
   */
  void extraRepository(String id, String url, String type = "default") {
    additionalRepositories.add(new RemoteRepository.Builder(id, type, url).build())
  }


  private postConstruct(MuzzleDirective directive) {
    // Make skipVersions case insensitive.
    directive.skipVersions = directive.skipVersions.collect {
      it.toLowerCase(Locale.ROOT)
    }
    // Add existing repositories
    directive.additionalRepositories.addAll(additionalRepositories)
  }
}

abstract class MuzzleTask extends DefaultTask {
  {
    group = 'Muzzle'
  }

  @javax.inject.Inject
  abstract JavaToolchainService getJavaToolchainService()

  @javax.inject.Inject
  abstract BuildInvocationDetails getInvocationDetails()

  @javax.inject.Inject
  abstract WorkerExecutor getWorkerExecutor()

  void assertMuzzle(Configuration muzzleBootstrap,
                    Configuration muzzleTooling,
                    Project instrumentationProject,
                    MuzzleDirective muzzleDirective = null)
  {
    def workQueue
    String javaVersion = muzzleDirective?.javaVersion
    if (javaVersion) {
      def javaLauncher = javaToolchainService.launcherFor { spec ->
        spec.languageVersion.set(JavaLanguageVersion.of(javaVersion))
      }.get()
      workQueue = workerExecutor.processIsolation { spec ->
        spec.forkOptions { fork ->
          fork.executable = javaLauncher.executablePath
        }
      }
    } else {
      workQueue = workerExecutor.noIsolation()
    }
    workQueue.submit(MuzzleAction.class, parameters -> {
      parameters.buildStartedTime.set(invocationDetails.buildStartedTime)
      parameters.bootstrapClassPath.setFrom(muzzleBootstrap)
      parameters.toolingClassPath.setFrom(muzzleTooling)
      parameters.instrumentationClassPath.setFrom(MuzzlePlugin.createAgentClassPath(instrumentationProject))
      parameters.testApplicationClassPath.setFrom(MuzzlePlugin.createMuzzleClassPath(instrumentationProject, name))
      if (muzzleDirective) {
        parameters.assertPass.set(muzzleDirective.assertPass)
        parameters.muzzleDirective.set(muzzleDirective.name ?: muzzleDirective.module)
      } else {
        parameters.assertPass.set(true)
      }
    })
  }

  void printMuzzle(Project instrumentationProject) {
    FileCollection cp = instrumentationProject.sourceSets.main.runtimeClasspath
    ClassLoader cl = new URLClassLoader(cp*.toURI()*.toURL() as URL[], null as ClassLoader)
    Method printMethod = cl.loadClass('datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin')
      .getMethod('printMuzzleReferences', ClassLoader.class)
    printMethod.invoke(null, cl)
  }
}

interface MuzzleWorkParameters extends WorkParameters {
  Property<Long> getBuildStartedTime()
  ConfigurableFileCollection getBootstrapClassPath()
  ConfigurableFileCollection getToolingClassPath()
  ConfigurableFileCollection getInstrumentationClassPath()
  ConfigurableFileCollection getTestApplicationClassPath()
  Property<Boolean> getAssertPass()
  Property<String> getMuzzleDirective()
}

abstract class MuzzleAction implements WorkAction<MuzzleWorkParameters> {
  private static final Object lock = new Object()
  private static ClassLoader bootCL
  private static ClassLoader toolCL
  private static volatile long lastBuildStamp

  @Override
  void execute() {
    // reset shared class-loaders each time a new build starts
    long buildStamp = parameters.buildStartedTime.get()
    if (lastBuildStamp < buildStamp || !bootCL || !toolCL) {
      synchronized (lock) {
        if (lastBuildStamp < buildStamp || !bootCL || !toolCL) {
          bootCL = createClassLoader(parameters.bootstrapClassPath)
          toolCL = createClassLoader(parameters.toolingClassPath, bootCL)
          lastBuildStamp = buildStamp
        }
      }
    }
    ClassLoader instCL = createClassLoader(parameters.instrumentationClassPath, toolCL)
    ClassLoader testCL = createClassLoader(parameters.testApplicationClassPath, bootCL)
    boolean assertPass = parameters.assertPass.get()
    String muzzleDirective = parameters.muzzleDirective.getOrNull()
    Method assertionMethod = instCL.loadClass('datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin')
      .getMethod('assertInstrumentationMuzzled', ClassLoader, ClassLoader, boolean, String)
    assertionMethod.invoke(null, instCL, testCL, assertPass, muzzleDirective)
  }

  static ClassLoader createClassLoader(cp, parent = ClassLoader.systemClassLoader) {
    return new URLClassLoader(cp*.toURI()*.toURL() as URL[], parent as ClassLoader)
  }
}
