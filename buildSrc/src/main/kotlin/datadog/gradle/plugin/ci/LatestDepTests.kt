package datadog.gradle.plugin.ci

/** Whether a test task runs against the latest dependencies, so CI runs it with `allLatestDepTests`. */
fun isLatestDepTest(testTaskName: String): Boolean = testTaskName.contains("latest", ignoreCase = true)
