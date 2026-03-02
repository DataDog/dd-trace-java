import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

class FFMInstrumentationTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // only here to let the FFMApiModule kick in without forking each case then.
    injectSysConfig("trace.native.methods", "unknown[*]")
  }

  def "should trace ffm calls"() {
    setup:
    injectSysConfig("trace.native.methods", configured)
    final MemorySegment strlenAddr = lookup.call().findOrThrow("strlen")
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
    configured | traceExpected | resource | lookup
    ""         | false         | _        | {
      Linker.nativeLinker().defaultLookup()
    }
    "[strlen]" | true          | "strlen" | {
      Linker.nativeLinker().defaultLookup()
    }
    "[*]"      | true          | "strlen" | {
      Linker.nativeLinker().defaultLookup()
    }
  }
}

