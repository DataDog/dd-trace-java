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
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HotspotCrashLogParser {
  private static final DateTimeFormatter ZONED_DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy zzz", Locale.getDefault());
  private static final DateTimeFormatter OFFSET_DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy X", Locale.getDefault());
  private static final String OOM_MARKER = "OutOfMemory encountered: ";

  // all lowercased
  private static final String[] KNOWN_LIBRARY_NAMES =
      {"libjavaprofiler", "libddwaf", "libsqreen"};

  enum State {
    NEW,
    HEADER,
    MESSAGE,
    SUMMARY,
    THREAD,
    STACKTRACE,
    DONE
  }

  private State state = State.NEW;

  private static final Pattern PLUS_SPLITTER = Pattern.compile("\\+");
  private static final Pattern SPACE_SPLITTER = Pattern.compile("\\s+");
  private static final Pattern NEWLINE_SPLITTER = Pattern.compile("\n");
  private static final Pattern SIGNAL_PARSER = Pattern.compile("\\s*(\\w+) \\((\\w+)\\).*");

  private StackFrame parseLine(String line) {
    String functionName = null;
    Integer functionLine = null;
    String filename = null;
    char firstChar = line.charAt(0);
    switch (firstChar) {
      case 'J':
        {
          // J 36572 c2 datadog.trace.util.AgentTaskScheduler$PeriodicTask.run()V (25 bytes) @
          // 0x00007f2fd0198488 [0x00007f2fd0198420+0x0000000000000068]
          String[] parts = SPACE_SPLITTER.split(line);
          functionName = parts[3];
          break;
        }
      case 'j':
        {
          // j  one.profiler.AsyncProfiler.stop()V+1
          String[] parts = PLUS_SPLITTER.split(line, 2);
          functionName = parts[0].substring(3);
          if (parts.length > 1) {
            try {
              functionLine = Integer.parseInt(parts[1]);
            } catch (Throwable ignored) {
            }
          }
          break;
        }
      case 'C':
      case 'V':
        {
          // V  [libjvm.so+0x8fc20a]  thread_entry(JavaThread*, JavaThread*)+0x8a
          if (line.endsWith("]")) {
            // C  [libpthread.so.0+0x13d60]
            functionName = line.substring(4, line.length() - 1);
          } else {
            int plusIdx = line.lastIndexOf('+');
            functionName =
                plusIdx > -1
                    ? line.substring(line.indexOf(']') + 3, plusIdx)
                    : line.substring(line.indexOf(']') + 3);
          }
          int libstart = line.indexOf('[');
          if (libstart > 0) {
            int libend = line.indexOf(']', libstart + 1);
            if (libend > 0) {
              String[] parts = PLUS_SPLITTER.split(line.substring(libstart + 1, libend), 2);
              filename = normalizeFilename(parts[0]);
              // TODO: extract relative address for second part and send to the intake
            }
          }
          break;
        }
      case 'v':
        {
          // v  ~StubRoutines::call_stub
          int plusIdx = line.lastIndexOf('+');
          functionName =
              plusIdx > -1
                  ? line.substring(line.indexOf(']') + 3, plusIdx)
                  : line.substring(line.indexOf(']') + 3);
          break;
        }
      default:
        // do nothing
        break;
    }
    if (functionName != null) {
      return new StackFrame(filename, functionLine, functionName);
    }
    return null;
  }

  private static String knownLibraryPrefix(String filename) {
    final String lowerCased = filename.toLowerCase(Locale.ROOT);
    for (String prefix : KNOWN_LIBRARY_NAMES) {
      if (lowerCased.startsWith(prefix)) {
        return prefix;
      }
    }
    return null;
  }

  private String normalizeFilename(String filename) {
    if (filename == null) {
      return null;
    }
    final String prefix = knownLibraryPrefix(filename);
    if (prefix == null) {
      return filename;
    }

    final int prefixLen = prefix.length();
    final int end = filename.indexOf('.', prefixLen);
    if (end < prefixLen) {
      return filename;
    }
    return filename.substring(0, prefixLen) + filename.substring(end);
  }

  public CrashLog parse(String uuid, String crashLog) {
    SigInfo sigInfo = null;
    String pid = null;
    List<StackFrame> frames = new ArrayList<>();
    String datetime = null;
    boolean incomplete = false;
    String oomMessage = null;

    String[] lines = NEWLINE_SPLITTER.split(crashLog);
    outer:
    for (String line : lines) {
      switch (state) {
        case NEW:
          if (line.startsWith(
              "# A fatal error has been detected by the Java Runtime Environment:")) {
            state = State.MESSAGE; // jump directly to MESSAGE state
          }
          break;
        case MESSAGE:
          if (line.toLowerCase().contains("core dump")) {
            // break out of the message block
            state = State.HEADER;
          } else if ((oomMessage == null
              && (sigInfo == null || "INVALID".equals(sigInfo.name))
              && !"#".equals(line))) {
            // note: some jvm might use INVALID to represent a OOM crash too.
            final int oomIdx = line.indexOf(OOM_MARKER);
            if (oomIdx > 0) {
              oomMessage = line.substring(oomIdx + OOM_MARKER.length());
            } else {
              String name = null, address = null;
              int number = 0;
              // first non-empty line after the message is the signal
              final Matcher signalMatcher = SIGNAL_PARSER.matcher(line.substring(3));
              // #   SIGSEGV (0xb) at pc=0x00007f8b1c0b3d7d, pid=1, tid=1
              if (signalMatcher.matches()) {
                name = signalMatcher.group(1);
                try {
                  number = Integer.decode(signalMatcher.group(2));
                } catch (Throwable ignored) {
                }
              }

              int pcIdx = line.indexOf("pc=");
              if (pcIdx > -1) {
                int endIdx = line.indexOf(',', pcIdx);
                address = line.substring(pcIdx + 3, endIdx);
              }
              if (name != null) {
                sigInfo = new SigInfo(number, name, address);
              }

              int pidIdx = line.indexOf("pid=");
              if (pidIdx > -1) {
                int endIdx = line.indexOf(',', pidIdx);
                pid = line.substring(pidIdx + 4, endIdx);
              }
            }
          }
          break;
        case HEADER:
          if (line.contains("S U M M A R Y")) {
            state = State.SUMMARY;
          } else if (line.contains("T H R E A D")) {
            state = State.THREAD;
          }
          break;
        case SUMMARY:
          if (line.contains("T H R E A D")) {
            state = State.THREAD;
          } else if (line.contains("Time: ")) {
            int idx = line.lastIndexOf(" elapsed time: ");
            datetime = dateTimeToISO(idx > -1 ? line.substring(6, idx) : line.substring(6));
          }
          break;
        case THREAD:
          // Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
          if (line.startsWith("Native frames: ")) {
            state = State.STACKTRACE;
          }
          break;
        case STACKTRACE:
          if (line.isEmpty()) {
            state = State.DONE;
          } else {
            // Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
            final StackFrame frame = parseLine(line);
            if (frame != null) {
              frames.add(frame);
            }
          }
          break;
        case DONE:
          // skip
          break outer;
        default:
          // unexpected parser state; bail out
          break outer;
      }
    }

    if (state != State.DONE) {
      // incomplete crash log
      incomplete = true;
    }
    final String kind;
    final String message;
    if (oomMessage != null) {
      kind = "OutOfMemory";
      message = oomMessage;
    } else {
      kind = sigInfo != null && sigInfo.name != null ? sigInfo.name : "UNKNOWN";
      message = "Process terminated by signal " + kind;
    }

    ErrorData error =
        new ErrorData(kind, message, new StackTrace(frames.toArray(new StackFrame[0])));
    // We can not really extract the full metadata and os info from the crash log
    // This code assumes the parser is run on the same machine as the crash happened
    Metadata metadata = new Metadata("dd-trace-java", VersionInfo.VERSION, "java", null);
    ProcInfo procInfo = pid != null ? new ProcInfo(pid) : null;
    return new CrashLog(
        uuid, incomplete, datetime, error, metadata, OSInfo.current(), procInfo, sigInfo, "1.0");
  }

  static String dateTimeToISO(String datetime) {
    try {
      return ZonedDateTime.parse(datetime, ZONED_DATE_TIME_FORMATTER).format(ISO_OFFSET_DATE_TIME);
    } catch (DateTimeParseException ignored) {
      try {
        return OffsetDateTime.parse(datetime, OFFSET_DATE_TIME_FORMATTER)
            .format(ISO_OFFSET_DATE_TIME);
      } catch (DateTimeParseException e3) {
        // Failed to parse date time
        return null;
      }
    }
  }
}
