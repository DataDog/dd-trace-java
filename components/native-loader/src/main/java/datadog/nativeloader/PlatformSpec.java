package datadog.nativeloader;

import datadog.environment.OperatingSystem;
import datadog.environment.OperatingSystem.Architecture;

/**
 * PlatformSpec describes a native library "platform" -- including operating system, architecture,
 * and libc variation
 */
public abstract class PlatformSpec {
  public static final PlatformSpec defaultPlatformSpec() {
    return DefaultPlatformSpec.INSTANCE;
  }

  /** Is the target OS MacOS? */
  public abstract boolean isMac();

  /** Is the target OS Windows? */
  public abstract boolean isWindows();

  /** Is the target OS Linux? */
  public abstract boolean isLinux();

  /** Is the target OS unknown / not handled by {@link NativeLoader}? */
  public final boolean isUnknownOs() {
    return !this.isLinux() && !this.isMac() && !this.isWindows();
  }

  /** Is the target architecture x86-32 bit? */
  public abstract boolean isX86_32();

  /** Is the target architecture x86-64 bit? */
  public abstract boolean isX86_64();

  /** Is the target architecture ARM 32-bit? */
  public abstract boolean isArm32();

  /** Is the target architecture ARM 64-bit? */
  public abstract boolean isAarch64();

  /** Is the target architecture unknown / not handled by {@link NativeLoader}? */
  public final boolean isUnknownArch() {
    return !this.isX86_64() && !this.isAarch64() && !this.isX86_32() && !this.isArm32();
  }

  /** Is the target using MUSL libc? */
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
    return DefaultPlatformSpec.class.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return (obj instanceof DefaultPlatformSpec);
  }
}
