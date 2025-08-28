package datadog.gradle.plugin.muzzle

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
import org.eclipse.aether.util.version.GenericVersionScheme
import org.eclipse.aether.version.Version
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskProvider

import java.lang.reflect.Method
import java.util.function.BiFunction
/**
 * muzzle task plugin which runs muzzle validation against a range of dependencies.
 */
class MuzzlePlugin implements Plugin<Project> {
  /**
   * Remote repositories used to query version ranges and fetch dependencies
   */
  private static final List<RemoteRepository> MUZZLE_REPOS

  static {
    RemoteRepository central = new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build()

    String mavenProxyUrl = System.getenv("MAVEN_REPOSITORY_PROXY")

    if (mavenProxyUrl == null) {
      MUZZLE_REPOS = Collections.singletonList(central)
    } else {
      RemoteRepository proxy = new RemoteRepository.Builder("central-proxy", "default", mavenProxyUrl).build()
      MUZZLE_REPOS = Collections.unmodifiableList(Arrays.asList(proxy, central))
    }
  }

  static class TestedArtifact {
    final String instrumentation
    final String group
    final String module
    final Version lowVersion
    final Version highVersion

    TestedArtifact(String instrumentation, String group, String module, Version lowVersion, Version highVersion) {
      this.instrumentation = instrumentation
      this.group = group
      this.module = module
      this.lowVersion = lowVersion
      this.highVersion = highVersion
    }

    String key() {
      "$instrumentation:$group:$module"
    }
  }

  @Override
  void apply(Project project) {
    def childProjects = project.rootProject.getChildProjects().get('dd-java-agent').getChildProjects()
    def bootstrapProject = childProjects.get('agent-bootstrap')
    def toolingProject = childProjects.get('agent-tooling')
    project.extensions.create("muzzle", MuzzleExtension, project.objects)

    def muzzleBootstrap = project.configurations.register('muzzleBootstrap', {
      canBeConsumed: false
      canBeResolved: true
    })
    def muzzleTooling = project.configurations.register('muzzleTooling', {
      canBeConsumed: false
      canBeResolved: true
    })

    project.dependencies.add(muzzleBootstrap.name, bootstrapProject)
    project.dependencies.add(muzzleTooling.name, toolingProject)

    project.evaluationDependsOn ':dd-java-agent:agent-bootstrap'
    project.evaluationDependsOn ':dd-java-agent:agent-tooling'

    // compileMuzzle compiles all projects required to run muzzle validation.
    // Not adding group and description to keep this task from showing in `gradle tasks`.
    TaskProvider<Task> compileMuzzle = project.tasks.register('compileMuzzle') {
      it.dependsOn(project.tasks.withType(InstrumentTask))
      it.dependsOn bootstrapProject.tasks.named("compileJava")
      it.dependsOn bootstrapProject.tasks.named("compileMain_java11Java")
      it.dependsOn toolingProject.tasks.named("compileJava")
    }

    def muzzleTask = project.tasks.register('muzzle', MuzzleTask) {
      description = "Run instrumentation muzzle on compile time dependencies"
      doLast {
        if (!project.muzzle.directives.any { it.assertPass }) {
          project.getLogger().info('No muzzle pass directives configured. Asserting pass against instrumentation compile-time dependencies')
          assertMuzzle(muzzleBootstrap, muzzleTooling, project)
        }
      }
      dependsOn compileMuzzle
    }

    project.tasks.register('printReferences', MuzzleTask) {
      description = "Print references created by instrumentation muzzle"
      doLast {
        printMuzzle(project)
      }
      dependsOn compileMuzzle
    }

    project.tasks.register('generateMuzzleReport', MuzzleTask) {
      description = "Print instrumentation version report"
      doLast {
        dumpVersionRanges(project)
      }
      dependsOn compileMuzzle
    }

    project.tasks.register('mergeMuzzleReports', MuzzleTask) {
      description = "Merge generated version reports in one unique csv"
      doLast {
        mergeReports(project)
      }
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
      TaskProvider<Task> runAfter = muzzleTask
      for (MuzzleDirective muzzleDirective : project.muzzle.directives) {
        project.getLogger().info("configured $muzzleDirective")

        if (muzzleDirective.coreJdk) {
          runAfter = addMuzzleTask(muzzleDirective, null, project, runAfter, muzzleBootstrap, muzzleTooling)
        } else {
          def range = resolveVersionRange(muzzleDirective, system, session)
          for (Artifact singleVersion : muzzleDirectiveToArtifacts(muzzleDirective, range)) {
            runAfter = addMuzzleTask(muzzleDirective, singleVersion, project, runAfter, muzzleBootstrap, muzzleTooling)
          }
          if (muzzleDirective.assertInverse) {
            for (MuzzleDirective inverseDirective : inverseOf(muzzleDirective, system, session)) {
              def inverseRange = resolveVersionRange(inverseDirective, system, session)
              for (Artifact singleVersion : (muzzleDirectiveToArtifacts(inverseDirective, inverseRange))) {
                runAfter = addMuzzleTask(inverseDirective, singleVersion, project, runAfter, muzzleBootstrap, muzzleTooling)
              }
            }
          }
        }
      }
      def timingTask = project.tasks.register("muzzle-end") {
        doLast {
          long endTime = System.currentTimeMillis()
          generateResultsXML(project, endTime - startTime)
        }
      }
      // last muzzle task to run
      runAfter.configure {
        finalizedBy(timingTask)
      }
    }
  }

  static Version highest(Version a, Version b) {
    (a <=> b) > 0 ? a : b
  }

  static Version lowest(Version a, Version b) {
    (a <=> b) < 0 ? a : b
  }

  static Map resolveInstrumentationAndJarVersions(MuzzleDirective directive, ClassLoader cl,
                                                  Version lowVersion, Version highVersion) {

    Method listMethod = cl.loadClass('datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin')
      .getMethod('listInstrumentationNames', ClassLoader.class, String.class)

    Set<String> names = (Set<String>) listMethod.invoke(null, cl, directive.getName())
    Map<String, TestedArtifact> ret = [:]
    for (String n : names) {
      def testedArtifact = new TestedArtifact(n, directive.group, directive.module, lowVersion, highVersion)
      def value = ret.get(testedArtifact.key(), testedArtifact)
      ret.put(testedArtifact.key(), new TestedArtifact(value.instrumentation, value.group, value.module, lowest(lowVersion, value.lowVersion),
        highest(highVersion, value.highVersion)))
    }
    return ret
  }

  private static void mergeReports(Project project) {
    def dir = project.file("${project.rootProject.buildDir}/muzzle-deps-results")
    Map<String, TestedArtifact> map = new TreeMap<>()
    def versionScheme = new GenericVersionScheme()
    dir.eachFileMatch(~/.*\.csv/) { file ->
      file.eachLine { line, nb ->
        if (nb == 1) {
          // skip header
          return
        }
        def split = line.split(",")
        def parsed = new TestedArtifact(split[0], split[1], split[2], versionScheme.parseVersion(split[3]),
          versionScheme.parseVersion(split[4]))
        map.merge(parsed.key(), parsed, [
          apply: { TestedArtifact x, TestedArtifact y ->
            return new TestedArtifact(x.instrumentation, x.group, x.module, lowest(x.lowVersion, y.lowVersion), highest(x.highVersion, y.highVersion))
          }
        ] as BiFunction)
      }
    }

    dumpVersionsToCsv(project, map)
  }

  private static void dumpVersionRanges(Project project) {
    final RepositorySystem system = newRepositorySystem()
    final RepositorySystemSession session = newRepositorySystemSession(system)
    def versions = new TreeMap<String, TestedArtifact>()
    project.muzzle.directives.findAll { !((MuzzleDirective) it).isCoreJdk() && !((MuzzleDirective) it).isSkipFromReport() }.each {
      def range = resolveVersionRange(it as MuzzleDirective, system, session)
      def cp = project.sourceSets.main.runtimeClasspath
      def cl = new URLClassLoader(cp*.toURI()*.toURL() as URL[], null as ClassLoader)
      def partials = resolveInstrumentationAndJarVersions(it as MuzzleDirective, cl,
        range.lowestVersion, range.highestVersion)
      partials.each {
        versions.merge(it.getKey(), it.getValue(), [
          apply: { TestedArtifact x, TestedArtifact y ->
            return new TestedArtifact(x.instrumentation, x.group, x.module, lowest(x.lowVersion, y.lowVersion), highest(x.highVersion, y.highVersion))
          }
        ] as BiFunction)
      }
    }
    dumpVersionsToCsv(project, versions)
  }

  private static void dumpVersionsToCsv(Project project, SortedMap<String, TestedArtifact> versions) {
    def filename = project.path.replaceFirst('^:', '').replace(':', '_')
    def dir = project.file("${project.rootProject.buildDir}/muzzle-deps-results")
    dir.mkdirs()
    def file = project.file("${dir}/${filename}.csv")
    file.write "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n"
    file << versions.values().collect { [it.instrumentation, it.group, it.module, it.lowVersion.toString(), it.highVersion.toString()].join(",") }.join("\n")
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

  static VersionRangeResult resolveVersionRange(MuzzleDirective muzzleDirective, RepositorySystem system, RepositorySystemSession session) {
    final Artifact directiveArtifact = new DefaultArtifact(muzzleDirective.group, muzzleDirective.module, muzzleDirective.classifier ?: "", "jar", muzzleDirective.versions)

    final VersionRangeRequest rangeRequest = new VersionRangeRequest()
    rangeRequest.setRepositories(muzzleDirective.getRepositories(MUZZLE_REPOS))
    rangeRequest.setArtifact(directiveArtifact)
    return system.resolveVersionRange(session, rangeRequest)
  }

  /**
   * Convert a muzzle directive to a list of artifacts
   */
  private static Set<Artifact> muzzleDirectiveToArtifacts(MuzzleDirective muzzleDirective, VersionRangeResult rangeResult) {

    final Set<Version> versions = MuzzleVersionUtils.filterAndLimitVersions(
      rangeResult,
      muzzleDirective.skipVersions,
      muzzleDirective.includeSnapshots
    )

    final Set<Artifact> allVersionArtifacts = versions.collect { version ->
      new DefaultArtifact(muzzleDirective.group, muzzleDirective.module, muzzleDirective.classifier ?: "", "jar", version.toString())
    }.toSet()

    if (allVersionArtifacts.isEmpty()) {
      throw new GradleException("No muzzle artifacts found for $muzzleDirective.group:$muzzleDirective.module $muzzleDirective.versions $muzzleDirective.classifier")
    }

    return allVersionArtifacts
  }

  /**
   * Create a list of muzzle directives which assert the opposite of the given datadog.gradle.plugin.muzzle.MuzzleDirective.
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

    return MuzzleVersionUtils.filterAndLimitVersions(
      allRangeResult,
      muzzleDirective.skipVersions,
      muzzleDirective.includeSnapshots
    ).collect { version ->
      final MuzzleDirective inverseDirective = new MuzzleDirective()
      inverseDirective.name = muzzleDirective.name
      inverseDirective.group = muzzleDirective.group
      inverseDirective.module = muzzleDirective.module
      inverseDirective.versions = "$version"
      inverseDirective.assertPass = !muzzleDirective.assertPass
      inverseDirective.excludedDependencies = muzzleDirective.excludedDependencies
      inverseDirective.includeSnapshots = muzzleDirective.includeSnapshots
      inverseDirective
    }.toSet()
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
  private static TaskProvider<Task> addMuzzleTask(
    MuzzleDirective muzzleDirective,
    Artifact versionArtifact,
    Project instrumentationProject,
    TaskProvider<Task> runAfter,
    NamedDomainObjectProvider<Configuration> muzzleBootstrap,
    NamedDomainObjectProvider<Configuration> muzzleTooling
  ) {
    def muzzleTaskName
    if (muzzleDirective.coreJdk) {
      muzzleTaskName = "muzzle-Assert$muzzleDirective"
    } else {
      muzzleTaskName = "muzzle-Assert${muzzleDirective.assertPass ? "Pass" : "Fail"}-$versionArtifact.groupId-$versionArtifact.artifactId-$versionArtifact.version${muzzleDirective.name ? "-${muzzleDirective.getNameSlug()}" : ""}"
    }
    instrumentationProject.configurations.register(muzzleTaskName) { Configuration taskConfig ->
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

        taskConfig.dependencies.add(dep)
      }
      for (String additionalDependency : muzzleDirective.additionalDependencies) {
        taskConfig.dependencies.add(instrumentationProject.dependencies.create(additionalDependency) { dep ->
          for (String excluded : muzzleDirective.excludedDependencies) {
            String[] parts = excluded.split(':')
            dep.exclude group: parts[0], module: parts[1]
          }
          dep.transitive = true
        })
      }
    }

    def muzzleTask = instrumentationProject.tasks.register(muzzleTaskName, MuzzleTask) {
      doLast {
        assertMuzzle(muzzleBootstrap, muzzleTooling, instrumentationProject, muzzleDirective)
      }
    }

    runAfter.configure {
      finalizedBy(muzzleTask)
    }
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
}
