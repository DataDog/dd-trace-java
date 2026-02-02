package datadog.crashtracking.parsers;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

import datadog.common.version.VersionInfo;
import datadog.crashtracking.dto.CrashLog;
import datadog.crashtracking.dto.ErrorData;
import datadog.crashtracking.dto.Metadata;
import datadog.crashtracking.dto.OSInfo;
import datadog.crashtracking.dto.ProcInfo;
import datadog.crashtracking.dto.SigInfo;
import datadog.crashtracking.dto.StackFrame;
import datadog.crashtracking.dto.StackTrace;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for J9/OpenJ9 javacore crash dump files.
 *
 * <p>J9 javacore files use a hierarchical tag-based format with numbered prefixes indicating
 * section depth (0SECTION, 1TI*, 2XH*, 3XM*, 4XE*, etc).
 *
 * <p>Key sections:
 *
 * <ul>
 *   <li>TITLE - Contains dump event type and timestamp
 *   <li>GPINFO - General information including OS level and CPU architecture
 *   <li>ENVINFO - Environment info including process ID
 *   <li>THREADS - Thread information and stack traces
 * </ul>
 */
public final class J9JavacoreParser {

  private static final String OOM_MARKER = "OutOfMemory";

  // J9 event types mapped to signal names and numbers
  private static final String EVENT_GPF = "gpf";
  private static final String EVENT_ABORT = "abort";
  private static final String EVENT_SYSTHROW = "systhrow";

  // Section markers
  private static final String SECTION_MARKER = "0SECTION";
  private static final String SECTION_TITLE = "TITLE";
  private static final String SECTION_GPINFO = "GPINFO";
  private static final String SECTION_ENVINFO = "ENVINFO";
  private static final String SECTION_THREADS = "THREADS";

  // Tag patterns
  private static final Pattern NEWLINE_SPLITTER = Pattern.compile("\\n");
  private static final Pattern SIG_INFO_PATTERN =
      Pattern.compile("1TISIGINFO\\s+Dump Event \"(\\w+)\"(?:\\s+\\((\\w+)\\))?.*");
  private static final Pattern DATETIME_PATTERN =
      Pattern.compile(
          "1TIDATETIME\\s+Date:\\s+(\\d{4}/\\d{2}/\\d{2})\\s+at\\s+(\\d{2}:\\d{2}:\\d{2})(?::(\\d{3}))?.*");
  private static final Pattern PID_PATTERN =
      Pattern.compile("1CIPROCESSID\\s+Process ID:\\s+(\\d+).*");
  private static final Pattern CURRENT_THREAD_PATTERN =
      Pattern.compile("1XMCURTHDINFO\\s+Current thread.*");
  private static final Pattern THREAD_INFO_PATTERN =
      Pattern.compile("3XMTHREADINFO\\s+\"(.+?)\".*");
  private static final Pattern JAVA_STACK_PATTERN = Pattern.compile("4XESTACKTRACE\\s+at\\s+(.+)");
  private static final Pattern NATIVE_STACK_PATTERN = Pattern.compile("4XENATIVESTACK\\s+(.+)");
  private static final Pattern EXCEPTION_DETAIL_PATTERN =
      Pattern.compile("1TISIGINFO.*[Dd]etail\\s+\"(.+?)\".*");

  // Date time formatter for J9 format: YYYY/MM/DD at HH:MM:SS
  private static final DateTimeFormatter J9_DATETIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT);

  enum Section {
    NONE,
    TITLE,
    GPINFO,
    ENVINFO,
    THREADS,
    OTHER
  }

  public CrashLog parse(String uuid, String javacoreContent) {
    // Parser state - kept as local variables to ensure thread safety and reusability
    Section currentSection = Section.NONE;
    boolean inCurrentThread = false;
    boolean collectingStack = false;
    String eventType = null;
    String eventCode = null;
    String exceptionDetail = null;
    String pid = null;
    String datetime = null;
    List<StackFrame> frames = new ArrayList<>();
    boolean incomplete = false;
    boolean foundThreadSection = false;

    String[] lines = NEWLINE_SPLITTER.split(javacoreContent);

    for (String line : lines) {
      // Track section changes
      if (line.startsWith(SECTION_MARKER)) {
        currentSection = detectSection(line);
        if (currentSection == Section.THREADS) {
          foundThreadSection = true;
          inCurrentThread = false;
          collectingStack = false;
        }
        continue;
      }

      switch (currentSection) {
        case TITLE:
          // Extract event type (gpf, abort, systhrow)
          Matcher sigMatcher = SIG_INFO_PATTERN.matcher(line);
          if (sigMatcher.matches()) {
            eventType = sigMatcher.group(1);
            eventCode = sigMatcher.group(2);
          }

          // Extract exception detail for systhrow events
          Matcher detailMatcher = EXCEPTION_DETAIL_PATTERN.matcher(line);
          if (detailMatcher.matches()) {
            exceptionDetail = detailMatcher.group(1);
          }

          // Extract timestamp
          Matcher dtMatcher = DATETIME_PATTERN.matcher(line);
          if (dtMatcher.matches()) {
            datetime = parseDateTime(dtMatcher.group(1), dtMatcher.group(2));
          }
          break;

        case GPINFO:
          // OS level and CPU architecture are available in GPINFO section but currently
          // not extracted since OSInfo.current() is used for the crash report.
          // If needed in the future, parse 2XHOSLEVEL and 3XHCPUARCH tags here.
          break;

        case ENVINFO:
          // Extract process ID
          Matcher pidMatcher = PID_PATTERN.matcher(line);
          if (pidMatcher.matches()) {
            pid = pidMatcher.group(1);
          }
          break;

        case THREADS:
          // Look for current thread marker
          if (CURRENT_THREAD_PATTERN.matcher(line).matches()) {
            inCurrentThread = true;
            continue;
          }

          // If in current thread section, look for thread info start
          if (inCurrentThread && line.startsWith("3XMTHREADINFO")) {
            Matcher threadMatcher = THREAD_INFO_PATTERN.matcher(line);
            if (threadMatcher.matches()) {
              collectingStack = true;
            }
            continue;
          }

          // Collect stack frames for current thread
          if (collectingStack) {
            // Java stack frame
            Matcher javaStackMatcher = JAVA_STACK_PATTERN.matcher(line);
            if (javaStackMatcher.matches()) {
              StackFrame frame = parseJavaStackFrame(javaStackMatcher.group(1));
              if (frame != null) {
                frames.add(frame);
              }
              continue;
            }

            // Native stack frame
            Matcher nativeStackMatcher = NATIVE_STACK_PATTERN.matcher(line);
            if (nativeStackMatcher.matches()) {
              StackFrame frame = parseNativeStackFrame(nativeStackMatcher.group(1));
              if (frame != null) {
                frames.add(frame);
              }
              continue;
            }

            // End of stack trace - blank line or new section
            if (line.isEmpty() || line.startsWith("NULL") || line.startsWith("0SECTION")) {
              collectingStack = false;
              inCurrentThread = false;
            }
          }
          break;

        default:
          break;
      }
    }

    // Check for incomplete parse
    if (!foundThreadSection || (eventType == null && exceptionDetail == null)) {
      incomplete = true;
    }

    // Build signal info from event type
    SigInfo sigInfo = buildSigInfo(eventType, eventCode);

    // Determine error kind and message
    String kind;
    String message;
    if (isOOMEvent(eventType, exceptionDetail)) {
      kind = "OutOfMemory";
      message = exceptionDetail != null ? exceptionDetail : "OutOfMemoryError";
    } else if (eventType != null) {
      kind =
          sigInfo != null && sigInfo.name != null
              ? sigInfo.name
              : eventType.toUpperCase(Locale.ROOT);
      message = "Process terminated by signal " + kind;
    } else {
      kind = "UNKNOWN";
      message = "Unknown crash event";
    }

    ErrorData error =
        new ErrorData(kind, message, new StackTrace(frames.toArray(new StackFrame[0])));
    Metadata metadata = new Metadata("dd-trace-java", VersionInfo.VERSION, "java", null);
    ProcInfo procInfo = pid != null ? new ProcInfo(pid) : null;

    return new CrashLog(
        uuid, incomplete, datetime, error, metadata, OSInfo.current(), procInfo, sigInfo, "1.0");
  }

  private static Section detectSection(String line) {
    if (line.contains(SECTION_TITLE)) {
      return Section.TITLE;
    } else if (line.contains(SECTION_GPINFO)) {
      return Section.GPINFO;
    } else if (line.contains(SECTION_ENVINFO)) {
      return Section.ENVINFO;
    } else if (line.contains(SECTION_THREADS)) {
      return Section.THREADS;
    } else {
      return Section.OTHER;
    }
  }

  private boolean isOOMEvent(String eventType, String exceptionDetail) {
    if (EVENT_SYSTHROW.equals(eventType) && exceptionDetail != null) {
      return exceptionDetail.contains(OOM_MARKER);
    }
    return false;
  }

  private SigInfo buildSigInfo(String eventType, String eventCode) {
    if (eventType == null) {
      return null;
    }

    String signalName;
    int signalNumber;

    switch (eventType.toLowerCase(Locale.ROOT)) {
      case EVENT_GPF:
        signalName = "SIGSEGV";
        signalNumber = 11;
        break;
      case EVENT_ABORT:
        signalName = "SIGABRT";
        signalNumber = 6;
        break;
      case EVENT_SYSTHROW:
        signalName = "EXCEPTION";
        signalNumber = 0;
        break;
      default:
        signalName = eventType.toUpperCase(Locale.ROOT);
        signalNumber = parseEventCode(eventCode);
    }

    return new SigInfo(signalNumber, signalName, null);
  }

  private int parseEventCode(String eventCode) {
    if (eventCode == null) {
      return 0;
    }
    try {
      return Integer.decode(eventCode);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Parse J9 Java stack frame format: package/Class.method(Source.java:line) or
   * package/Class.method(Native Method)
   */
  private StackFrame parseJavaStackFrame(String frameText) {
    String function = frameText.trim();
    String file = null;
    Integer line = null;

    // Extract source file and line: method(File.java:123)
    int parenStart = function.lastIndexOf('(');
    int parenEnd = function.lastIndexOf(')');
    if (parenStart > 0 && parenEnd > parenStart) {
      String sourceInfo = function.substring(parenStart + 1, parenEnd);
      function = function.substring(0, parenStart);

      if (!"Native Method".equals(sourceInfo)) {
        int colonIdx = sourceInfo.lastIndexOf(':');
        if (colonIdx > 0) {
          file = sourceInfo.substring(0, colonIdx);
          try {
            line = Integer.parseInt(sourceInfo.substring(colonIdx + 1));
          } catch (NumberFormatException ignored) {
            // Keep line as null
          }
        } else {
          file = sourceInfo;
        }
      }
    }

    return new StackFrame(file, line, function);
  }

  /**
   * Parse J9 native stack frame format: (0xADDRESS [library+offset]) or functionName+offset
   * (address [library+offset])
   */
  private StackFrame parseNativeStackFrame(String frameText) {
    String text = frameText.trim();
    String function = null;
    String file = null;

    // Try to extract library from [lib+offset] pattern
    int bracketStart = text.indexOf('[');
    int bracketEnd = text.indexOf(']', bracketStart + 1);
    if (bracketStart >= 0 && bracketEnd > bracketStart) {
      String libInfo = text.substring(bracketStart + 1, bracketEnd);
      int plusIdx = libInfo.indexOf('+');
      if (plusIdx > 0) {
        file = libInfo.substring(0, plusIdx);
      } else {
        file = libInfo;
      }
    }

    // Try to extract function name (before the first parenthesis or bracket)
    int funcEnd = text.indexOf('(');
    if (funcEnd < 0) {
      funcEnd = bracketStart >= 0 ? bracketStart : text.length();
    }
    if (funcEnd > 0) {
      String funcPart = text.substring(0, funcEnd).trim();
      // Remove trailing +offset if present
      int plusIdx = funcPart.lastIndexOf('+');
      if (plusIdx > 0) {
        function = funcPart.substring(0, plusIdx).trim();
      } else if (!funcPart.isEmpty() && !funcPart.startsWith("0x")) {
        function = funcPart;
      }
    }

    // If we couldn't extract a function name, use the whole text
    if (function == null || function.isEmpty()) {
      function = text;
    }

    return new StackFrame(file, null, function);
  }

  private String parseDateTime(String datePart, String timePart) {
    try {
      String combined = datePart + " " + timePart;
      LocalDateTime localDateTime = LocalDateTime.parse(combined, J9_DATETIME_FORMATTER);
      ZonedDateTime zonedDateTime = localDateTime.atZone(ZoneId.systemDefault());
      return zonedDateTime.format(ISO_OFFSET_DATE_TIME);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
