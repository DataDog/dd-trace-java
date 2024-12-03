package com.datadog.crashtracking.parsers;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

import com.datadog.crashtracking.dto.CrashLog;
import com.datadog.crashtracking.dto.ErrorData;
import com.datadog.crashtracking.dto.Metadata;
import com.datadog.crashtracking.dto.OSInfo;
import com.datadog.crashtracking.dto.ProcInfo;
import com.datadog.crashtracking.dto.SemanticVersion;
import com.datadog.crashtracking.dto.StackFrame;
import com.datadog.crashtracking.dto.StackTrace;
import datadog.common.version.VersionInfo;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class HotspotCrashLogParser {
  private static final DateTimeFormatter ZONED_DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy zzz", Locale.getDefault());
  private static final DateTimeFormatter OFFSET_DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy X", Locale.getDefault());

  enum State {
    NEW,
    HEADER,
    MESSAGE,
    SUMMARY,
    THREAD,
    STACKTRACE,
    DONE
  }

  private static class FileLine {
    final String file;
    final int lineNum;

    public FileLine(String file, int lineNum) {
      this.file = file;
      this.lineNum = lineNum;
    }
  }

  private State state = State.NEW;

  private static final Pattern COLUMN_SPLITTER = Pattern.compile(":");
  private static final Pattern SPACE_SPLITTER = Pattern.compile("\\s+");
  private static final Pattern NEWLINE_SPLITTER = Pattern.compile("\n");

  private FileLine fileLine(String line) {
    if (line.endsWith(")")) {
      int idx = line.lastIndexOf('(');
      String src = line.substring(idx + 1, line.length() - 1);
      String[] srcParts = COLUMN_SPLITTER.split(src);
      if (srcParts.length == 2) {
        return new FileLine(srcParts[0], Integer.parseInt(srcParts[1]));
      }
    }
    return null;
  }

  private StackFrame parseLine(String line) {
    FileLine fLine = fileLine(line);
    String function = null;
    char firstChar = line.charAt(0);
    switch (firstChar) {
      case 'J':
        {
          // J 36572 c2 datadog.trace.util.AgentTaskScheduler$PeriodicTask.run()V (25 bytes) @
          // 0x00007f2fd0198488 [0x00007f2fd0198420+0x0000000000000068]
          String[] parts = SPACE_SPLITTER.split(line);
          function = parts[3];
          break;
        }
      case 'j':
        {
          // j  one.profiler.AsyncProfiler.stop()V+1
          int plusIdx = line.lastIndexOf('+');
          function = plusIdx > -1 ? line.substring(3, plusIdx) : line.substring(3);
          break;
        }
      case 'C':
      case 'V':
        {
          // V  [libjvm.so+0x8fc20a]  thread_entry(JavaThread*, JavaThread*)+0x8a
          if (line.endsWith("]")) {
            // C  [libpthread.so.0+0x13d60]
            function = line.substring(4, line.length() - 1);
          } else {
            int plusIdx = line.lastIndexOf('+');
            function =
                plusIdx > -1
                    ? line.substring(line.indexOf(']') + 3, plusIdx)
                    : line.substring(line.indexOf(']') + 3);
          }
          break;
        }
      case 'v':
        {
          // v  ~StubRoutines::call_stub
          int plusIdx = line.lastIndexOf('+');
          function =
              plusIdx > -1
                  ? line.substring(line.indexOf(']') + 3, plusIdx)
                  : line.substring(line.indexOf(']') + 3);
          break;
        }
      default:
        // do nothing
        break;
    }
    if (function != null || fLine != null) {
      return new StackFrame(
          fLine != null ? fLine.file : null, fLine != null ? fLine.lineNum : 0, function);
    }
    return null;
  }

  public CrashLog parse(String crashLog) {
    String signal = null;
    String pid = null;
    List<StackFrame> frames = new ArrayList<>();
    String datetime = null;
    StringBuilder message = new StringBuilder();

    String[] lines = NEWLINE_SPLITTER.split(crashLog);
    outer:
    for (String line : lines) {
      switch (state) {
        case NEW:
          if (line.startsWith(
              "# A fatal error has been detected by the Java Runtime Environment:")) {
            message.append("\n\n");
            state = State.MESSAGE; // jump directly to MESSAGE state
          }
          break;
        case MESSAGE:
          if (line.toLowerCase().contains("core dump")) {
            // break out of the message block
            state = State.HEADER;
          } else if (!"#".equals(line)) {
            if (signal == null) {
              // first non-empty line after the message is the signal
              signal =
                  line.substring(
                      3,
                      line.indexOf(
                          ' ', 3)); // #   SIGSEGV (0xb) at pc=0x00007f8b1c0b3d7d, pid=1, tid=1
              int pidIdx = line.indexOf("pid=");
              if (pidIdx > -1) {
                int endIdx = line.indexOf(',', pidIdx);
                pid = line.substring(pidIdx + 4, endIdx);
              }
            } else {
              message.append(line.substring(2)).append('\n');
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
            message.append('\n').append(line).append('\n');
            state = State.STACKTRACE;
          }
          break;
        case STACKTRACE:
          if (line.isEmpty()) {
            state = State.DONE;
          } else {
            // Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
            message.append(line).append('\n');
            frames.add(parseLine(line));
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
      return null;
    }

    ErrorData error =
        new ErrorData(
            signal, message.toString(), new StackTrace(frames.toArray(new StackFrame[0])));
    // We can not really extract the full metadata and os info from the crash log
    // This code assumes the parser is run on the same machine as the crash happened
    Metadata metadata = new Metadata("dd-trace-java", VersionInfo.VERSION, "java", null);
    OSInfo osInfo =
        new OSInfo(
            System.getProperty("os.arch"),
            System.getProperty("sun.arch.data.model"),
            System.getProperty("os.name"),
            SemanticVersion.of(System.getProperty("os.version")));
    ProcInfo procInfo = pid != null ? new ProcInfo(pid) : null;
    return new CrashLog(false, datetime, error, metadata, osInfo, procInfo);
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
