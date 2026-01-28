/*
 * This plugin enables dependency locking.
 *
 * The goal is to be able to later rebuild any version, by pinning floating versions.
 * It will also help IDEs not having to re-index any latest library release.
 * Pinned versions will be updated by the CI on a weekly basis.
 *
 * Pinned version can be updated using: ./gradlew resolveAndLockAll --write-locks
 *
 * See https://docs.gradle.org/current/userguide/dependency_locking.html
 */

// Configure dependency locking after java plugin is fully applied
// to avoid conflicts with Gradle 9's test suite creation
pluginManager.withPlugin("java") {
  project.dependencyLocking {
    lockAllConfigurations()
    // lockmode set to LENIENT because there are resolution
    // errors in the build with an apiguardian dependency.
    // See: https://docs.gradle.org/current/userguide/dependency_locking.html for more info
    lockMode = LockMode.LENIENT
  }
}

tasks.register("resolveAndLockAll") {
  notCompatibleWithConfigurationCache("Filters configurations at execution time")
  doFirst {
    require(gradle.startParameter.isWriteDependencyLocks)
  }
  doLast {
    configurations.filter {
      // Add any custom filtering on the configurations to be resolved:
      // - Should be resolvable
      // - Should skip Scala related task (https://github.com/ben-manes/gradle-versions-plugin/issues/816#issuecomment-1872264880)
      it.isCanBeResolved && !it.name.startsWith("incrementalScalaAnalysis")
    }.forEach { it.resolve() }
  }
}
