package datadog.nativeloader;

import datadog.environment.OperatingSystem;
import datadog.environment.OperatingSystem.Architecture;

/*
 * Default PlatformSpec used in dd-trace-java -- wraps detection code in component:environment
 */
final class IntrospectPlatformSpec extends PlatformSpec {
  static final PlatformSpec INSTANCE = new IntrospectPlatformSpec();

  @Override
  public boolean isLinux() {
    return OperatingSystem.isLinux();
  }

  @Override
  public boolean isMac() {
    return OperatingSystem.isMacOs();
  }

  @Override
  public boolean isWindows() {
    return OperatingSystem.isWindows();
  }

  @Override
  public boolean isMusl() {
    return OperatingSystem.isMusl();
  }

  @Override
  public boolean isAarch64() {
    return isArch(Architecture.ARM64);
  }

  @Override
  public boolean isArm32() {
    return isArch(Architecture.ARM);
  }

  @Override
  public boolean isX86_32() {
    return isArch(Architecture.X86);
  }

  @Override
  public boolean isX86_64() {
    return isArch(Architecture.X64);
  }

  static final boolean isArch(OperatingSystem.Architecture arch) {
    return (OperatingSystem.architecture() == arch);
  }

  @Override
  public int hashCode() {
    return IntrospectPlatformSpec.class.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return (obj instanceof IntrospectPlatformSpec);
  }
}
