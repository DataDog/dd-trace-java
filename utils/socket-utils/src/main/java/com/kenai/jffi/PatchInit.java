package com.kenai.jffi;

import com.kenai.jffi.internal.StubLoader;
import datadog.environment.JavaVirtualMachine;
import datadog.environment.SystemProperties;

/** Replacement Init class that loads StubLoader from the same (isolating) class-loader. */
final class PatchInit {
  private static volatile boolean loaded;

  private PatchInit() {}

  static void load() {
    if (loaded) {
      return;
    }
    if (JavaVirtualMachine.isJavaVersionAtLeast(25)) {
      SystemProperties.set("jffi.unsafe.disabled", "true");
    }
    try {
      if (StubLoader.isLoaded()) {
        loaded = true;
      } else {
        throw StubLoader.getFailureCause();
      }
    } catch (UnsatisfiedLinkError e) {
      throw e;
    } catch (Throwable e) {
      throw (UnsatisfiedLinkError) new UnsatisfiedLinkError(e.getLocalizedMessage()).initCause(e);
    }
  }
}
