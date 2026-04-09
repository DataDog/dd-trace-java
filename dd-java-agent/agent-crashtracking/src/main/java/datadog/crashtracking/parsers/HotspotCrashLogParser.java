package datadog.crashtracking.parsers;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

import datadog.common.version.VersionInfo;
import datadog.crashtracking.buildid.BuildIdCollector;
import datadog.crashtracking.buildid.BuildInfo;
import datadog.crashtracking.dto.CrashLog;
import datadog.crashtracking.dto.DynamicLibs;
import datadog.crashtracking.dto.ErrorData;
import datadog.crashtracking.dto.Experimental;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for HotSpot JVM fatal error logs ({@code hs_err_pidNNN.log}).
 *
 * <p>The log is parsed using a linear state machine that mirrors the deterministic section order
 * emitted by {@code VMError::report()} in HotSpot. The section order is fixed for a given platform
 * but differs across OS/CPU combinations.
 *
 * <p>If an early sentinel line is absent (e.g. {@code "Native frames:"} is missing because the JVM
 * crashed before producing a stack), the state machine will not advance past {@code THREAD} state
 * and subsequent sections such as {@code siginfo} and registers will be silently skipped. The
 * resulting {@link datadog.crashtracking.dto.CrashLog} will be marked {@code incomplete}.
 */
public final class HotspotCrashLogParser {
  private static final String HOTSPOT_JVM_ARGS_PREFIX = "jvm_args:";
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
    REGISTER_TO_MEMORY_MAPPING,
    REGISTERS,
    PROCESS,
    VM_ARGUMENTS,
    DYNAMIC_LIBRARIES,
    SYSTEM,
    DONE
  }

  private State state = State.NEW;

  public HotspotCrashLogParser() {
    this.buildIdCollector = new BuildIdCollector();
  }

  private static final Pattern PLUS_SPLITTER = Pattern.compile("\\+");
  private static final Pattern SPACE_SPLITTER = Pattern.compile("\\s+");
  private static final Pattern NEWLINE_SPLITTER = Pattern.compile("\n");
  // Groups: 1=si_signo, 2=signal name, 3=si_code, 4=si_code name,
  //         5=si_addr (null for SI_USER), 6=si_pid (null for si_addr), 7=si_uid (null for si_addr)
  private static final Pattern SIGINFO_PARSER =
      Pattern.compile(
          "siginfo:\\s+si_signo:\\s+(\\d+)\\s+\\((\\w+)\\),\\s+si_code:\\s+(\\d+)\\s+\\(([^)]+)\\),\\s+"
              + "(?:si_addr:\\s+(0x[0-9a-fA-F]+)|si_pid:\\s+(\\d+),\\s+si_uid:\\s+(\\d+))");
  private static final Pattern DYNAMIC_LIBS_PATH_PARSER =
      Pattern.compile("^(?:0x)?[0-9a-fA-F]+(?:-[0-9a-fA-F]+)?\\s+(?:[^\\s/\\[]+\\s+)*(.*)$");
  // Matches register entries like:
  // * RAX=0x..., R8 =0x..., TRAPNO=0x... (x86-64)
  // * R0=0x..., R30=0x... (Linux aarch64)
  // * x0=0x..., fp=0x..., lr=0x..., sp=0x..., pc=0x... (macOS aarch64)
  // Note that register formatting varies by platform, the JVM crash handler can emit one or four
  // per line.
  private static final Pattern REGISTER_ENTRY_PARSER =
      Pattern.compile("([A-Za-z][A-Za-z0-9]*)\\s*=\\s*(0x[0-9a-fA-F]+)");
  private static final Pattern REGISTER_TO_MEMORY_MAPPING_PARSER =
      Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9]*)\\s*=");
  // Used for the REGISTERS-state exit condition only: the register name must start the line
  // (after optional whitespace). This prevents lines like "Top of Stack: (sp=0x...)" and
  // "Instructions: (pc=0x...)" from being mistaken for register entries by REGISTER_ENTRY_PARSER's
  // find(), which would otherwise match the lowercase "sp"/"pc" tokens embedded in those lines.
  private static final Pattern REGISTER_LINE_START =
      Pattern.compile("^\\s*[A-Za-z][A-Za-z0-9]*\\s*=\\s*0x");
  private static final Pattern SUBSECTION_TITLE = Pattern.compile("^\\s*[A-Za-z][\\w ]*:.+$");
  private static final Pattern COMPILED_JAVA_ADDRESS_PARSER =
      Pattern.compile("@\\s+(0x[0-9a-fA-F]+)\\s+\\[(0x[0-9a-fA-F]+)\\+(0x[0-9a-fA-F]+)\\]");

  // HotSpot crash logs encode the execution kind in the first column of each frame line.
  // Source references:
  // JDK 8:
  // https://github.com/openjdk/jdk8u/blob/73c9c6bcd062196cbebc4d9f22b13d2e20a14f98/hotspot/src/share/vm/runtime/frame.cpp#L710-L724
  // JDK 11:
  // https://github.com/openjdk/jdk11u/blob/970d6cf491a55fd6ab98ec3f449c13a58633078a/src/hotspot/share/runtime/frame.cpp#L647-L662
  // JDK 25:
  // https://github.com/openjdk/jdk25u/blob/2fe611a2a3386d097f636c15bd4d396a82dc695e/src/hotspot/share/runtime/frame.cpp#L652-L666
  // Mainline:
  // https://github.com/openjdk/jdk/blob/53c864a881d2183d3664a6a5a56480bd99fffe45/src/hotspot/share/runtime/frame.cpp#L647-L661
  // Note: the marker set changes across JDK lines. In particular, "A" appears in some HotSpot
  // versions but not all, so this mapping is best-effort rather than a stable cross-version enum.
  private static String hotspotFrameType(char marker) {
    switch (marker) {
      case 'J':
        return "compiled";
      case 'A': // exists in JDK 11
        return "aot_compiled";
      case 'j':
        return "interpreted";
      case 'V':
        return "vm";
      case 'v':
        return "stub";
      case 'C':
        return "native";
      default:
        return null;
    }
  }

  private StackFrame parseLine(String line) {
    if (line == null || line.isEmpty()) {
      return null;
    }

    String functionName = null;
    Integer functionLine = null;
    String filename = null;
    String ip = null;
    String relAddress = null;
    String symbolAddress = null;
    char firstChar = line.charAt(0);
    String frameType = hotspotFrameType(firstChar);
    if (line.length() > 1 && !Character.isSpaceChar(line.charAt(1))) {
      // We can find entries like this in between the frames
      // Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)
      return null;
    }
    switch (firstChar) {
      case 'J':
      case 'A':
        {
          // spotless:off
          // J 36572 c2 datadog.trace.util.AgentTaskScheduler$PeriodicTask.run()V (25 bytes) @ 0x00007f2fd0198488 [0x00007f2fd0198420+0x0000000000000068]
          // J 3896 c2 java.nio.ByteBuffer.allocate(I)Ljava/nio/ByteBuffer; java.base@21.0.1 (20 bytes) @ 0x0000000112ad51e8 [0x0000000112ad4fc0+0x0000000000000228]
          // J 302  java.util.zip.ZipFile.getEntry(J[BZ)J (0 bytes) @ 0x00007fa287303dce [0x00007fa287303d00+0xce]
          // spotless:on
          String[] parts = SPACE_SPLITTER.split(line);
          int bytesToken = -1;
          for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].startsWith("(") && "bytes)".equals(parts[i + 1])) {
              bytesToken = i;
              break;
            }
          }
          if (bytesToken > 1) {
            String candidate = parts[bytesToken - 1];
            // Newer JVMs insert a module token before "(NN bytes)".
            if (candidate.contains("@")) {
              candidate = parts[bytesToken - 2];
            }
            if (!candidate.startsWith("(")) {
              functionName = candidate;
            }
          } else if (parts.length > 3 && !parts[3].startsWith("(")) {
            functionName = parts[3];
          }

          Matcher matcher = COMPILED_JAVA_ADDRESS_PARSER.matcher(line);
          if (matcher.find()) {
            ip = matcher.group(1);
            symbolAddress = matcher.group(2);
            relAddress = matcher.group(3);
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
              // Keep the relative address offset as part of the function name
              if (libend + 3 < line.length() && !line.endsWith("]")) {
                functionName = line.substring(libend + 3).trim();
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
            // Keep the relative address offset as part of the function name
            functionName = remaining;
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
          frameType,
          null,
          null,
          null,
          ip,
          symbolAddress,
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

  static String parseCurrentThreadName(String line) {
    if (line == null || !line.startsWith("Current thread ")) {
      return null;
    }
    final int separator = line.indexOf(':');
    if (separator < 0) {
      return null;
    }

    String threadDescriptor = line.substring(separator + 1).trim();
    final int metadataStart = threadDescriptor.indexOf('[');
    if (metadataStart >= 0) {
      threadDescriptor = threadDescriptor.substring(0, metadataStart).trim();
    }
    if (threadDescriptor.isEmpty()) {
      return null;
    }
    return threadDescriptor;
  }

  private static List<String> parseHotspotJvmArgs(String line) {
    if (line == null || !line.startsWith(HOTSPOT_JVM_ARGS_PREFIX)) {
      return null;
    }
    return RuntimeArgs.parseVmArgs(line.substring(HOTSPOT_JVM_ARGS_PREFIX.length()));
  }

  public CrashLog parse(String uuid, String crashLog) {
    SigInfo sigInfo = null;
    String pid = null;
    String threadName = null;
    List<StackFrame> frames = new ArrayList<>();
    String datetime = null;
    String datetimeRaw = null;
    boolean incomplete = false;
    String oomMessage = null;
    Map<String, String> registers = new LinkedHashMap<>();
    Map<String, String> registerToMemoryMapping = new LinkedHashMap<>();
    String currentRegisterToMemoryMapping = "";
    List<String> runtimeArgs = null;
    List<String> dynamicLibraryLines = null;
    String dynamicLibraryKey = null;
    boolean previousLineBlank = false;
    State nextThreadSectionState = null;

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
          } else if (oomMessage == null
              && (sigInfo == null || "INVALID".equals(sigInfo.name))
              && !"#".equals(line)) {
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
          if (threadName == null) {
            threadName = parseCurrentThreadName(line);
          }
          // Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
          if (line.startsWith("Native frames: ")) {
            state = State.STACKTRACE;
          }
          break;
        case STACKTRACE:
          nextThreadSectionState = nextThreadSectionState(line, previousLineBlank);
          if (nextThreadSectionState != null) {
            state = nextThreadSectionState;
          } else if (line.startsWith("siginfo:")) {
            // spotless:off
            // siginfo: si_signo: 11 (SIGSEGV), si_code: 1 (SEGV_MAPERR), si_addr: 0x70
            // siginfo: si_signo: 11 (SIGSEGV), si_code: 0 (SI_USER), si_pid: 554848, si_uid: 1000
            // spotless:on
            final Matcher siginfoMatcher = SIGINFO_PARSER.matcher(line);
            if (siginfoMatcher.matches()) {
              Integer number = safelyParseInt(siginfoMatcher.group(1));
              String name = siginfoMatcher.group(2);
              Integer siCode = safelyParseInt(siginfoMatcher.group(3));
              String sigAction = siginfoMatcher.group(4);
              String address = siginfoMatcher.group(5);
              Integer siPid = safelyParseInt(siginfoMatcher.group(6));
              Integer siUid = safelyParseInt(siginfoMatcher.group(7));
              sigInfo = new SigInfo(number, name, siCode, sigAction, address, siPid, siUid);
            }
          } else {
            // Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
            final StackFrame frame = parseLine(line);
            if (frame != null) {
              frames.add(frame);
            }
          }
          break;
        case REGISTER_TO_MEMORY_MAPPING:
          nextThreadSectionState = nextThreadSectionState(line, previousLineBlank);
          if (nextThreadSectionState != null) {
            currentRegisterToMemoryMapping = "";
            state = nextThreadSectionState;
          } else if (!line.isEmpty()) {
            final Matcher m = REGISTER_TO_MEMORY_MAPPING_PARSER.matcher(line);
            if (m.lookingAt()) {
              currentRegisterToMemoryMapping = m.group(1);
              registerToMemoryMapping.put(currentRegisterToMemoryMapping, line.substring(m.end()));
            } else if (!currentRegisterToMemoryMapping.isEmpty()) {
              registerToMemoryMapping.computeIfPresent(
                  currentRegisterToMemoryMapping, (key, value) -> value + "\n" + line);
            }
          }
          break;
        case REGISTERS:
          nextThreadSectionState = nextThreadSectionState(line, previousLineBlank);
          if (nextThreadSectionState != null) {
            state = nextThreadSectionState;
          } else if (!line.isEmpty() && !REGISTER_LINE_START.matcher(line).find()) {
            // non-empty line that does not start with a register entry signals end of section
            state = State.STACKTRACE;
          } else {
            final Matcher m = REGISTER_ENTRY_PARSER.matcher(line);
            while (m.find()) {
              registers.put(m.group(1), m.group(2));
            }
          }
          break;
        case PROCESS:
          if (runtimeArgs == null && line.startsWith("VM Arguments:")) {
            state = State.VM_ARGUMENTS;
          } else if (line.startsWith("Dynamic libraries:")) {
            state = State.DYNAMIC_LIBRARIES;
          } else if (line.contains("S Y S T E M")) {
            state = State.SYSTEM;
          } else if (line.equals("END.")) {
            state = State.DONE;
          }
          break;
        case VM_ARGUMENTS:
          if (line.isEmpty()) {
            state = State.PROCESS;
          } else if (runtimeArgs == null && line.startsWith(HOTSPOT_JVM_ARGS_PREFIX)) {
            runtimeArgs = parseHotspotJvmArgs(line);
          }
          break;
        case DYNAMIC_LIBRARIES:
          if (line.isEmpty()) {
            state = State.PROCESS;
          } else {
            if (dynamicLibraryKey == null) {
              dynamicLibraryKey = detectDynamicLibrariesKey(line);
              dynamicLibraryLines = new ArrayList<>();
            }
            final Matcher matcher = DYNAMIC_LIBS_PATH_PARSER.matcher(line);
            if (matcher.matches()) {
              final String pathString = matcher.group(1);
              if (pathString != null && !pathString.isEmpty()) {
                dynamicLibraryLines.add(line);
                try {
                  final Path path = Paths.get(pathString);
                  buildIdCollector.resolveBuildId(path);
                } catch (InvalidPathException ignored) {
                }
              }
            }
          }
          break;
        case SYSTEM:
          if (line.equals("END.")) {
            state = State.DONE;
          } else if (datetime == null && datetimeRaw == null && line.startsWith("time: ")) {
            // JDK 8 fallback: no SUMMARY section, time is split across two lines here
            datetimeRaw = line.substring(6).trim();
          } else if (datetime == null && datetimeRaw != null && line.startsWith("timezone: ")) {
            datetime = dateTimeToISO(datetimeRaw + " " + line.substring(10).trim());
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
      previousLineBlank = line.isEmpty();
    }

    // PROCESS and SYSTEM sections are late enough that all critical data is captured
    if (state != State.DONE && state != State.PROCESS && state != State.SYSTEM) {
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
                frame.frameType,
                buildInfo.buildId,
                buildInfo.buildIdType,
                buildInfo.fileType,
                frame.ip,
                frame.symbolAddress,
                frame.relativeAddress));
      } else {
        enrichedFrames.add(
            new StackFrame(
                normalizeFilename(frame.path),
                frame.line,
                frame.function,
                frame.frameType,
                null,
                null,
                null,
                frame.ip,
                frame.symbolAddress,
                frame.relativeAddress));
      }
    }

    ErrorData error =
        new ErrorData(
            kind, message, threadName, new StackTrace(enrichedFrames.toArray(new StackFrame[0])));
    // We can not really extract the full metadata and os info from the crash log
    // This code assumes the parser is run on the same machine as the crash happened
    Metadata metadata = new Metadata("dd-trace-java", VersionInfo.VERSION, "java", null);
    Integer parsedPid = safelyParseInt(pid);
    ProcInfo procInfo = parsedPid != null ? new ProcInfo(parsedPid) : null;
    Map<String, String> resolvedMapping = null;
    if (!registerToMemoryMapping.isEmpty()) {
      registerToMemoryMapping.replaceAll((k, v) -> RedactUtils.redactRegisterToMemoryMapping(v));
      resolvedMapping = registerToMemoryMapping;
    }
    Experimental experimental =
        !registers.isEmpty()
                || resolvedMapping != null
                || (runtimeArgs != null && !runtimeArgs.isEmpty())
            ? new Experimental(registers, resolvedMapping, runtimeArgs)
            : null;
    DynamicLibs files =
        (dynamicLibraryLines != null && !dynamicLibraryLines.isEmpty())
            ? new DynamicLibs(dynamicLibraryKey, dynamicLibraryLines)
            : null;
    return new CrashLog(
        uuid,
        incomplete,
        datetime,
        error,
        metadata,
        OSInfo.current(),
        procInfo,
        sigInfo,
        "1.0",
        experimental,
        files);
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

  private static State nextThreadSectionState(String line, boolean previousLineBlank) {
    if (line.startsWith("Register to memory mapping:")) {
      return State.REGISTER_TO_MEMORY_MAPPING;
    }
    if (line.startsWith("Registers:")) {
      return State.REGISTERS;
    }
    if (line.startsWith("siginfo:")) {
      return null;
    }
    if (line.contains("P R O C E S S")) {
      return State.PROCESS;
    }
    if (previousLineBlank && SUBSECTION_TITLE.matcher(line).matches()) {
      return State.STACKTRACE;
    }
    return null;
  }

  /**
   * Detects whether the Dynamic libraries section comes from Linux {@code /proc/self/maps} (address
   * range format {@code addr-addr perms ...}) or from the BSD/macOS dyld callback (format {@code
   * 0xaddr\tpath}). Returns the appropriate map key.
   */
  // The "Dynamic libraries:" section is written by os::print_dll_info(), whose implementation
  // differs by platform:
  //
  // Linux
  // -----
  // This reads `/proc/{tid}/maps` verbatim via _print_ascii_file(), producing the usual
  // `/proc/self/maps` format:
  //
  //   "addr-addr perms offset dev:inode [path]"
  //
  // Mainline:
  // https://github.com/openjdk/jdk/blob/783f8f1adc4ea3ef7fd4c5ca5473aad76dfc7ed1/src/hotspot/os/linux/os_linux.cpp#L2086-L2099
  //
  // BSD/macOS
  // ---------
  // This relies on `_dyld_image_count()`/`_dyld_get_image_name()` (on macOS) or
  // `dlinfo(RTLD_DI_LINKMAP)` (on FreeBSD/OpenBSD) via a callback, producing a simpler format:
  //
  //   "0xaddr\tpath"
  //
  // which lacks much of the information found in Linux's `/proc/self/maps`.
  // Mainline:
  // https://github.com/openjdk/jdk/blob/783f8f1adc4ea3ef7fd4c5ca5473aad76dfc7ed1/src/hotspot/os/bsd/os_bsd.cpp#L1382-L1387
  static String detectDynamicLibrariesKey(String firstLine) {
    int dash = firstLine.indexOf('-');
    int space = firstLine.indexOf(' ');
    return (dash > 0 && space > 0 && dash < space) ? "/proc/self/maps" : "dynamic_libraries";
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
