import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import util.LoaderUtil

class FFMInstrumentationForkedTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // only here to let the FFMApiModule kick in without forking each case then.
    injectSysConfig("trace.native.methods", "unknown[*]")
  }

  def "should trace ffm calls for #configured"() {
    setup:
    injectSysConfig("trace.native.methods", configured)
    final MemorySegment strlenAddr = Linker.nativeLinker().defaultLookup().findOrThrow("strlen")
    final MethodHandle strlenHandle =
    Linker.nativeLinker().downcallHandle(
    strlenAddr, FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))

    when:
    long len
    try (final Arena arena = Arena.ofConfined()) {
      len = (long) strlenHandle.invokeWithArguments(arena.allocateFrom("Hello world!"))
    }

    then:
    len == 12
    assertTraces(traceExpected ? 1 : 0) {
      if (traceExpected) {
        trace(1) {
          span {
            operationName "trace.native"
            resourceName "libsyslookup.strlen"
            tags {
              "$Tags.COMPONENT" "trace-ffm"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    configured             | traceExpected
    ""                     | false
    "libsyslookup[strlen]" | true
    "libsyslookup[*]"      | true
  }

  def "should measure methods for #configured"() {
    setup:
    injectSysConfig("trace.native.methods", "libsyslookup[strlen]")
    injectSysConfig("measure.native.methods", configured)
    final MemorySegment strlenAddr = Linker.nativeLinker().defaultLookup().findOrThrow("strlen")
    final MethodHandle strlenHandle =
    Linker.nativeLinker().downcallHandle(
    strlenAddr, FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))

    when:
    long len
    try (final Arena arena = Arena.ofConfined()) {
      len = (long) strlenHandle.invokeWithArguments(arena.allocateFrom("Hello world!"))
    }

    then:
    len == 12
    assertTraces(1) {
      trace(1) {
        span {
          operationName "trace.native"
          resourceName "libsyslookup.strlen"
          measured expectMeasured
          tags {
            "$Tags.COMPONENT" "trace-ffm"
            defaultTags()
          }
        }
      }
    }

    where:
    configured             | expectMeasured
    "libsyslookup[strlen]" | true
    ""                     | false
  }

  def "should trace ffm calls using libraryLookup of jdk library for #configured"() {
    setup:
    // libzip ships with every JDK; System.mapLibraryName gives the correct platform filename
    final String libName = System.mapLibraryName("zip")  // "libzip.so" on Linux, "libzip.dylib" on macOS
    final Path libzipPath = Path.of(System.getProperty("java.home"), "lib", libName)
    injectSysConfig("trace.native.methods", configured)

    when:
    try (final Arena arena = Arena.ofConfined()) {
      final SymbolLookup libLookup = SymbolLookup.libraryLookup(libzipPath, arena)
      final MemorySegment zipOpenAddr = libLookup.findOrThrow("ZIP_Open")
      final MethodHandle zipOpenHandle =
      Linker.nativeLinker().downcallHandle(
      zipOpenAddr,
      FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
      // open a nonexistent path — ZIP_Open guards on pmsg before deref, returns NULL safely
      zipOpenHandle.invokeWithArguments(arena.allocateFrom("/nonexistent.zip"), MemorySegment.NULL)
    }

    then:
    assertTraces(traceExpected ? 1 : 0) {
      if (traceExpected) {
        trace(1) {
          span {
            operationName "trace.native"
            resourceName "libzip.ZIP_Open"
            tags {
              "$Tags.COMPONENT" "trace-ffm"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    configured         | traceExpected
    "libzip[ZIP_Open]" | true
    "libzip[*]"        | true
  }

  def "should trace ffm calls using libraryLookup by path for library loaded with System.load"() {
    setup:
    injectSysConfig("trace.native.methods", "libdt_socket[*]")
    final String libName = System.mapLibraryName("dt_socket")
    final Path libPath = Path.of(System.getProperty("java.home"), "lib", libName)

    when:
    LoaderUtil.loadLibrary(libPath)
    final SymbolLookup libLookup = LoaderUtil.loaderLookup()
    final MemorySegment onLoadAddr = libLookup.findOrThrow("jdwpTransport_OnLoad")
    final MethodHandle onLoadHandle =
    Linker.nativeLinker().downcallHandle(
    onLoadAddr,
    FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS))
    // version 0 is rejected before any argument is dereferenced
    onLoadHandle.invokeWithArguments(MemorySegment.NULL, MemorySegment.NULL, 0, MemorySegment.NULL)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "trace.native"
          resourceName "libdt_socket.jdwpTransport_OnLoad"
          tags {
            "$Tags.COMPONENT" "trace-ffm"
            defaultTags()
          }
        }
      }
    }
  }
}
