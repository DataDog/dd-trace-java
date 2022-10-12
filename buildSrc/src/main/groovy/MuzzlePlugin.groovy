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
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.SourceSet

import java.lang.reflect.Method
import java.security.SecureClassLoader
import java.util.concurrent.atomic.AtomicReference
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
  private static final AtomicReference<ClassLoader> TOOLING_LOADER = new AtomicReference<>()

  static {
    RemoteRepository central = new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build()
    RemoteRepository sonatype = new RemoteRepository.Builder("sonatype", "default", "https://oss.sonatype.org/content/repositories/releases/").build()
    RemoteRepository jcenter = new RemoteRepository.Builder("jcenter", "default", "https://jcenter.bintray.com/").build()
    RemoteRepository spring = new RemoteRepository.Builder("spring", "default", "https://repo.spring.io/libs-release/").build()
    RemoteRepository jboss = new RemoteRepository.Builder("jboss", "default", "https://repository.jboss.org/nexus/content/repositories/releases/").build()
    RemoteRepository typesafe = new RemoteRepository.Builder("typesafe", "default", "https://repo.typesafe.com/typesafe/releases").build()
    RemoteRepository akka = new RemoteRepository.Builder("akka", "default", "https://dl.bintray.com/akka/maven/").build()
    RemoteRepository atlassian = new RemoteRepository.Builder("atlassian", "default", "https://maven.atlassian.com/content/repositories/atlassian-public/").build()
//    MUZZLE_REPOS = Arrays.asList(central, sonatype, jcenter, spring, jboss, typesafe, akka, atlassian)
    MUZZLE_REPOS = Collections.unmodifiableList(Arrays.asList(central, jcenter))
  }

  @Override
  void apply(Project project) {
    def childProjects = project.rootProject.getChildProjects().get('dd-java-agent').getChildProjects()
    def bootstrapProject = childProjects.get('agent-bootstrap')
    def toolingProject = childProjects.get('agent-tooling')
    project.extensions.create("muzzle", MuzzleExtension, project.objects)

    // compileMuzzle compiles all projects required to run muzzle validation.
    // Not adding group and description to keep this task from showing in `gradle tasks`.
    def compileMuzzle = project.task('compileMuzzle')
    toolingProject.afterEvaluate {
      compileMuzzle.dependsOn(toolingProject.tasks.named("compileJava"))
    }
    project.afterEvaluate {
      project.tasks.matching { it.name in ['instrumentJava', 'instrumentScala', 'instrumentKotlin'] }.all {
        compileMuzzle.dependsOn(it)
      }
    }

    def muzzle = project.task('muzzle') {
      group = 'Muzzle'
      description = "Run instrumentation muzzle on compile time dependencies"
      doLast {
        if (!project.muzzle.directives.any { it.assertPass }) {
          project.getLogger().info('No muzzle pass directives configured. Asserting pass against instrumentation compile-time dependencies')
          final ClassLoader userCL = createCompileDepsClassLoader(project, bootstrapProject)
          final ClassLoader instrumentationCL = createInstrumentationClassloader(project, toolingProject)
          Method assertionMethod = instrumentationCL.loadClass('datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin')
            .getMethod('assertInstrumentationMuzzled', ClassLoader.class, ClassLoader.class, boolean.class, String.class)
          assertionMethod.invoke(null, instrumentationCL, userCL, true, null)
        }
        println "Muzzle executing for $project"
      }
    }
    def printReferences = project.task('printReferences') {
      group = 'Muzzle'
      description = "Print references created by instrumentation muzzle"
      doLast {
        final ClassLoader instrumentationCL = createInstrumentationClassloader(project, toolingProject)
        Method assertionMethod = instrumentationCL.loadClass('datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin')
          .getMethod('printMuzzleReferences', ClassLoader.class)
        assertionMethod.invoke(null, instrumentationCL)
      }
    }
    bootstrapProject.afterEvaluate {
      compileMuzzle.dependsOn it.tasks.compileJava
      compileMuzzle.dependsOn it.tasks.compileMain_java11Java
    }
    toolingProject.afterEvaluate {
      compileMuzzle.dependsOn it.tasks.compileJava
    }
    muzzle.dependsOn(compileMuzzle)
    printReferences.dependsOn(compileMuzzle)

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
          runLast = runAfter = addMuzzleTask(muzzleDirective, null, project, runAfter, bootstrapProject, toolingProject)
        } else {
          runLast = muzzleDirectiveToArtifacts(muzzleDirective, system, session).inject(runLast) { last, Artifact singleVersion ->
            runAfter = addMuzzleTask(muzzleDirective, singleVersion, project, runAfter, bootstrapProject, toolingProject)
          }
          if (muzzleDirective.assertInverse) {
            runLast = inverseOf(muzzleDirective, system, session).inject(runLast) { last1, MuzzleDirective inverseDirective ->
              muzzleDirectiveToArtifacts(inverseDirective, system, session).inject(last1) { last2, Artifact singleVersion ->
                runAfter = addMuzzleTask(inverseDirective, singleVersion, project, runAfter, bootstrapProject, toolingProject)
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

  private static ClassLoader getOrCreateToolingLoader(Project toolingProject) {
    synchronized (TOOLING_LOADER) {
      final ClassLoader toolingLoader = TOOLING_LOADER.get()
      if (toolingLoader == null) {
        Set<URL> ddUrls = new HashSet<>()
        toolingProject.getLogger().info('creating classpath for agent-tooling')
        for (File f : toolingProject.sourceSets.main.runtimeClasspath.getFiles()) {
          toolingProject.getLogger().info('--' + f)
          ddUrls.add(f.toURI().toURL())
        }
        def loader = new URLClassLoader(ddUrls.toArray(new URL[0]), (ClassLoader) null)
        assert TOOLING_LOADER.compareAndSet(null, loader)
        return TOOLING_LOADER.get()
      } else {
        return toolingLoader
      }
    }
  }

  /**
   * Create a classloader with core agent classes and project instrumentation on the classpath.
   */
  private static ClassLoader createInstrumentationClassloader(Project project, Project toolingProject) {
    project.getLogger().info("Creating instrumentation classpath for: " + project.getName())
    Set<URL> ddUrls = new HashSet<>()
    for (File f : project.sourceSets.main.runtimeClasspath.getFiles()) {
      project.getLogger().info('--' + f)
      ddUrls.add(f.toURI().toURL())
    }
    return new URLClassLoader(ddUrls.toArray(new URL[0]), getOrCreateToolingLoader(toolingProject))
  }

  /**
   * Create a classloader with all compile-time dependencies on the classpath
   */
  private static ClassLoader createCompileDepsClassLoader(Project project, Project bootstrapProject) {
    List<URL> userUrls = new ArrayList<>()
    project.getLogger().info("Creating compile-time classpath for: " + project.getName())
    for (File f : project.configurations.compileClasspath.getFiles()) {
      project.getLogger().info('--' + f)
      userUrls.add(f.toURI().toURL())
    }
    for (File f : bootstrapProject.sourceSets.main.runtimeClasspath.getFiles()) {
      project.getLogger().info('--' + f)
      userUrls.add(f.toURI().toURL())
    }
    return new URLClassLoader(userUrls.toArray(new URL[0]), (ClassLoader) null)
  }

  /**
   * Create a classloader with dependencies for a single muzzle task.
   */
  private static ClassLoader createClassLoaderForTask(Project project, Project bootstrapProject, String muzzleTaskName) {
    final List<URL> userUrls = new ArrayList<>()

    project.getLogger().info("Creating task classpath")
    project.configurations.getByName(muzzleTaskName).resolvedConfiguration.files.each { File jarFile ->
      project.getLogger().info("-- Added to instrumentation classpath: $jarFile")
      userUrls.add(jarFile.toURI().toURL())
    }

    for (SourceSet sourceSet: bootstrapProject.sourceSets) {
      if (sourceSet.name.startsWith('main')) {
        for (File f : sourceSet.runtimeClasspath.getFiles()) {
          project.getLogger().info("-- Added to instrumentation bootstrap classpath: $f")
          userUrls.add(f.toURI().toURL())
        }
      }
    }
    return new URLClassLoader(userUrls.toArray(new URL[0]), (ClassLoader) null)
  }

  /**
   * Convert a muzzle directive to a list of artifacts
   */
  private static Set<Artifact> muzzleDirectiveToArtifacts(MuzzleDirective muzzleDirective, RepositorySystem system, RepositorySystemSession session) {
    final Artifact directiveArtifact = new DefaultArtifact(muzzleDirective.group, muzzleDirective.module, "jar", muzzleDirective.versions)

    final VersionRangeRequest rangeRequest = new VersionRangeRequest()
    rangeRequest.setRepositories(muzzleDirective.getRepositories(MUZZLE_REPOS))
    rangeRequest.setArtifact(directiveArtifact)
    final VersionRangeResult rangeResult = system.resolveVersionRange(session, rangeRequest)
    final Set<Version> versions = filterAndLimitVersions(rangeResult, muzzleDirective.skipVersions)

//    println "Range Request: " + rangeRequest
//    println "Range Result: " + rangeResult

    final Set<Artifact> allVersionArtifacts = versions.collect { version ->
      new DefaultArtifact(muzzleDirective.group, muzzleDirective.module, "jar", version.toString())
    }.toSet()

    if (allVersionArtifacts.isEmpty()) {
      throw new GradleException("No muzzle artifacts found for $muzzleDirective.group:$muzzleDirective.module $muzzleDirective.versions")
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
  private static Task addMuzzleTask(MuzzleDirective muzzleDirective, Artifact versionArtifact, Project instrumentationProject, Task runAfter, Project bootstrapProject, Project toolingProject) {
    def taskName
    if (muzzleDirective.coreJdk) {
      taskName = "muzzle-Assert$muzzleDirective"
    } else {
      taskName = "muzzle-Assert${muzzleDirective.assertPass ? "Pass" : "Fail"}-$versionArtifact.groupId-$versionArtifact.artifactId-$versionArtifact.version${muzzleDirective.name ? "-${muzzleDirective.getNameSlug()}" : ""}"
    }
    def config = instrumentationProject.configurations.create(taskName)

    if (!muzzleDirective.coreJdk) {
      def dep = instrumentationProject.dependencies.create("$versionArtifact.groupId:$versionArtifact.artifactId:$versionArtifact.version") {
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

    def muzzleTask = instrumentationProject.task(taskName) {
      doLast {
        final ClassLoader instrumentationCL = createInstrumentationClassloader(instrumentationProject, toolingProject)
        def ccl = Thread.currentThread().contextClassLoader
        def bogusLoader = new SecureClassLoader() {
          @Override
          String toString() {
            return "bogus"
          }
        }
        Thread.currentThread().contextClassLoader = bogusLoader
        final ClassLoader userCL = createClassLoaderForTask(instrumentationProject, bootstrapProject, taskName)
        try {
          // find all instrumenters, get muzzle, and assert
          Method assertionMethod = instrumentationCL.loadClass('datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin')
            .getMethod('assertInstrumentationMuzzled', ClassLoader.class, ClassLoader.class, boolean.class, String.class)
          assertionMethod.invoke(null, instrumentationCL, userCL, muzzleDirective.assertPass, muzzleDirective.name ?: muzzleDirective.module)
        } finally {
          Thread.currentThread().contextClassLoader = ccl
        }

        for (Thread thread : Thread.getThreads()) {
          if (thread.contextClassLoader == bogusLoader || thread.contextClassLoader == instrumentationCL || thread.contextClassLoader == userCL) {
            throw new GradleException("Task $taskName has spawned a thread: $thread with classloader $thread.contextClassLoader. This will prevent GC of dynamic muzzle classes. Aborting muzzle run.")
          }
        }
      }
    }
    runAfter.finalizedBy(muzzleTask)
    return muzzleTask
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
      def version = it.toString().toLowerCase()
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
  String versions
  Set<String> skipVersions = new HashSet<>()
  List<String> additionalDependencies = new ArrayList<>()
  List<RemoteRepository> additionalRepositories = new ArrayList<>();
  List<String> excludedDependencies = new ArrayList<>();
  boolean assertPass
  boolean assertInverse = false
  boolean coreJdk = false

  void coreJdk() {
    coreJdk = true
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
      it.toLowerCase()
    }
    // Add existing repositories
    directive.additionalRepositories.addAll(additionalRepositories)
  }
}
