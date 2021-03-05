package com.datadog.profiling.controller.oracle;

import com.datadog.profiling.controller.jfr.JdkTypeIDs;

/** 'Old' (pre JDK-11) JFR event type IDs */
final class JdkTypeIDs_Old {
  /** The prefix used for JDK 11 and later */
  private static final String PREFIX = "jdk.";

  /** The prefix used for JDK 9 and 10. */
  private static final String PREFIX_9_10 = "com.oracle.jdk.";

  /*
   * Package scope producer id constants
   */
  private static final String EVENT_ID_ROOT = "http://www.oracle.com/hotspot/"; // $NON-NLS-1$
  private static final String JVM_EVENT_ID_ROOT = EVENT_ID_ROOT + "jvm/"; // $NON-NLS-1$
  private static final String JDK_EVENT_ID_ROOT = EVENT_ID_ROOT + "jdk/"; // $NON-NLS-1$
  private static final String JFR_INFO_EVENT_ID_ROOT = EVENT_ID_ROOT + "jfr-info/"; // $NON-NLS-1$

  /*
   * Unused JDK9 constants
   */

  // Runtime
  /*
   * FIXME: VMError is commented out since the build cannot handle warnings on lines containing
   * the text 'error'. Restore when we are sure that the build works with it.
   */
  //	private final static String VMError = PREFIX_9_10 + "VMError"; // "vm.runtime.vm_error";
  private static final String ClassLoaderStatistics =
      PREFIX_9_10 + "ClassLoaderStatistics"; // "java.statistics.class_loaders";

  // GC
  private static final String G1HeapSummary =
      PREFIX_9_10 + "G1HeapSummary"; // "vm.gc.heap.g1_summary";
  private static final String GC_G1MMU = PREFIX_9_10 + "GCG1MMU"; // "vm.gc.detailed.g1_mmu_info";
  private static final String PromoteObjectInNewPLAB =
      PREFIX_9_10 + "PromoteObjectInNewPLAB"; // "vm.gc.detailed.object_promotion_in_new_PLAB";
  private static final String PromoteObjectOutsidePLAB =
      PREFIX_9_10 + "PromoteObjectOutsidePLAB"; // "vm.gc.detailed.object_promotion_outside_PLAB";

  /*
   * JDK8 constants
   */
  private static final String CPU_LOAD = JVM_EVENT_ID_ROOT + "os/processor/cpu_load";
  private static final String EXECUTION_SAMPLE = JVM_EVENT_ID_ROOT + "vm/prof/execution_sample";
  private static final String EXECUTION_SAMPLING_INFO_EVENT_ID =
      JVM_EVENT_ID_ROOT + "vm/prof/execution_sampling_info";
  private static final String PROCESSES = JVM_EVENT_ID_ROOT + "os/system_process";
  private static final String OS_MEMORY_SUMMARY = JVM_EVENT_ID_ROOT + "os/memory/physical_memory";
  private static final String OS_INFORMATION = JVM_EVENT_ID_ROOT + "os/information";
  private static final String CPU_INFORMATION = JVM_EVENT_ID_ROOT + "os/processor/cpu_information";
  private static final String THREAD_ALLOCATION_STATISTICS =
      JVM_EVENT_ID_ROOT + "java/statistics/thread_allocation";
  private static final String HEAP_CONF = JVM_EVENT_ID_ROOT + "vm/gc/configuration/heap";
  private static final String GC_CONF = JVM_EVENT_ID_ROOT + "vm/gc/configuration/gc";
  private static final String HEAP_SUMMARY = JVM_EVENT_ID_ROOT + "vm/gc/heap/summary";
  static final String ALLOC_INSIDE_TLAB = JVM_EVENT_ID_ROOT + "java/object_alloc_in_new_TLAB";
  static final String ALLOC_OUTSIDE_TLAB = JVM_EVENT_ID_ROOT + "java/object_alloc_outside_TLAB";
  private static final String VM_INFO = JVM_EVENT_ID_ROOT + "vm/info";
  private static final String CLASS_LOAD = JVM_EVENT_ID_ROOT + "vm/class/load";
  private static final String CLASS_UNLOAD = JVM_EVENT_ID_ROOT + "vm/class/unload";
  private static final String CLASS_LOAD_STATISTICS =
      JVM_EVENT_ID_ROOT + "java/statistics/class_loading";
  static final String COMPILATION = JVM_EVENT_ID_ROOT + "vm/compiler/compilation";

  private static final String FILE_WRITE = JDK_EVENT_ID_ROOT + "java/file_write";
  private static final String FILE_READ = JDK_EVENT_ID_ROOT + "java/file_read";
  private static final String SOCKET_WRITE = JDK_EVENT_ID_ROOT + "java/socket_write";
  private static final String SOCKET_READ = JDK_EVENT_ID_ROOT + "java/socket_read";

  static final String THREAD_PARK = JVM_EVENT_ID_ROOT + "java/thread_park";
  private static final String THREAD_SLEEP = JVM_EVENT_ID_ROOT + "java/thread_sleep";
  static final String MONITOR_ENTER = JVM_EVENT_ID_ROOT + "java/monitor_enter";
  static final String MONITOR_WAIT = JVM_EVENT_ID_ROOT + "java/monitor_wait";

  private static final String METASPACE_OOM = JVM_EVENT_ID_ROOT + "vm/gc/metaspace/out_of_memory";

  private static final String CODE_CACHE_FULL = JVM_EVENT_ID_ROOT + "vm/code_cache/full";
  static final String CODE_CACHE_STATISTICS = JVM_EVENT_ID_ROOT + "vm/code_cache/stats";

  private static final String CODE_SWEEPER_STATISTICS = JVM_EVENT_ID_ROOT + "vm/code_sweeper/stats";
  static final String SWEEP_CODE_CACHE = JVM_EVENT_ID_ROOT + "vm/code_sweeper/sweep";

  private static final String ENVIRONMENT_VARIABLE =
      JVM_EVENT_ID_ROOT + "os/initial_environment_variable";
  private static final String SYSTEM_PROPERTIES = JVM_EVENT_ID_ROOT + "vm/initial_system_property";

  static final String OBJECT_COUNT = JVM_EVENT_ID_ROOT + "vm/gc/detailed/object_count";
  private static final String GC_REFERENCE_STATISTICS =
      JVM_EVENT_ID_ROOT + "vm/gc/reference/statistics";

  private static final String OLD_OBJECT_SAMPLE = JVM_EVENT_ID_ROOT + "java/old_object";

  private static final String GC_PAUSE_L3 = JVM_EVENT_ID_ROOT + "vm/gc/phases/pause_level_3";
  private static final String GC_PAUSE_L2 = JVM_EVENT_ID_ROOT + "vm/gc/phases/pause_level_2";
  private static final String GC_PAUSE_L1 = JVM_EVENT_ID_ROOT + "vm/gc/phases/pause_level_1";
  private static final String GC_PAUSE = JVM_EVENT_ID_ROOT + "vm/gc/phases/pause";

  private static final String METASPACE_SUMMARY =
      JVM_EVENT_ID_ROOT + "vm/gc/heap/metaspace_summary";
  private static final String GARBAGE_COLLECTION =
      JVM_EVENT_ID_ROOT + "vm/gc/collector/garbage_collection";
  private static final String CONCURRENT_MODE_FAILURE =
      JVM_EVENT_ID_ROOT + "vm/gc/detailed/concurrent_mode_failure";

  private static final String THROWABLES_STATISTICS =
      JDK_EVENT_ID_ROOT + "java/statistics/throwables";
  private static final String ERRORS_THROWN = JDK_EVENT_ID_ROOT + "java/error_throw";
  private static final String EXCEPTIONS_THROWN = JDK_EVENT_ID_ROOT + "java/exception_throw";

  private static final String COMPILER_STATS = JVM_EVENT_ID_ROOT + "vm/compiler/stats";
  static final String COMPILER_FAILURE = JVM_EVENT_ID_ROOT + "vm/compiler/failure";

  private static final String ULONG_FLAG = JVM_EVENT_ID_ROOT + "vm/flag/ulong";
  private static final String BOOLEAN_FLAG = JVM_EVENT_ID_ROOT + "vm/flag/boolean";
  private static final String STRING_FLAG = JVM_EVENT_ID_ROOT + "vm/flag/string";
  private static final String DOUBLE_FLAG = JVM_EVENT_ID_ROOT + "vm/flag/double";
  private static final String LONG_FLAG = JVM_EVENT_ID_ROOT + "vm/flag/long";
  private static final String INT_FLAG = JVM_EVENT_ID_ROOT + "vm/flag/int";
  private static final String UINT_FLAG = JVM_EVENT_ID_ROOT + "vm/flag/uint";

  static final String ULONG_FLAG_CHANGED = JVM_EVENT_ID_ROOT + "vm/flag/ulong_changed";
  static final String BOOLEAN_FLAG_CHANGED = JVM_EVENT_ID_ROOT + "vm/flag/boolean_changed";
  static final String STRING_FLAG_CHANGED = JVM_EVENT_ID_ROOT + "vm/flag/string_changed";
  static final String DOUBLE_FLAG_CHANGED = JVM_EVENT_ID_ROOT + "vm/flag/double_changed";
  static final String LONG_FLAG_CHANGED = JVM_EVENT_ID_ROOT + "vm/flag/long_changed";
  static final String INT_FLAG_CHANGED = JVM_EVENT_ID_ROOT + "vm/flag/int_changed";
  static final String UINT_FLAG_CHANGED = JVM_EVENT_ID_ROOT + "vm/flag/uint_changed";

  private static final String TIME_CONVERSION = JVM_EVENT_ID_ROOT + "os/processor/cpu_tsc";
  private static final String THREAD_DUMP = JVM_EVENT_ID_ROOT + "vm/runtime/thread_dump";

  private static final String GC_CONF_YOUNG_GENERATION =
      JVM_EVENT_ID_ROOT + "vm/gc/configuration/young_generation";
  private static final String GC_CONF_SURVIVOR = JVM_EVENT_ID_ROOT + "vm/gc/configuration/survivor";
  private static final String GC_CONF_TLAB = JVM_EVENT_ID_ROOT + "vm/gc/configuration/tlab";

  private static final String JAVA_THREAD_START = JVM_EVENT_ID_ROOT + "java/thread_start";
  private static final String JAVA_THREAD_END = JVM_EVENT_ID_ROOT + "java/thread_end";
  private static final String VM_OPERATIONS = JVM_EVENT_ID_ROOT + "vm/runtime/execute_vm_operation";

  private static final String THREAD_STATISTICS = JVM_EVENT_ID_ROOT + "java/statistics/threads";
  private static final String CONTEXT_SWITCH_RATE =
      JVM_EVENT_ID_ROOT + "os/processor/context_switch_rate";

  private static final String COMPILER_CONFIG = JVM_EVENT_ID_ROOT + "vm/compiler/config";
  private static final String CODE_CACHE_CONFIG = JVM_EVENT_ID_ROOT + "vm/code_cache/config";
  private static final String CODE_SWEEPER_CONFIG = JVM_EVENT_ID_ROOT + "vm/code_sweeper/config";
  static final String COMPILER_PHASE = JVM_EVENT_ID_ROOT + "vm/compiler/phase";
  private static final String GC_COLLECTOR_G1_GARBAGE_COLLECTION =
      JVM_EVENT_ID_ROOT + "vm/gc/collector/g1_garbage_collection";
  private static final String GC_COLLECTOR_OLD_GARBAGE_COLLECTION =
      JVM_EVENT_ID_ROOT + "vm/gc/collector/old_garbage_collection";
  private static final String GC_COLLECTOR_PAROLD_GARBAGE_COLLECTION =
      JVM_EVENT_ID_ROOT + "vm/gc/collector/parold_garbage_collection";
  private static final String GC_COLLECTOR_YOUNG_GARBAGE_COLLECTION =
      JVM_EVENT_ID_ROOT + "vm/gc/collector/young_garbage_collection";
  private static final String GC_DETAILED_ALLOCATION_REQUIRING_GC =
      JVM_EVENT_ID_ROOT + "vm/gc/detailed/allocation_requiring_gc";
  private static final String GC_DETAILED_EVACUATION_FAILED =
      JVM_EVENT_ID_ROOT + "vm/gc/detailed/evacuation_failed";
  static final String GC_DETAILED_EVACUATION_INFO =
      JVM_EVENT_ID_ROOT + "vm/gc/detailed/evacuation_info";
  static final String GC_DETAILED_OBJECT_COUNT_AFTER_GC =
      JVM_EVENT_ID_ROOT + "vm/gc/detailed/object_count_after_gc";
  private static final String GC_DETAILED_PROMOTION_FAILED =
      JVM_EVENT_ID_ROOT + "vm/gc/detailed/promotion_failed";
  private static final String GC_HEAP_PS_SUMMARY = JVM_EVENT_ID_ROOT + "vm/gc/heap/ps_summary";
  private static final String GC_METASPACE_ALLOCATION_FAILURE =
      JVM_EVENT_ID_ROOT + "vm/gc/metaspace/allocation_failure";
  private static final String GC_METASPACE_CHUNK_FREE_LIST_SUMMARY =
      JVM_EVENT_ID_ROOT + "vm/gc/metaspace/chunk_free_list_summary";
  private static final String GC_METASPACE_GC_THRESHOLD =
      JVM_EVENT_ID_ROOT + "vm/gc/metaspace/gc_threshold";

  static final String RECORDINGS = JFR_INFO_EVENT_ID_ROOT + "recordings/recording";
  static final String RECORDING_SETTING = JFR_INFO_EVENT_ID_ROOT + "recordings/recording_setting";
  static final String JDK9_RECORDING_SETTING = PREFIX_9_10 + "ActiveSetting";

  static final String BUFFER_LOST_TYPE_ID = "org.openjdk.jmc.flightrecorder.bufferlosttypeid";

  /**
   * Translate a pre-JDK 11 type id into a JDK 11 type id.
   *
   * @param typeId Pre-JDK 11 type id
   * @return JDK 11 type id
   */
  public static String translateFrom(String typeId) {
    if (typeId.startsWith(PREFIX_9_10)) {
      if (typeId.endsWith("AllocationRequiringGc")) {
        return JdkTypeIDs.GC_DETAILED_ALLOCATION_REQUIRING_GC;
      }
      if (typeId.endsWith("GCG1MMU")) {
        return JdkTypeIDs.GC_G1MMU;
      }
      return PREFIX + typeId.substring(PREFIX_9_10.length());
    }
    switch (typeId) {
      case CPU_LOAD:
        return JdkTypeIDs.CPU_LOAD;
      case EXECUTION_SAMPLE:
        return JdkTypeIDs.EXECUTION_SAMPLE;
      case EXECUTION_SAMPLING_INFO_EVENT_ID:
        return JdkTypeIDs.EXECUTION_SAMPLING_INFO_EVENT_ID;
      case PROCESSES:
        return JdkTypeIDs.PROCESSES;
      case OS_MEMORY_SUMMARY:
        return JdkTypeIDs.OS_MEMORY_SUMMARY;
      case OS_INFORMATION:
        return JdkTypeIDs.OS_INFORMATION;
      case CPU_INFORMATION:
        return JdkTypeIDs.CPU_INFORMATION;
      case THREAD_ALLOCATION_STATISTICS:
        return JdkTypeIDs.THREAD_ALLOCATION_STATISTICS;
      case HEAP_CONF:
        return JdkTypeIDs.HEAP_CONF;
      case GC_CONF:
        return JdkTypeIDs.GC_CONF;
      case HEAP_SUMMARY:
        return JdkTypeIDs.HEAP_SUMMARY;
      case ALLOC_INSIDE_TLAB:
        return JdkTypeIDs.ALLOC_INSIDE_TLAB;
      case ALLOC_OUTSIDE_TLAB:
        return JdkTypeIDs.ALLOC_OUTSIDE_TLAB;
      case VM_INFO:
        return JdkTypeIDs.VM_INFO;
      case CLASS_LOAD:
        return JdkTypeIDs.CLASS_LOAD;
      case CLASS_UNLOAD:
        return JdkTypeIDs.CLASS_UNLOAD;
      case CLASS_LOAD_STATISTICS:
        return JdkTypeIDs.CLASS_LOAD_STATISTICS;
      case COMPILATION:
        return JdkTypeIDs.COMPILATION;
      case FILE_WRITE:
        return JdkTypeIDs.FILE_WRITE;
      case FILE_READ:
        return JdkTypeIDs.FILE_READ;
      case SOCKET_WRITE:
        return JdkTypeIDs.SOCKET_WRITE;
      case SOCKET_READ:
        return JdkTypeIDs.SOCKET_READ;
      case THREAD_PARK:
        return JdkTypeIDs.THREAD_PARK;
      case THREAD_SLEEP:
        return JdkTypeIDs.THREAD_SLEEP;
      case MONITOR_ENTER:
        return JdkTypeIDs.MONITOR_ENTER;
      case MONITOR_WAIT:
        return JdkTypeIDs.MONITOR_WAIT;
      case METASPACE_OOM:
        return JdkTypeIDs.METASPACE_OOM;
      case CODE_CACHE_FULL:
        return JdkTypeIDs.CODE_CACHE_FULL;
      case CODE_CACHE_STATISTICS:
        return JdkTypeIDs.CODE_CACHE_STATISTICS;
      case CODE_SWEEPER_STATISTICS:
        return JdkTypeIDs.CODE_SWEEPER_STATISTICS;
      case SWEEP_CODE_CACHE:
        return JdkTypeIDs.SWEEP_CODE_CACHE;
      case ENVIRONMENT_VARIABLE:
        return JdkTypeIDs.ENVIRONMENT_VARIABLE;
      case SYSTEM_PROPERTIES:
        return JdkTypeIDs.SYSTEM_PROPERTIES;
      case OBJECT_COUNT:
        return JdkTypeIDs.OBJECT_COUNT;
      case GC_REFERENCE_STATISTICS:
        return JdkTypeIDs.GC_REFERENCE_STATISTICS;
      case OLD_OBJECT_SAMPLE:
        return JdkTypeIDs.OLD_OBJECT_SAMPLE;
      case GC_PAUSE_L3:
        return JdkTypeIDs.GC_PAUSE_L3;
      case GC_PAUSE_L2:
        return JdkTypeIDs.GC_PAUSE_L2;
      case GC_PAUSE_L1:
        return JdkTypeIDs.GC_PAUSE_L1;
      case GC_PAUSE:
        return JdkTypeIDs.GC_PAUSE;
      case METASPACE_SUMMARY:
        return JdkTypeIDs.METASPACE_SUMMARY;
      case GARBAGE_COLLECTION:
        return JdkTypeIDs.GARBAGE_COLLECTION;
      case CONCURRENT_MODE_FAILURE:
        return JdkTypeIDs.CONCURRENT_MODE_FAILURE;
      case THROWABLES_STATISTICS:
        return JdkTypeIDs.THROWABLES_STATISTICS;
      case ERRORS_THROWN:
        return JdkTypeIDs.ERRORS_THROWN;
      case EXCEPTIONS_THROWN:
        return JdkTypeIDs.EXCEPTIONS_THROWN;
      case COMPILER_STATS:
        return JdkTypeIDs.COMPILER_STATS;
      case COMPILER_FAILURE:
        return JdkTypeIDs.COMPILER_FAILURE;
      case ULONG_FLAG:
        return JdkTypeIDs.ULONG_FLAG;
      case BOOLEAN_FLAG:
        return JdkTypeIDs.BOOLEAN_FLAG;
      case STRING_FLAG:
        return JdkTypeIDs.STRING_FLAG;
      case DOUBLE_FLAG:
        return JdkTypeIDs.DOUBLE_FLAG;
      case LONG_FLAG:
        return JdkTypeIDs.LONG_FLAG;
      case INT_FLAG:
        return JdkTypeIDs.INT_FLAG;
      case UINT_FLAG:
        return JdkTypeIDs.UINT_FLAG;
      case ULONG_FLAG_CHANGED:
        return JdkTypeIDs.ULONG_FLAG_CHANGED;
      case BOOLEAN_FLAG_CHANGED:
        return JdkTypeIDs.BOOLEAN_FLAG_CHANGED;
      case STRING_FLAG_CHANGED:
        return JdkTypeIDs.STRING_FLAG_CHANGED;
      case DOUBLE_FLAG_CHANGED:
        return JdkTypeIDs.DOUBLE_FLAG_CHANGED;
      case LONG_FLAG_CHANGED:
        return JdkTypeIDs.LONG_FLAG_CHANGED;
      case INT_FLAG_CHANGED:
        return JdkTypeIDs.INT_FLAG_CHANGED;
      case UINT_FLAG_CHANGED:
        return JdkTypeIDs.UINT_FLAG_CHANGED;
      case TIME_CONVERSION:
        return JdkTypeIDs.TIME_CONVERSION;
      case THREAD_DUMP:
        return JdkTypeIDs.THREAD_DUMP;
      case BUFFER_LOST_TYPE_ID:
        return JdkTypeIDs.JFR_DATA_LOST;
      case GC_CONF_YOUNG_GENERATION:
        return JdkTypeIDs.GC_CONF_YOUNG_GENERATION;
      case GC_CONF_SURVIVOR:
        return JdkTypeIDs.GC_CONF_SURVIVOR;
      case GC_CONF_TLAB:
        return JdkTypeIDs.GC_CONF_TLAB;
      case JAVA_THREAD_START:
        return JdkTypeIDs.JAVA_THREAD_START;
      case JAVA_THREAD_END:
        return JdkTypeIDs.JAVA_THREAD_END;
      case VM_OPERATIONS:
        return JdkTypeIDs.VM_OPERATIONS;
      case THREAD_STATISTICS:
        return JdkTypeIDs.THREAD_STATISTICS;
      case CONTEXT_SWITCH_RATE:
        return JdkTypeIDs.CONTEXT_SWITCH_RATE;
      case COMPILER_CONFIG:
        return JdkTypeIDs.COMPILER_CONFIG;
      case CODE_CACHE_CONFIG:
        return JdkTypeIDs.CODE_CACHE_CONFIG;
      case CODE_SWEEPER_CONFIG:
        return JdkTypeIDs.CODE_SWEEPER_CONFIG;
      case COMPILER_PHASE:
        return JdkTypeIDs.COMPILER_PHASE;
      case GC_COLLECTOR_G1_GARBAGE_COLLECTION:
        return JdkTypeIDs.GC_COLLECTOR_G1_GARBAGE_COLLECTION;
      case GC_COLLECTOR_OLD_GARBAGE_COLLECTION:
        return JdkTypeIDs.GC_COLLECTOR_OLD_GARBAGE_COLLECTION;
      case GC_COLLECTOR_PAROLD_GARBAGE_COLLECTION:
        return JdkTypeIDs.GC_COLLECTOR_PAROLD_GARBAGE_COLLECTION;
      case GC_COLLECTOR_YOUNG_GARBAGE_COLLECTION:
        return JdkTypeIDs.GC_COLLECTOR_YOUNG_GARBAGE_COLLECTION;
      case GC_DETAILED_ALLOCATION_REQUIRING_GC:
        return JdkTypeIDs.GC_DETAILED_ALLOCATION_REQUIRING_GC;
      case GC_DETAILED_EVACUATION_FAILED:
        return JdkTypeIDs.GC_DETAILED_EVACUATION_FAILED;
      case GC_DETAILED_EVACUATION_INFO:
        return JdkTypeIDs.GC_DETAILED_EVACUATION_INFO;
      case GC_DETAILED_OBJECT_COUNT_AFTER_GC:
        return JdkTypeIDs.GC_DETAILED_OBJECT_COUNT_AFTER_GC;
      case GC_DETAILED_PROMOTION_FAILED:
        return JdkTypeIDs.GC_DETAILED_PROMOTION_FAILED;
      case GC_HEAP_PS_SUMMARY:
        return JdkTypeIDs.GC_HEAP_PS_SUMMARY;
      case GC_METASPACE_ALLOCATION_FAILURE:
        return JdkTypeIDs.GC_METASPACE_ALLOCATION_FAILURE;
      case GC_METASPACE_CHUNK_FREE_LIST_SUMMARY:
        return JdkTypeIDs.GC_METASPACE_CHUNK_FREE_LIST_SUMMARY;
      case GC_METASPACE_GC_THRESHOLD:
        return JdkTypeIDs.GC_METASPACE_GC_THRESHOLD;
      case RECORDING_SETTING:
        return JdkTypeIDs.RECORDING_SETTING;
      case RECORDINGS:
        return JdkTypeIDs.RECORDINGS;
      case GC_G1MMU:
        return JdkTypeIDs.GC_G1MMU;
      default:
        return typeId;
    }
  }

  /**
   * Translate a JDK 11 type id into a pre-JDK 11 type id.
   *
   * @param typeId JDK 11 type id
   * @return Pre-JDK 11 type id
   */
  public static String translateTo(String typeId) {
    switch (typeId) {
      case JdkTypeIDs.CPU_LOAD:
        return CPU_LOAD;
      case JdkTypeIDs.EXECUTION_SAMPLE:
        return EXECUTION_SAMPLE;
      case JdkTypeIDs.EXECUTION_SAMPLING_INFO_EVENT_ID:
        return EXECUTION_SAMPLING_INFO_EVENT_ID;
      case JdkTypeIDs.PROCESSES:
        return PROCESSES;
      case JdkTypeIDs.OS_MEMORY_SUMMARY:
        return OS_MEMORY_SUMMARY;
      case JdkTypeIDs.OS_INFORMATION:
        return OS_INFORMATION;
      case JdkTypeIDs.CPU_INFORMATION:
        return CPU_INFORMATION;
      case JdkTypeIDs.THREAD_ALLOCATION_STATISTICS:
        return THREAD_ALLOCATION_STATISTICS;
      case JdkTypeIDs.HEAP_CONF:
        return HEAP_CONF;
      case JdkTypeIDs.GC_CONF:
        return GC_CONF;
      case JdkTypeIDs.HEAP_SUMMARY:
        return HEAP_SUMMARY;
      case JdkTypeIDs.ALLOC_INSIDE_TLAB:
        return ALLOC_INSIDE_TLAB;
      case JdkTypeIDs.ALLOC_OUTSIDE_TLAB:
        return ALLOC_OUTSIDE_TLAB;
      case JdkTypeIDs.VM_INFO:
        return VM_INFO;
      case JdkTypeIDs.CLASS_LOAD:
        return CLASS_LOAD;
      case JdkTypeIDs.CLASS_UNLOAD:
        return CLASS_UNLOAD;
      case JdkTypeIDs.CLASS_LOAD_STATISTICS:
        return CLASS_LOAD_STATISTICS;
      case JdkTypeIDs.COMPILATION:
        return COMPILATION;
      case JdkTypeIDs.FILE_WRITE:
        return FILE_WRITE;
      case JdkTypeIDs.FILE_READ:
        return FILE_READ;
      case JdkTypeIDs.SOCKET_WRITE:
        return SOCKET_WRITE;
      case JdkTypeIDs.SOCKET_READ:
        return SOCKET_READ;
      case JdkTypeIDs.THREAD_PARK:
        return THREAD_PARK;
      case JdkTypeIDs.THREAD_SLEEP:
        return THREAD_SLEEP;
      case JdkTypeIDs.MONITOR_ENTER:
        return MONITOR_ENTER;
      case JdkTypeIDs.MONITOR_WAIT:
        return MONITOR_WAIT;
      case JdkTypeIDs.METASPACE_OOM:
        return METASPACE_OOM;
      case JdkTypeIDs.CODE_CACHE_FULL:
        return CODE_CACHE_FULL;
      case JdkTypeIDs.CODE_CACHE_STATISTICS:
        return CODE_CACHE_STATISTICS;
      case JdkTypeIDs.CODE_SWEEPER_STATISTICS:
        return CODE_SWEEPER_STATISTICS;
      case JdkTypeIDs.SWEEP_CODE_CACHE:
        return SWEEP_CODE_CACHE;
      case JdkTypeIDs.ENVIRONMENT_VARIABLE:
        return ENVIRONMENT_VARIABLE;
      case JdkTypeIDs.SYSTEM_PROPERTIES:
        return SYSTEM_PROPERTIES;
      case JdkTypeIDs.OBJECT_COUNT:
        return OBJECT_COUNT;
      case JdkTypeIDs.GC_REFERENCE_STATISTICS:
        return GC_REFERENCE_STATISTICS;
      case JdkTypeIDs.OLD_OBJECT_SAMPLE:
        return OLD_OBJECT_SAMPLE;
      case JdkTypeIDs.GC_PAUSE_L3:
        return GC_PAUSE_L3;
      case JdkTypeIDs.GC_PAUSE_L2:
        return GC_PAUSE_L2;
      case JdkTypeIDs.GC_PAUSE_L1:
        return GC_PAUSE_L1;
      case JdkTypeIDs.GC_PAUSE:
        return GC_PAUSE;
      case JdkTypeIDs.METASPACE_SUMMARY:
        return METASPACE_SUMMARY;
      case JdkTypeIDs.GARBAGE_COLLECTION:
        return GARBAGE_COLLECTION;
      case JdkTypeIDs.CONCURRENT_MODE_FAILURE:
        return CONCURRENT_MODE_FAILURE;
      case JdkTypeIDs.THROWABLES_STATISTICS:
        return THROWABLES_STATISTICS;
      case JdkTypeIDs.ERRORS_THROWN:
        return ERRORS_THROWN;
      case JdkTypeIDs.EXCEPTIONS_THROWN:
        return EXCEPTIONS_THROWN;
      case JdkTypeIDs.COMPILER_STATS:
        return COMPILER_STATS;
      case JdkTypeIDs.COMPILER_FAILURE:
        return COMPILER_FAILURE;
      case JdkTypeIDs.ULONG_FLAG:
        return ULONG_FLAG;
      case JdkTypeIDs.BOOLEAN_FLAG:
        return BOOLEAN_FLAG;
      case JdkTypeIDs.STRING_FLAG:
        return STRING_FLAG;
      case JdkTypeIDs.DOUBLE_FLAG:
        return DOUBLE_FLAG;
      case JdkTypeIDs.LONG_FLAG:
        return LONG_FLAG;
      case JdkTypeIDs.INT_FLAG:
        return INT_FLAG;
      case JdkTypeIDs.UINT_FLAG:
        return UINT_FLAG;
      case JdkTypeIDs.ULONG_FLAG_CHANGED:
        return ULONG_FLAG_CHANGED;
      case JdkTypeIDs.BOOLEAN_FLAG_CHANGED:
        return BOOLEAN_FLAG_CHANGED;
      case JdkTypeIDs.STRING_FLAG_CHANGED:
        return STRING_FLAG_CHANGED;
      case JdkTypeIDs.DOUBLE_FLAG_CHANGED:
        return DOUBLE_FLAG_CHANGED;
      case JdkTypeIDs.LONG_FLAG_CHANGED:
        return LONG_FLAG_CHANGED;
      case JdkTypeIDs.INT_FLAG_CHANGED:
        return INT_FLAG_CHANGED;
      case JdkTypeIDs.UINT_FLAG_CHANGED:
        return UINT_FLAG_CHANGED;
      case JdkTypeIDs.TIME_CONVERSION:
        return TIME_CONVERSION;
      case JdkTypeIDs.THREAD_DUMP:
        return THREAD_DUMP;
      case JdkTypeIDs.JFR_DATA_LOST:
        return BUFFER_LOST_TYPE_ID;
      case JdkTypeIDs.GC_CONF_YOUNG_GENERATION:
        return GC_CONF_YOUNG_GENERATION;
      case JdkTypeIDs.GC_CONF_SURVIVOR:
        return GC_CONF_SURVIVOR;
      case JdkTypeIDs.GC_CONF_TLAB:
        return GC_CONF_TLAB;
      case JdkTypeIDs.JAVA_THREAD_START:
        return JAVA_THREAD_START;
      case JdkTypeIDs.JAVA_THREAD_END:
        return JAVA_THREAD_END;
      case JdkTypeIDs.VM_OPERATIONS:
        return VM_OPERATIONS;
      case JdkTypeIDs.THREAD_STATISTICS:
        return THREAD_STATISTICS;
      case JdkTypeIDs.CONTEXT_SWITCH_RATE:
        return CONTEXT_SWITCH_RATE;
      case JdkTypeIDs.COMPILER_CONFIG:
        return COMPILER_CONFIG;
      case JdkTypeIDs.CODE_CACHE_CONFIG:
        return CODE_CACHE_CONFIG;
      case JdkTypeIDs.CODE_SWEEPER_CONFIG:
        return CODE_SWEEPER_CONFIG;
      case JdkTypeIDs.COMPILER_PHASE:
        return COMPILER_PHASE;
      case JdkTypeIDs.GC_COLLECTOR_G1_GARBAGE_COLLECTION:
        return GC_COLLECTOR_G1_GARBAGE_COLLECTION;
      case JdkTypeIDs.GC_COLLECTOR_OLD_GARBAGE_COLLECTION:
        return GC_COLLECTOR_OLD_GARBAGE_COLLECTION;
      case JdkTypeIDs.GC_COLLECTOR_PAROLD_GARBAGE_COLLECTION:
        return GC_COLLECTOR_PAROLD_GARBAGE_COLLECTION;
      case JdkTypeIDs.GC_COLLECTOR_YOUNG_GARBAGE_COLLECTION:
        return GC_COLLECTOR_YOUNG_GARBAGE_COLLECTION;
      case JdkTypeIDs.GC_DETAILED_ALLOCATION_REQUIRING_GC:
        return GC_DETAILED_ALLOCATION_REQUIRING_GC;
      case JdkTypeIDs.GC_DETAILED_EVACUATION_FAILED:
        return GC_DETAILED_EVACUATION_FAILED;
      case JdkTypeIDs.GC_DETAILED_EVACUATION_INFO:
        return GC_DETAILED_EVACUATION_INFO;
      case JdkTypeIDs.GC_DETAILED_OBJECT_COUNT_AFTER_GC:
        return GC_DETAILED_OBJECT_COUNT_AFTER_GC;
      case JdkTypeIDs.GC_DETAILED_PROMOTION_FAILED:
        return GC_DETAILED_PROMOTION_FAILED;
      case JdkTypeIDs.GC_HEAP_PS_SUMMARY:
        return GC_HEAP_PS_SUMMARY;
      case JdkTypeIDs.GC_METASPACE_ALLOCATION_FAILURE:
        return GC_METASPACE_ALLOCATION_FAILURE;
      case JdkTypeIDs.GC_METASPACE_CHUNK_FREE_LIST_SUMMARY:
        return GC_METASPACE_CHUNK_FREE_LIST_SUMMARY;
      case JdkTypeIDs.GC_METASPACE_GC_THRESHOLD:
        return GC_METASPACE_GC_THRESHOLD;
      case JdkTypeIDs.RECORDING_SETTING:
        return RECORDING_SETTING;
      case JdkTypeIDs.RECORDINGS:
        return RECORDINGS;
      case JdkTypeIDs.GC_G1MMU:
        return GC_G1MMU;
      default:
        return typeId;
    }
  }
}
