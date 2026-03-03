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
import spock.lang.IgnoreIf
import util.NativeLibraryResolver

class FFMInstrumentationTest extends InstrumentationSpecification {
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
            resourceName "$resource"
            tags {
              "$Tags.COMPONENT" "trace-ffm"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    configured | traceExpected | resource
    ""         | false         | _
    "[strlen]" | true          | "strlen"
    "[*]"      | true          | "strlen"
  }

  @IgnoreIf({ !os.isLinux() })
  def "should trace ffm calls using libraryLookup by name for #configured"() {
    setup:
    injectSysConfig("trace.native.methods", configured)

    when:
    long len
    try (final Arena arena = Arena.ofConfined()) {
      final SymbolLookup libLookup = SymbolLookup.libraryLookup("c", arena)
      final MemorySegment strlenAddr = libLookup.findOrThrow("strlen")
      final MethodHandle strlenHandle =
      Linker.nativeLinker().downcallHandle(
      strlenAddr, FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
      len = (long) strlenHandle.invokeWithArguments(arena.allocateFrom("Hello world!"))
    }

    then:
    len == 12
    assertTraces(traceExpected ? 1 : 0) {
      if (traceExpected) {
        trace(1) {
          span {
            operationName "trace.native"
            resourceName "$resource"
            tags {
              "$Tags.COMPONENT" "trace-ffm"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    configured       | traceExpected | resource
    ""               | false         | _
    "c[strlen]"      | true          | "c.strlen"
    "c[*]"           | true          | "c.strlen"
    "unknown_lib[*]" | false         | _
  }

  @IgnoreIf({ !os.isLinux() })
  def "should trace ffm calls using libraryLookup by path for #configTemplate"() {
    setup:
    final MemorySegment strlenSym = Linker.nativeLinker().defaultLookup().findOrThrow("strlen")
    final String libPath = NativeLibraryResolver.findLibraryPath(strlenSym)
    final String libFileName = Path.of(libPath).getFileName().toString().toLowerCase(Locale.ROOT)
    injectSysConfig("trace.native.methods", configTemplate.replace("{lib}", libFileName))

    when:
    long len
    try (final Arena arena = Arena.ofConfined()) {
      final SymbolLookup libLookup = SymbolLookup.libraryLookup(Path.of(libPath), arena)
      final MemorySegment strlenAddr = libLookup.findOrThrow("strlen")
      final MethodHandle strlenHandle =
      Linker.nativeLinker().downcallHandle(
      strlenAddr, FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
      len = (long) strlenHandle.invokeWithArguments(arena.allocateFrom("Hello world!"))
    }

    then:
    len == 12
    assertTraces(traceExpected ? 1 : 0) {
      if (traceExpected) {
        trace(1) {
          span {
            operationName "trace.native"
            resourceName "${libFileName}.strlen"
            tags {
              "$Tags.COMPONENT" "trace-ffm"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    configTemplate   | traceExpected
    ""               | false
    "{lib}[strlen]"  | true
    "{lib}[*]"       | true
    "unknown_lib[*]" | false
  }
}
