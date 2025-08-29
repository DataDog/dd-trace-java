package datadog.gradle.plugin.muzzle

import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
/**
 * muzzle task plugin which runs muzzle validation against a range of dependencies.
 */
class MuzzlePlugin implements Plugin<Project> {
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
        MuzzleMavenRepoUtils.dumpVersionRanges(project)
      }
      dependsOn compileMuzzle
    }

    project.tasks.register('mergeMuzzleReports', MuzzleTask) {
      description = "Merge generated version reports in one unique csv"
      doLast {
        MuzzleReportUtils.mergeReports(project)
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

    final RepositorySystem system = MuzzleMavenRepoUtils.newRepositorySystem()
    final RepositorySystemSession session = MuzzleMavenRepoUtils.newRepositorySystemSession(system)
    project.afterEvaluate {
      // use runAfter to set up task finalizers in version order
      TaskProvider<Task> runAfter = muzzleTask
      for (MuzzleDirective muzzleDirective : project.muzzle.directives) {
        project.getLogger().info("configured $muzzleDirective")

        if (muzzleDirective.coreJdk) {
          runAfter = MuzzlePluginK.addMuzzleTask(muzzleDirective, null, project, runAfter, muzzleBootstrap, muzzleTooling)
        } else {
          def range = MuzzleMavenRepoUtils.resolveVersionRange(muzzleDirective, system, session)
          for (Artifact singleVersion : MuzzleMavenRepoUtils.muzzleDirectiveToArtifacts(muzzleDirective, range)) {
            runAfter = MuzzlePluginK.addMuzzleTask(muzzleDirective, singleVersion, project, runAfter, muzzleBootstrap, muzzleTooling)
          }
          if (muzzleDirective.assertInverse) {
            for (MuzzleDirective inverseDirective : MuzzleMavenRepoUtils.inverseOf(muzzleDirective, system, session)) {
              def inverseRange = MuzzleMavenRepoUtils.resolveVersionRange(inverseDirective, system, session)
              for (Artifact singleVersion : (MuzzleMavenRepoUtils.muzzleDirectiveToArtifacts(inverseDirective, inverseRange))) {
                runAfter = MuzzlePluginK.addMuzzleTask(inverseDirective, singleVersion, project, runAfter, muzzleBootstrap, muzzleTooling)
              }
            }
          }
        }
      }
      def timingTask = project.tasks.register("muzzle-end") {
        doLast {
          long endTime = System.currentTimeMillis()
          MuzzleReportUtils.generateResultsXML(project, endTime - startTime)
        }
      }
      // last muzzle task to run
      runAfter.configure {
        finalizedBy(timingTask)
      }
    }
  }
}
