package datadog.smoke.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * No-op stand-in for the Android Gradle Plugin's {@code com.android.base} plugin.
 *
 * <p>Real AGP requires the Android SDK to be installed, which is not available in the smoke-test
 * environment. The CI Visibility build-level Android detection only checks whether a plugin
 * registered under the {@code com.android.base} id has been applied (see {@code
 * CiVisibilityGradleListener}); every AGP variant applies {@code com.android.base} transitively.
 * Registering an inert plugin under that exact id therefore exercises the detection, the
 * module→session tag propagation and the {@code is_android} telemetry faithfully, without pulling
 * in AGP or the SDK.
 */
public class AndroidBaseStandInPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    // intentionally empty: only the plugin id matters for detection
  }
}
