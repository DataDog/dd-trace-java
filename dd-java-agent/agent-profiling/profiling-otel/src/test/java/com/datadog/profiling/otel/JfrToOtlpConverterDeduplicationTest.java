package com.datadog.profiling.otel;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.otel.proto.dictionary.*;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Tests for verifying deduplication behavior in JFR to OTLP conversion.
 *
 * <p>These tests use reflection to access internal dictionary tables and verify that:
 *
 * <ul>
 *   <li>Identical stacktraces are deduplicated correctly
 *   <li>Dictionary tables (String, Function, Location, Stack) work as expected
 *   <li>Large-scale conversions handle deduplication efficiently
 * </ul>
 */
class JfrToOtlpConverterDeduplicationTest {

  @Test
  void verifyStacktraceDeduplication() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();

    // Access internal dictionary tables via reflection
    StringTable strings = getDictionaryTable(converter, "stringTable", StringTable.class);
    FunctionTable functions = getDictionaryTable(converter, "functionTable", FunctionTable.class);
    LocationTable locations = getDictionaryTable(converter, "locationTable", LocationTable.class);
    StackTable stacks = getDictionaryTable(converter, "stackTable", StackTable.class);

    // Simulate multiple samples with identical stacks
    // Stack A: method1 -> method2 -> method3
    int className1 = strings.intern("com.example.Class1");
    int methodName1 = strings.intern("method1");
    int func1 = functions.intern(className1, methodName1, 0, 10);
    int loc1 = locations.intern(0, 0x1000, func1, 10, 0);

    int className2 = strings.intern("com.example.Class2");
    int methodName2 = strings.intern("method2");
    int func2 = functions.intern(className2, methodName2, 0, 20);
    int loc2 = locations.intern(0, 0x2000, func2, 20, 0);

    int className3 = strings.intern("com.example.Class3");
    int methodName3 = strings.intern("method3");
    int func3 = functions.intern(className3, methodName3, 0, 30);
    int loc3 = locations.intern(0, 0x3000, func3, 30, 0);

    int[] stackA = new int[] {loc1, loc2, loc3};

    // Intern stack A multiple times - should get same index
    int stackIndex1 = stacks.intern(stackA);
    int stackIndex2 = stacks.intern(stackA);
    int stackIndex3 = stacks.intern(new int[] {loc1, loc2, loc3}); // New array, same content

    // All should reference the same stack index
    assertEquals(stackIndex1, stackIndex2, "Identical stacks should deduplicate");
    assertEquals(stackIndex1, stackIndex3, "Stacks with same content should deduplicate");

    // Create different stack B: method4 -> method5
    int className4 = strings.intern("com.example.Class4");
    int methodName4 = strings.intern("method4");
    int func4 = functions.intern(className4, methodName4, 0, 40);
    int loc4 = locations.intern(0, 0x4000, func4, 40, 0);

    int className5 = strings.intern("com.example.Class5");
    int methodName5 = strings.intern("method5");
    int func5 = functions.intern(className5, methodName5, 0, 50);
    int loc5 = locations.intern(0, 0x5000, func5, 50, 0);

    int[] stackB = new int[] {loc4, loc5};
    int stackIndexB = stacks.intern(stackB);

    // Stack B should have different index
    assertNotEquals(stackIndex1, stackIndexB, "Different stacks should have different indices");

    // Verify stack table size - should have 3 entries: index 0 (null), Stack A, Stack B
    assertEquals(3, stacks.size(), "Should have 3 stacks: null, A, B");

    // Verify string deduplication - repeated interns return same index
    int className1Again = strings.intern("com.example.Class1");
    assertEquals(className1, className1Again, "Duplicate strings should deduplicate");
  }

  @Test
  void verifyDictionaryTableDeduplication() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();

    // Access internal dictionary tables via reflection
    StringTable strings = getDictionaryTable(converter, "stringTable", StringTable.class);
    FunctionTable functions = getDictionaryTable(converter, "functionTable", FunctionTable.class);
    LocationTable locations = getDictionaryTable(converter, "locationTable", LocationTable.class);

    // Verify string deduplication
    int str1 = strings.intern("test.Class");
    int str2 = strings.intern("test.Class"); // Duplicate
    int str3 = strings.intern("other.Class"); // Different

    assertEquals(str1, str2, "Duplicate strings should return same index");
    assertNotEquals(str1, str3, "Different strings should return different index");

    // Initial size: 1 (index 0 for null/empty) + 2 unique strings = 3
    assertEquals(3, strings.size(), "StringTable should have 3 entries");

    // Verify function deduplication
    int method1 = strings.intern("method");
    int func1 = functions.intern(str1, method1, 0, 100);
    int func2 = functions.intern(str1, method1, 0, 100); // Duplicate
    int func3 = functions.intern(str3, method1, 0, 200); // Different class

    assertEquals(func1, func2, "Duplicate functions should return same index");
    assertNotEquals(func1, func3, "Different functions should return different index");

    // Function table size: 1 (index 0) + 2 unique functions = 3
    assertEquals(3, functions.size(), "FunctionTable should have 3 entries");

    // Verify location deduplication
    int loc1 = locations.intern(0, 0x1000, func1, 10, 0);
    int loc2 = locations.intern(0, 0x1000, func1, 10, 0); // Duplicate
    int loc3 = locations.intern(0, 0x2000, func1, 20, 0); // Different address

    assertEquals(loc1, loc2, "Duplicate locations should return same index");
    assertNotEquals(loc1, loc3, "Different locations should return different index");

    // Location table size: 1 (index 0) + 2 unique locations = 3
    assertEquals(3, locations.size(), "LocationTable should have 3 entries");
  }

  @Test
  void verifyLargeScaleDeduplication() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();

    // Access internal dictionary tables via reflection
    StringTable strings = getDictionaryTable(converter, "stringTable", StringTable.class);
    FunctionTable functions = getDictionaryTable(converter, "functionTable", FunctionTable.class);
    LocationTable locations = getDictionaryTable(converter, "locationTable", LocationTable.class);
    StackTable stacks = getDictionaryTable(converter, "stackTable", StackTable.class);

    // Create 10 unique stacks
    int[][] uniqueStacks = new int[10][];
    for (int i = 0; i < 10; i++) {
      int className = strings.intern("com.example.Class" + i);
      int methodName = strings.intern("method" + i);
      int func = functions.intern(className, methodName, 0, i * 10);
      int loc = locations.intern(0, 0x1000 + (i * 0x100), func, i * 10, 0);
      uniqueStacks[i] = new int[] {loc};
    }

    // Intern each unique stack 100 times
    int[] stackIndices = new int[10];
    for (int i = 0; i < 10; i++) {
      int firstIndex = stacks.intern(uniqueStacks[i]);
      stackIndices[i] = firstIndex;

      // Intern same stack 99 more times
      for (int repeat = 0; repeat < 99; repeat++) {
        int repeatIndex = stacks.intern(uniqueStacks[i]);
        assertEquals(
            firstIndex, repeatIndex, "Stack " + i + " repeat " + repeat + " should deduplicate");
      }
    }

    // Verify all 10 stacks have unique indices
    for (int i = 0; i < 10; i++) {
      for (int j = i + 1; j < 10; j++) {
        assertNotEquals(
            stackIndices[i],
            stackIndices[j],
            "Stack " + i + " and " + j + " should have different indices");
      }
    }

    // Stack table should have 11 entries: index 0 (null) + 10 unique stacks
    assertEquals(11, stacks.size(), "StackTable should have 11 entries after 1000 interns");

    // Verify string table has expected number of entries
    // Initial: 1 (index 0) + 10 class names + 10 method names = 21
    assertEquals(21, strings.size(), "StringTable should have 21 entries");

    // Verify function table has expected number of entries
    // Initial: 1 (index 0) + 10 unique functions = 11
    assertEquals(11, functions.size(), "FunctionTable should have 11 entries");

    // Verify location table has expected number of entries
    // Initial: 1 (index 0) + 10 unique locations = 11
    assertEquals(11, locations.size(), "LocationTable should have 11 entries");
  }

  @Test
  void verifyLinkTableDeduplication() throws Exception {
    JfrToOtlpConverter converter = new JfrToOtlpConverter();

    // Access link table via reflection
    LinkTable links = getDictionaryTable(converter, "linkTable", LinkTable.class);

    // Create trace links
    int link1 = links.intern(123L, 456L);
    int link2 = links.intern(123L, 456L); // Duplicate
    int link3 = links.intern(789L, 101112L); // Different

    assertEquals(link1, link2, "Duplicate links should return same index");
    assertNotEquals(link1, link3, "Different links should return different index");

    // Link table size: 1 (index 0) + 2 unique links = 3
    assertEquals(3, links.size(), "LinkTable should have 3 entries");
  }

  // Helper method to access private dictionary table fields using reflection
  @SuppressWarnings("unchecked")
  private <T> T getDictionaryTable(JfrToOtlpConverter converter, String fieldName, Class<T> type)
      throws Exception {
    Field field = JfrToOtlpConverter.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (T) field.get(converter);
  }
}
