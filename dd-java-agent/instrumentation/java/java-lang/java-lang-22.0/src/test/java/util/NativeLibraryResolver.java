package util;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

public class NativeLibraryResolver {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = LINKER.defaultLookup();

  // dladdr symbol
  private static final MethodHandle DLADDR;

  // struct Dl_info
  private static final GroupLayout DL_INFO_LAYOUT =
      MemoryLayout.structLayout(
          ValueLayout.ADDRESS.withName("dli_fname"),
          ValueLayout.ADDRESS.withName("dli_fbase"),
          ValueLayout.ADDRESS.withName("dli_sname"),
          ValueLayout.ADDRESS.withName("dli_saddr"));

  private static final long DLI_FNAME_OFFSET =
      DL_INFO_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("dli_fname"));

  static {
    try {
      MemorySegment dladdrSymbol = LOOKUP.find("dladdr").orElseThrow();

      DLADDR =
          LINKER.downcallHandle(
              dladdrSymbol,
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT, // int return
                  ValueLayout.ADDRESS, // const void* addr
                  ValueLayout.ADDRESS // Dl_info* info
                  ));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  /**
   * Returns the full native library path that defines the given symbol. Returns null if the symbol
   * cannot be resolved.
   *
   * @param symbolAddress the address of the symbol to resolve.
   * @return the library path or null if cannot be found.
   */
  public static String findLibraryPath(MemorySegment symbolAddress) {
    try (Arena arena = Arena.ofConfined()) {

      MemorySegment info = arena.allocate(DL_INFO_LAYOUT);

      int result = (int) DLADDR.invoke(symbolAddress, info);
      if (result == 0) {
        return null; // not found
      }

      MemorySegment fnamePtr = info.get(ValueLayout.ADDRESS, DLI_FNAME_OFFSET);

      if (fnamePtr == MemorySegment.NULL) {
        return null;
      }

      return fnamePtr.getString(0, StandardCharsets.UTF_8);

    } catch (Throwable t) {
      return null;
    }
  }
}
