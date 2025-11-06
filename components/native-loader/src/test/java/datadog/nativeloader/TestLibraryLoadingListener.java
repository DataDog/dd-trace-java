package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedList;

public final class TestLibraryLoadingListener implements LibraryLoadingListener {
  private final LinkedList<Check> checks;

  private Check failedCheck = null;
  private Throwable failedCause = null;

  public TestLibraryLoadingListener() {
    this.checks = new LinkedList<>();
  }

  private TestLibraryLoadingListener(TestLibraryLoadingListener that) {
    this.checks = new LinkedList<>(that.checks);
  }

  public TestLibraryLoadingListener expectResolveDynamic(String expectedLibName) {
    return this.expectResolveDynamic(new LibCheck(expectedLibName));
  }

  public TestLibraryLoadingListener expectResolveDynamic(
      String expectedComponent, String expectedLibName) {
    return this.expectResolveDynamic(new LibCheck(expectedComponent, expectedLibName));
  }

  public TestLibraryLoadingListener expectResolveDynamic(
      PlatformSpec expectedPlatformSpec, String expectedLibName) {
    return this.expectResolveDynamic(new LibCheck(expectedPlatformSpec, expectedLibName));
  }

  private TestLibraryLoadingListener expectResolveDynamic(LibCheck libCheck) {
    return this.addCheck(
        new Check("onResolveDynamic %s", libCheck) {
          @Override
          public void onResolveDynamic(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              boolean isPreloaded,
              URL optionalUrl) {
            libCheck.assertMatches(platformSpec, optionalComponent, libName);
          }
        });
  }

  public TestLibraryLoadingListener expectResolvePreloaded(String expectedLibName) {
    return this.expectResolvePreloaded(new LibCheck(expectedLibName));
  }

  private TestLibraryLoadingListener expectResolvePreloaded(LibCheck libCheck) {
    return this.addCheck(
        new Check("onResolveDynamic:preloaded %s", libCheck) {
          @Override
          public void onResolveDynamic(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              boolean isPreloaded,
              URL optionalUrl) {
            libCheck.assertMatches(platformSpec, optionalComponent, libName);
            assertTrue(isPreloaded);
          }
        });
  }

  public TestLibraryLoadingListener expectResolveDynamicFailure(String expectedLibName) {
    return this.expectResolveDynamicFailure(new LibCheck(expectedLibName));
  }

  private TestLibraryLoadingListener expectResolveDynamicFailure(LibCheck libCheck) {
    return this.addCheck(
        new Check("onResolveDynamicFailure %s", libCheck) {
          @Override
          public void onResolveDynamicFailure(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              Throwable optionalCause) {
            libCheck.assertMatches(platformSpec, optionalComponent, libName);
          }
        });
  }

  public TestLibraryLoadingListener expectLoad(String expectedLibName) {
    return this.expectLoad(new LibCheck(expectedLibName));
  }

  public TestLibraryLoadingListener expectLoad(String expectedComponent, String expectedLibName) {
    return this.expectLoad(new LibCheck(expectedComponent, expectedLibName));
  }

  private TestLibraryLoadingListener expectLoad(LibCheck libCheck) {
    return this.addCheck(
        new Check("onLoad %s", libCheck) {
          @Override
          public void onLoad(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              boolean isPreloaded,
              Path optionalLibPath) {
            libCheck.assertMatches(platformSpec, optionalComponent, libName);
          }
        });
  }

  public TestLibraryLoadingListener expectLoadPreloaded(String expectedLibName) {
    return this.expectLoadPreloaded(new LibCheck(expectedLibName));
  }

  private TestLibraryLoadingListener expectLoadPreloaded(LibCheck libCheck) {
    return this.addCheck(
        new Check("onLoad:preloaded %s", libCheck) {
          @Override
          public void onLoad(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              boolean isPreloaded,
              Path optionalLibPath) {
            libCheck.assertMatches(platformSpec, optionalComponent, libName);
            assertTrue(isPreloaded);
          }
        });
  }

  public TestLibraryLoadingListener expectLoadFailure(String expectedLibName) {
    return this.expectLoadFailure(new LibCheck(expectedLibName));
  }

  private TestLibraryLoadingListener expectLoadFailure(LibCheck libCheck) {
    return this.addCheck(
        new Check("onLoadFailure %s", libCheck) {
          @Override
          public void onLoadFailure(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              Throwable optionalCause) {
            libCheck.assertMatches(platformSpec, optionalComponent, libName);
          }
        });
  }

  public TestLibraryLoadingListener expectTempFileCreated(String expectedLibName) {
    return this.expectTempFileCreated(new LibCheck(expectedLibName));
  }

  private TestLibraryLoadingListener expectTempFileCreated(LibCheck libCheck) {
    return this.addCheck(
        new Check("onTempFileCreated %s", libCheck) {
          @Override
          public void onTempFileCreated(
              PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
            libCheck.assertMatches(platformSpec, optionalComponent, libName);
            assertNotNull(tempFile);
          }
        });
  }

  public TestLibraryLoadingListener expectTempFileCreationFailure(String expectedLibName) {
    return this.expectTempFileCreationFailure(new LibCheck(expectedLibName));
  }

  private TestLibraryLoadingListener expectTempFileCreationFailure(LibCheck libCheck) {
    return this.addCheck(
        new Check("onTempFileCreationFailure %s", libCheck) {
          @Override
          public void onTempFileCreationFailure(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              Path tempDir,
              String libExt,
              Path optionalTempFile,
              Throwable optionalCause) {
            libCheck.assertMatches(platformSpec, optionalComponent, libName);
            assertNotNull(tempDir);
            assertNotNull(libExt);
          }
        });
  }

  public TestLibraryLoadingListener expectTempFileCleanup(String expectedLibName) {
    return this.expectTempFileCleanup(new LibCheck(expectedLibName));
  }

  public TestLibraryLoadingListener expectTempFileCleanup(LibCheck libCheck) {
    return this.addCheck(
        new Check("onTempFileCreationCleanup %s", libCheck) {
          @Override
          public void onTempFileCleanup(
              PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
            libCheck.assertMatches(platformSpec, optionalComponent, libName);
            assertNotNull(tempFile);
          }
        });
  }

  public TestLibraryLoadingListener copy() {
    return new TestLibraryLoadingListener(this);
  }

  public void assertDone() {
    if (this.failedCheck != null) {
      try {
        fail("check failed: " + this.failedCheck, this.failedCause);
      } catch (AssertionError e) {
        e.initCause(this.failedCause);

        throw e;
      }
    }

    // written this way for better debugging
    assertEquals(Collections.emptyList(), this.checks);
  }

  @Override
  public void onResolveDynamic(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      URL optionalUrl) {
    this.nextCheck(
        check ->
            check.onResolveDynamic(
                platformSpec, optionalComponent, libName, isPreloaded, optionalUrl));
  }

  @Override
  public void onResolveDynamicFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Throwable optionalCause) {
    this.nextCheck(
        check ->
            check.onResolveDynamicFailure(platformSpec, optionalComponent, libName, optionalCause));
  }

  @Override
  public void onLoad(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      Path optionalLibPath) {
    this.nextCheck(
        check ->
            check.onLoad(platformSpec, optionalComponent, libName, isPreloaded, optionalLibPath));
  }

  @Override
  public void onLoadFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Throwable optionalCause) {
    this.nextCheck(
        check -> check.onLoadFailure(platformSpec, optionalComponent, libName, optionalCause));
  }

  @Override
  public void onTempFileCreated(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
    if (true) new RuntimeException("onTempFileCreated!");

    this.nextCheck(
        check -> check.onTempFileCreated(platformSpec, optionalComponent, libName, tempFile));
  }

  @Override
  public void onTempFileCreationFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Path tempDir,
      String libExt,
      Path optionalTempFile,
      Throwable optionalCause) {
    this.nextCheck(
        check ->
            check.onTempFileCreationFailure(
                platformSpec,
                optionalComponent,
                libName,
                tempDir,
                libExt,
                optionalTempFile,
                optionalCause));
  }

  @Override
  public void onTempFileCleanup(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
    this.nextCheck(
        check -> check.onTempFileCleanup(platformSpec, optionalComponent, libName, tempFile));
  }

  public TestLibraryLoadingListener addCheck(Check check) {
    this.checks.addLast(check);
    return this;
  }

  private void nextCheck(CheckInvocation invocation) {
    Check nextCheck = this.checks.isEmpty() ? Check.NOTHING : this.checks.removeFirst();
    try {
      invocation.invoke(nextCheck);
    } catch (Throwable t) {
      if (this.failedCheck == null) {
        this.failedCheck = nextCheck;
        this.failedCause = t;
      }
    }
  }

  public abstract static class Check implements LibraryLoadingListener {
    static final Check NOTHING = new Check("nothing") {};

    private final String name;

    Check(String nameFormat, Object... nameArgs) {
      this(String.format(nameFormat, nameArgs));
    }

    Check(String name) {
      this.name = name;
    }

    @Override
    public void onLoad(
        PlatformSpec platformSpec,
        String optionalComponent,
        String libName,
        boolean isPreloaded,
        Path optionalLibPath) {
      this.fallback(
          "onLoad", platformSpec, optionalComponent, libName, isPreloaded, optionalLibPath);
    }

    @Override
    public void onLoadFailure(
        PlatformSpec platformSpec,
        String optionalComponent,
        String libName,
        Throwable optionalCause) {
      this.fallback("onLoadFailure", platformSpec, optionalComponent, libName, optionalCause);
    }

    @Override
    public void onResolveDynamic(
        PlatformSpec platformSpec,
        String optionalComponent,
        String libName,
        boolean isPreloaded,
        URL optionalUrl) {
      this.fallback(
          "onResolveDynamic", platformSpec, optionalComponent, libName, isPreloaded, optionalUrl);
    }

    @Override
    public void onResolveDynamicFailure(
        PlatformSpec platformSpec,
        String optionalComponent,
        String libName,
        Throwable optionalCause) {
      this.fallback(
          "onResolveDynamicFailure", platformSpec, optionalComponent, libName, optionalCause);
    }

    @Override
    public void onTempFileCreated(
        PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
      this.fallback("onTmepFileCreated", platformSpec, optionalComponent, libName, tempFile);
    }

    @Override
    public void onTempFileCreationFailure(
        PlatformSpec platformSpec,
        String optionalComponent,
        String libName,
        Path tempDir,
        String libExt,
        Path optionalTempFile,
        Throwable optionalCause) {
      this.fallback(
          "onTempFileCreationFailure",
          platformSpec,
          optionalComponent,
          libName,
          tempDir,
          libExt,
          optionalTempFile,
          optionalCause);
    }

    @Override
    public void onTempFileCleanup(
        PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
      this.fallback("onTempFileCleanup", platformSpec, optionalComponent, libName, tempFile);
    }

    void fallback(String methodName, Object... args) {
      fail("unxpected call: " + callToString(methodName, args) + " - expected: " + this.name);
    }

    static final String callToString(String methodName, Object... args) {
      StringBuilder builder = new StringBuilder();
      builder.append(methodName);
      builder.append('(');
      for (int i = 0; i < args.length; ++i) {
        if (i != 0) builder.append(", ");
        builder.append(String.valueOf(args[i]));
      }
      builder.append(')');
      return builder.toString();
    }

    @Override
    public String toString() {
      return this.name;
    }
  }

  @FunctionalInterface
  interface CheckInvocation {
    void invoke(Check check);
  }

  public static final class LibCheck {
    private final PlatformSpec expectedPlatformSpec;
    private final String expectedComponent;
    private final String expectedLibName;

    LibCheck(PlatformSpec expectedPlatformSpec, String expectedLibName) {
      this(null, null, expectedLibName);
    }

    LibCheck(String expectedComponent, String expectedLibName) {
      this(null, expectedComponent, expectedLibName);
    }

    LibCheck(String expectedLibName) {
      this(null, null, expectedLibName);
    }

    LibCheck(PlatformSpec expectedPlatformSpec, String expectedComponent, String expectedLibName) {
      this.expectedPlatformSpec = expectedPlatformSpec;
      this.expectedComponent = expectedComponent;
      this.expectedLibName = expectedLibName;
    }

    void assertMatches(PlatformSpec platformSpec, String optionalComponent, String libName) {
      if (this.expectedPlatformSpec == null) {
        // if no expectedPlatformSpec was provided -- just check that platformSpec is not null
        assertNotNull(platformSpec);
      } else {
        assertEquals(this.expectedPlatformSpec, platformSpec);
      }

      if (this.expectedComponent == null) {
        // a null expectedComponent is treated as not expecting a component
        assertNull(optionalComponent);
      } else {
        assertEquals(this.expectedComponent, optionalComponent);
      }

      assertEquals(this.expectedLibName, libName);
    }

    @Override
    public String toString() {
      if (this.expectedComponent == null) {
        return this.expectedLibName;
      } else {
        return this.expectedComponent + "/" + this.expectedLibName;
      }
    }
  }
}
