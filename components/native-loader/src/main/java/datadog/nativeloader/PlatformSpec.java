package datadog.nativeloader;

/**
 * PlatformSpec describes a native library "platform" -- including operating system, architecture,
 * and libc variation
 */
public abstract class PlatformSpec {
  public static final PlatformSpec defaultPlatformSpec() {
    return IntrospectPlatformSpec.INSTANCE;
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
