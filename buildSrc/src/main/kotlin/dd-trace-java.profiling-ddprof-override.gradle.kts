// Convention plugin for overriding ddprof dependency version with snapshot.
// When the root project has the property 'ddprofUseSnapshot' set, this plugin:
// 1. Reads the calculated snapshot version from root project
// 2. Overrides all ddprof dependencies to use the snapshot version
//
// Apply this plugin only to projects that depend on ddprof.
if (rootProject.hasProperty("ddprofUseSnapshot")) {
  val ddprofSnapshotVersion = rootProject.property("ddprofSnapshotVersion").toString()

  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "com.datadoghq" && requested.name == "ddprof") {
        useVersion(ddprofSnapshotVersion)
        because("Using ddprof snapshot version for integration testing")
      }
    }
  }

  logger.lifecycle("${project.name}: Configured to use ddprof SNAPSHOT version $ddprofSnapshotVersion")
}
