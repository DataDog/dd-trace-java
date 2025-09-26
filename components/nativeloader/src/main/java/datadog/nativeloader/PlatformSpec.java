package datadog.nativeloader;

import datadog.environment.OperatingSystem;

/**
 * Class that describes a native library "platform" -- including operating system, architecture, and
 * libc variation
 */
public abstract class PlatformSpec {
  public static final PlatformSpec defaultPlatformSpec() {
    return DefaultPlatformSpec.INSTANCE;
  }

  public abstract boolean isMac();

  public abstract boolean isWindows();

  public abstract boolean isLinux();

  public final boolean isUnknownOs() {
    return !this.isLinux() && !this.isMac() && !this.isWindows();
  }

  public abstract boolean isX86_32();

  public abstract boolean isX86_64();

  public abstract boolean isArm32();

  public abstract boolean isAarch64();

  public final boolean isUnknownArch() {
    return !this.isX86_64() && !this.isAarch64() && !this.isX86_32() && !this.isArm32();
  }

  public abstract boolean isMusl();
}

/*
 * Default PlatformSpec used in dd-trace-java -- wraps detection code in component:environment
 */
final class DefaultPlatformSpec extends PlatformSpec {
  static final PlatformSpec INSTANCE = new DefaultPlatformSpec();

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
    return OperatingSystem.isAarch64();
  }

  @Override
  public boolean isArm32() {
    return false;
  }

  @Override
  public boolean isX86_32() {
    return false;
  }

  @Override
  public boolean isX86_64() {
    return false;
  }

  @Override
  public int hashCode() {
    return DefaultPlatformSpec.class.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return (obj instanceof DefaultPlatformSpec);
  }
}
