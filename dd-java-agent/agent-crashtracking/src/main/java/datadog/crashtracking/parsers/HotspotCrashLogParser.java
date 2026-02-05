package datadog.crashtracking.parsers;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

import datadog.common.version.VersionInfo;
import datadog.crashtracking.buildid.BuildIdCollector;
import datadog.crashtracking.buildid.BuildInfo;
import datadog.crashtracking.dto.CrashLog;
import datadog.crashtracking.dto.ErrorData;
import datadog.crashtracking.dto.Metadata;
import datadog.crashtracking.dto.OSInfo;
import datadog.crashtracking.dto.ProcInfo;
import datadog.crashtracking.dto.SigInfo;
import datadog.crashtracking.dto.StackFrame;
import datadog.crashtracking.dto.StackTrace;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
  private static final String[] KNOWN_LIBRARY_NAMES = {"libjavaprofiler", "libddwaf", "libsqreen"};

  private final BuildIdCollector buildIdCollector;

  enum State {
    NEW,
    HEADER,
    MESSAGE,
    SUMMARY,
    THREAD,
    STACKTRACE,
    SEEK_DYNAMIC_LIBRARIES,
    DYNAMIC_LIBRARIES,
    DONE
  }

  private State state = State.NEW;

  public HotspotCrashLogParser() {
    this.buildIdCollector = new BuildIdCollector();
  }

  private static final Pattern PLUS_SPLITTER = Pattern.compile("\\+");
  private static final Pattern SPACE_SPLITTER = Pattern.compile("\\s+");
  private static final Pattern NEWLINE_SPLITTER = Pattern.compile("\n");
  private static final Pattern SIGNAL_PARSER = Pattern.compile("\\s*(\\w+) \\((\\w+)\\).*");
  private static final Pattern SIGINFO_PARSER =
      Pattern.compile(
          "siginfo:\\s+si_signo:\\s+(\\d+)\\s+\\((\\w+)\\),\\s+si_code:\\s+(\\d+)\\s+\\(([^)]+)\\),\\s+si_addr:\\s+(0x[0-9a-fA-F]+)");
  private static final Pattern DYNAMIC_LIBS_PATH_PARSER =
      Pattern.compile("^(?:0x)?[0-9a-fA-F]+(?:-[0-9a-fA-F]+)?\\s+(?:[^\\s/\\[]+\\s+)*(.*)$");

  private StackFrame parseLine(String line) {
    if (line == null || line.isEmpty()) {
      return null;
    }

    String functionName = null;
    Integer functionLine = null;
    String filename = null;
    String relAddress = null;
    char firstChar = line.charAt(0);
    if (line.length() > 1 && !Character.isSpaceChar(line.charAt(1))) {
      // We can find entries like this in between the frames
      // Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)
      return null;
    }
    switch (firstChar) {
      case 'J':
        {
          // J 36572 c2 datadog.trace.util.AgentTaskScheduler$PeriodicTask.run()V (25 bytes) @
          // 0x00007f2fd0198488 [0x00007f2fd0198420+0x0000000000000068]
          // J 3896 c2 java.nio.ByteBuffer.allocate(I)Ljava/nio/ByteBuffer; java.base@21.0.1 (20
          // bytes) @ 0x0000000112ad51e8 [0x0000000112ad4fc0+0x0000000000000228]
          String[] parts = SPACE_SPLITTER.split(line);
          if (parts.length > 3) {
            functionName = parts[3];
          }
          break;
        }
      case 'j':
        {
          // j  one.profiler.AsyncProfiler.stop()V+1
          String[] parts = PLUS_SPLITTER.split(line, 2);
          if (parts.length > 0 && parts[0].length() > 3) {
            functionName = parts[0].substring(3);
            if (parts.length > 1) {
              try {
                functionLine = Integer.parseInt(parts[1]);
              } catch (NumberFormatException ignored) {
              }
            }
          }
          break;
        }
      case 'C':
      case 'V':
        {
          // V  [libjvm.so+0x8fc20a]  thread_entry(JavaThread*, JavaThread*)+0x8a
          // C  [libpthread.so.0+0x13d60]
          int libstart = line.indexOf('[');
          if (libstart > 0) {
            int libend = line.indexOf(']', libstart + 1);
            if (libend > 0) {
              String libAndRelAddress = line.substring(libstart + 1, libend);
              String[] parts = PLUS_SPLITTER.split(libAndRelAddress, 2);
              filename = parts[0];
              if (parts.length > 1) {
                relAddress = parts[1];
              }

              // Extract function name if present (after the bracket)
              if (libend + 3 < line.length() && !line.endsWith("]")) {
                int plusIdx = line.lastIndexOf('+');
                functionName =
                    plusIdx > libend
                        ? line.substring(libend + 3, plusIdx).trim()
                        : line.substring(libend + 3).trim();
              }
            }
          }
          break;
        }
      case 'v':
        {
          // v  ~StubRoutines::call_stub
          // v  ~RuntimeStub::_new_array_Java 0x00000001124cb638
          if (line.length() > 3) {
            String remaining = line.substring(3).trim();
            // Check for address at the end (0x...)
            int lastSpace = remaining.lastIndexOf(' ');
            if (lastSpace > 0 && lastSpace + 1 < remaining.length()) {
              String possibleAddress = remaining.substring(lastSpace + 1);
              if (possibleAddress.startsWith("0x")) {
                relAddress = possibleAddress;
                remaining = remaining.substring(0, lastSpace).trim();
              }
            }
            // Check for + offset
            int plusIdx = remaining.lastIndexOf('+');
            functionName = plusIdx > -1 ? remaining.substring(0, plusIdx).trim() : remaining;
          }
          break;
        }
      default:
        // do nothing
        break;
    }
    if (filename != null && !filename.isEmpty()) {
      buildIdCollector.addUnprocessedLibrary(filename);
    }

    if (functionName != null || filename != null) {
      return new StackFrame(
          filename,
          functionLine,
          stripCompilerAnnotations(functionName),
          null,
          null,
          null,
          relAddress);
    }
    return null;
  }

  private static String stripCompilerAnnotations(String functionName) {
    if (functionName == null) {
      return null;
    }
    // Strip compiler annotations like [clone .isra.531], [clone .constprop.0], etc.
    int bracketIdx = functionName.lastIndexOf(" [");
    if (bracketIdx > 0 && functionName.endsWith("]")) {
      return functionName.substring(0, bracketIdx);
    }
    return functionName;
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

  private static String normalizeFilename(String filename) {
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
          if (line.startsWith("siginfo:")) {
            // siginfo: si_signo: 11 (SIGSEGV), si_code: 1 (SEGV_MAPERR), si_addr:
            // 0x0000000000000070
            final Matcher siginfoMatcher = SIGINFO_PARSER.matcher(line);
            if (siginfoMatcher.matches()) {
              Integer number = safelyParseInt(siginfoMatcher.group(1));
              String name = siginfoMatcher.group(2);
              Integer siCode = safelyParseInt(siginfoMatcher.group(3));
              String sigAction = siginfoMatcher.group(4);
              String address = siginfoMatcher.group(5);
              sigInfo = new SigInfo(number, name, siCode, sigAction, address);
            }
          } else if (line.contains("P R O C E S S")) {
            state = State.SEEK_DYNAMIC_LIBRARIES;
          } else {
            // Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
            final StackFrame frame = parseLine(line);
            if (frame != null) {
              frames.add(frame);
            }
          }
          break;
        case SEEK_DYNAMIC_LIBRARIES:
          if (line.startsWith("Dynamic libraries:")) {
            state = State.DYNAMIC_LIBRARIES;
          } else if (line.equals("END.")) {
            state = State.DONE;
          }
          break;
        case DYNAMIC_LIBRARIES:
          if (line.isEmpty()) {
            state = State.DONE;
          }
          final Matcher matcher = DYNAMIC_LIBS_PATH_PARSER.matcher(line);
          if (matcher.matches()) {
            final String pathString = matcher.group(1);
            if (pathString != null && !pathString.isEmpty()) {
              try {
                final Path path = Paths.get(pathString);
                buildIdCollector.resolveBuildId(path);
              } catch (InvalidPathException ignored) {
              }
            }
          }
          break;
        case DONE:
          // skip
          buildIdCollector.awaitCollectionDone(5);
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

    final List<StackFrame> enrichedFrames = new ArrayList<>(frames.size());

    for (StackFrame frame : frames) {
      // enrich with the build id if collected (best effort)
      if (frame.path == null) {
        enrichedFrames.add(frame);
        continue;
      }
      final BuildInfo buildInfo = buildIdCollector.getBuildInfo(frame.path);
      if (buildInfo != null) {
        enrichedFrames.add(
            new StackFrame(
                normalizeFilename(frame.path),
                frame.line,
                frame.function,
                buildInfo.buildId,
                buildInfo.buildIdType,
                buildInfo.fileType,
                frame.relativeAddress));
      } else {
        enrichedFrames.add(
            new StackFrame(
                normalizeFilename(frame.path),
                frame.line,
                frame.function,
                null,
                null,
                null,
                frame.relativeAddress));
      }
    }

    ErrorData error =
        new ErrorData(kind, message, new StackTrace(enrichedFrames.toArray(new StackFrame[0])));
    // We can not really extract the full metadata and os info from the crash log
    // This code assumes the parser is run on the same machine as the crash happened
    Metadata metadata = new Metadata("dd-trace-java", VersionInfo.VERSION, "java", null);
    Integer parsedPid = safelyParseInt(pid);
    ProcInfo procInfo = parsedPid != null ? new ProcInfo(parsedPid) : null;
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

  static Integer safelyParseInt(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
