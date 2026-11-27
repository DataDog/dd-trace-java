package datadog.gradle

/**
 * CI experiment marker. Changing buildSrc bytecode invalidates Gradle cache keys, so every arm of
 * the experiment runs the full test suite. Not for merge.
 */
internal const val CI_EXPERIMENT_MARKER = "ci-exp-2026-11-24"
