package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedList;

public final class TestLibraryLoadingListener implements LibraryLoadingListener {
  private final LinkedList<Check> checks;

  TestLibraryLoadingListener() {
    this.checks = new LinkedList<>();
  }

  TestLibraryLoadingListener(TestLibraryLoadingListener that) {
    this.checks = new LinkedList<>(that.checks);
  }

  public TestLibraryLoadingListener expectResolveDynamic(String expectedLibName) {
    return this.addCheck(
        new Check("onResolveDynamic %s", expectedLibName) {
          @Override
          public void onResolveDynamic(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              boolean isPreloaded,
              URL optionalUrl) {
            assertNull(optionalComponent);
            assertEquals(libName, expectedLibName);
          }
        });
  }

  public TestLibraryLoadingListener expectResolvePreloaded(String expectedLibName) {
    return this.addCheck(
        new Check("onResolveDynamic:preloaded %s", expectedLibName) {
          @Override
          public void onResolveDynamic(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              boolean isPreloaded,
              URL optionalUrl) {
            assertNull(optionalComponent);
            assertEquals(libName, expectedLibName);
            assertTrue(isPreloaded);
          }
        });
  }

  public TestLibraryLoadingListener expectResolveDynamicFailure(String expectedLibName) {
    return this.addCheck(
        new Check("onResolveDynamicFailure %s", expectedLibName) {
          @Override
          public void onResolveDynamicFailure(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              LibraryLoadException optionalCause) {
            assertNull(optionalComponent);
            assertEquals(libName, expectedLibName);
          }
        });
  }

  public TestLibraryLoadingListener expectLoad(String expectedLibName) {
    return this.addCheck(
        new Check("onLoad %s", expectedLibName) {
          @Override
          public void onLoad(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              boolean isPreloaded,
              Path optionalLibPath) {
            assertNull(optionalComponent);
            assertEquals(libName, expectedLibName);
          }
        });
  }

  public TestLibraryLoadingListener expectLoadPreloaded(String expectedLibName) {
    return this.addCheck(
        new Check("onLoad:preloaded %s", expectedLibName) {
          @Override
          public void onLoad(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              boolean isPreloaded,
              Path optionalLibPath) {
            assertNull(optionalComponent);
            assertEquals(libName, expectedLibName);
            assertTrue(isPreloaded);
          }
        });
  }

  public TestLibraryLoadingListener expectLoad(String expectedComponent, String expectedLibName) {
    return this.addCheck(
        new Check("onLoad %s/%s", expectedComponent, expectedLibName) {
          @Override
          public void onLoad(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              boolean isPreloaded,
              Path optionalLibPath) {
            assertEquals(optionalComponent, expectedComponent);
            assertEquals(libName, expectedLibName);
          }
        });
  }

  public TestLibraryLoadingListener expectTempFileCreated(String expectedLibName) {
    return this.addCheck(
        new Check("onTempFileCreated %s", expectedLibName) {
          @Override
          public void onTempFileCreated(
              PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
            assertNull(optionalComponent);
            assertEquals(expectedLibName, libName);
          }
        });
  }

  public TestLibraryLoadingListener expectTempFileCreationFailure(String expectedLibName) {
    return this.addCheck(
        new Check("onTempFileCreationFailure %s", expectedLibName) {
          @Override
          public void onTempFileCreationFailure(
              PlatformSpec platformSpec,
              String optionalComponent,
              String libName,
              Path tempDir,
              String libExt,
              Path optionalTempFile,
              Throwable optionalCause) {
            assertNull(optionalComponent);
            assertEquals(expectedLibName, libName);
          }
        });
  }

  public TestLibraryLoadingListener expectTempFileCleanup(String expectedLibName) {
    return this.addCheck(
        new Check("onTempFileCreationCleanup %s", expectedLibName) {
          @Override
          public void onTempFileCleanup(
              PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
            assertNull(optionalComponent);
            assertEquals(expectedLibName, libName);
          }
        });
  }

  public TestLibraryLoadingListener copy() {
    return new TestLibraryLoadingListener(this);
  }

  public void assertDone() {
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
    this.nextCheck()
        .onResolveDynamic(platformSpec, optionalComponent, libName, isPreloaded, optionalUrl);
  }

  @Override
  public void onResolveDynamicFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      LibraryLoadException optionalCause) {
    this.nextCheck()
        .onResolveDynamicFailure(platformSpec, optionalComponent, libName, optionalCause);
  }

  @Override
  public void onLoad(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      Path optionalLibPath) {
    this.nextCheck().onLoad(platformSpec, optionalComponent, libName, isPreloaded, optionalLibPath);
  }

  @Override
  public void onLoadFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      LibraryLoadException optionalCause) {
    this.nextCheck().onLoadFailure(platformSpec, optionalComponent, libName, optionalCause);
  }

  @Override
  public void onTempFileCreated(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
    this.nextCheck().onTempFileCreated(platformSpec, optionalComponent, libName, tempFile);
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
    this.nextCheck()
        .onTempFileCreationFailure(
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
    this.nextCheck().onTempFileCleanup(platformSpec, optionalComponent, libName, tempFile);
  }

  TestLibraryLoadingListener addCheck(Check check) {
    this.checks.addLast(check);
    return this;
  }

  Check nextCheck() {
    return this.checks.isEmpty() ? Check.NOTHING : this.checks.removeFirst();
  }

  abstract static class Check implements LibraryLoadingListener {
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
      this.fail("onLoad", platformSpec, optionalComponent, libName, isPreloaded, optionalLibPath);
    }

    @Override
    public void onLoadFailure(
        PlatformSpec platformSpec,
        String optionalComponent,
        String libName,
        LibraryLoadException optionalCause) {
      this.fail("onLoadFailure", platformSpec, optionalComponent, libName, optionalCause);
    }

    @Override
    public void onResolveDynamic(
        PlatformSpec platformSpec,
        String optionalComponent,
        String libName,
        boolean isPreloaded,
        URL optionalUrl) {
      this.fail(
          "onResolveDynamic", platformSpec, optionalComponent, libName, isPreloaded, optionalUrl);
    }

    @Override
    public void onResolveDynamicFailure(
        PlatformSpec platformSpec,
        String optionalComponent,
        String libName,
        LibraryLoadException optionalCause) {
      this.fail("onResolveDynamicFailure", platformSpec, optionalComponent, libName, optionalCause);
    }

    @Override
    public void onTempFileCreated(
        PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
      this.fail("onTmepFileCreated", platformSpec, optionalComponent, libName, tempFile);
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
      this.fail(
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
      this.fail("onTempFileCleanup", platformSpec, optionalComponent, libName, tempFile);
    }

    void fail(String methodName, Object... args) {
      fail("unxpected call: " + callToString(methodName, args) + " - expected: " + this.name);
    }

    static final String callToString(String methodName, Object... args) {
      StringBuilder builder = new StringBuilder();
      builder.append(methodName);
      builder.append('(');
      for (int i = 0; i < args.length; ++i) {
        if (i != 0) builder.append(", ");
        builder.append(args[i].toString());
      }
      builder.append(')');
      return builder.toString();
    }

    @Override
    public String toString() {
      return this.name;
    }
  }
}
