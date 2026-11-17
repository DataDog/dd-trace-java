package datadog.smoketest

import org.spockframework.runtime.extension.ExtensionAnnotation

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Spock test methods annotated with this will be executed last.
 * This is useful for tests that need to wait for some test to settle while other tests run (e.g. telemetry), so it is
 * more efficient to run them at the end.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD])
@ExtensionAnnotation(RunLastExtension)
@interface RunLast {
}
