package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.civisibility.coverage.SourceMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Reads a JSR-45 source map (SMAP), the text of a class's SourceDebugExtension attribute. Only the
 * default stratum is read, and lines that do not parse are skipped.
 */
final class SmapParser {

  private static final int OTHER_SECTION = 0;
  private static final int FILE_SECTION = 1;
  private static final int LINE_SECTION = 2;

  private final List<String> files = new ArrayList<>();
  private final Map<Integer, Integer> fileIndexById = new HashMap<>();
  private int[] ranges = new int[40];
  private int rangesLength;

  private SmapParser() {}

  /**
   * @param smap the SourceDebugExtension of a class
   * @param packageDirectory the package of the class as a directory, such as {@code com/example/}
   * @return the map, or {@code null} if the text is not a source map or maps lines onto themselves
   */
  @Nullable
  static SourceMap parse(@Nullable String smap, String packageDirectory) {
    if (smap == null || !smap.startsWith("SMAP")) {
      return null;
    }
    String[] lines = lines(smap);
    if (lines.length < 3) {
      return null;
    }
    String stratum = defaultStratum(lines);
    if (stratum == null) {
      return null;
    }
    return new SmapParser().read(lines, stratum, packageDirectory);
  }

  /** The lines of a text split at {@code \n} or {@code \r\n}, without the trailing empty ones. */
  static String[] lines(String text) {
    int newline = text.indexOf('\n');
    if (newline < 0) {
      return new String[] {text};
    }
    List<String> lines = new ArrayList<>();
    int start = 0;
    while (newline >= 0) {
      int end = newline > start && text.charAt(newline - 1) == '\r' ? newline - 1 : newline;
      lines.add(text.substring(start, end));
      start = newline + 1;
      newline = text.indexOf('\n', start);
    }
    lines.add(text.substring(start));
    int size = lines.size();
    while (size > 0 && lines.get(size - 1).isEmpty()) {
      size--;
    }
    return lines.subList(0, size).toArray(new String[0]);
  }

  /** The stratum the header names, or the first one when the header names none of the map's. */
  @Nullable
  private static String defaultStratum(String[] lines) {
    String named = lines[2].trim();
    if ("Java".equals(named)) {
      return null; // the class's own line numbers
    }
    String first = null;
    for (int i = 3; i < lines.length; i++) {
      if (lines[i].startsWith("*S ")) {
        String stratum = lines[i].substring(3).trim();
        if (stratum.equals(named)) {
          return named;
        }
        if (first == null) {
          first = stratum;
        }
      }
    }
    return first;
  }

  private SourceMap read(String[] lines, String stratum, String packageDirectory) {
    boolean inStratum = false;
    int section = OTHER_SECTION;
    int embeddedDepth = 0;
    int pendingFileId = -1;
    String pendingFileName = null;
    int lineFileId = 0;
    for (int i = 3; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.startsWith("*")) {
        if (pendingFileName != null) {
          addFile(pendingFileId, packageDirectory + pendingFileName);
          pendingFileName = null;
        }
        if (line.equals("*O")) {
          embeddedDepth++;
        } else if (line.equals("*C")) {
          embeddedDepth--;
        } else if (embeddedDepth > 0) {
          continue;
        } else if (line.startsWith("*S ")) {
          if (inStratum) {
            break;
          }
          inStratum = line.substring(3).trim().equals(stratum);
          section = OTHER_SECTION;
        } else if (line.equals("*F")) {
          section = FILE_SECTION;
        } else if (line.equals("*L")) {
          section = LINE_SECTION;
        } else {
          section = OTHER_SECTION;
        }
        continue;
      }
      if (!inStratum || embeddedDepth > 0) {
        continue;
      }
      if (section == FILE_SECTION) {
        if (pendingFileName != null) {
          // the line after "+ id name" is the file's path; Kotlin writes the internal class name
          addFile(pendingFileId, directory(line) + pendingFileName);
          pendingFileName = null;
        } else if (line.startsWith("+ ")) {
          int space = line.indexOf(' ', 2);
          if (space > 2) {
            pendingFileId = number(line, 2, space);
            pendingFileName = line.substring(space + 1);
          }
        } else {
          int space = line.indexOf(' ');
          if (space > 0) {
            addFile(number(line, 0, space), packageDirectory + line.substring(space + 1));
          }
        }
      } else if (section == LINE_SECTION) {
        lineFileId = addRange(line, lineFileId);
      }
    }
    if (pendingFileName != null) {
      addFile(pendingFileId, packageDirectory + pendingFileName);
    }
    return new SourceMap(files.toArray(new String[0]), Arrays.copyOf(ranges, rangesLength));
  }

  /**
   * The package of a class as a directory, such as {@code com/example/} for {@code
   * com/example/Foo}.
   */
  static String packageDirectory(String className) {
    return className.substring(0, className.lastIndexOf('/') + 1);
  }

  private static String directory(String path) {
    int start = path.startsWith("/") ? 1 : 0;
    int slash = path.lastIndexOf('/');
    return slash < start ? "" : path.substring(start, slash + 1);
  }

  private void addFile(int fileId, String file) {
    if (fileId >= 0) {
      fileIndexById.put(fileId, files.size());
      files.add(file);
    }
  }

  /**
   * Adds the range of a line info, {@code start[#fileId][,repeat]:outputStart[,increment]}.
   *
   * @return the file id for the next line info, which can omit it
   */
  private int addRange(String line, int lineFileId) {
    int colon = line.indexOf(':');
    if (colon < 0) {
      return lineFileId;
    }
    int hash = line.indexOf('#');
    int inputComma = line.indexOf(',');
    if (inputComma > colon) {
      inputComma = -1;
    }
    int inputEnd = hash >= 0 && hash < colon ? hash : inputComma >= 0 ? inputComma : colon;
    int inputStart = number(line, 0, inputEnd);
    int fileId = lineFileId;
    if (hash >= 0 && hash < colon) {
      fileId = number(line, hash + 1, inputComma >= 0 ? inputComma : colon);
    }
    int repeat = inputComma >= 0 ? number(line, inputComma + 1, colon) : 1;
    int outputComma = line.indexOf(',', colon);
    int outputStart = number(line, colon + 1, outputComma >= 0 ? outputComma : line.length());
    int increment = outputComma >= 0 ? number(line, outputComma + 1, line.length()) : 1;
    if (inputStart < 0 || fileId < 0 || repeat < 0 || outputStart < 0 || increment < 0) {
      return lineFileId;
    }
    long outputEnd = outputStart + (long) repeat * increment - 1;
    if (repeat > 0 && increment > 0 && outputEnd <= Integer.MAX_VALUE) {
      Integer fileIndex = fileIndexById.get(fileId);
      if (rangesLength + 5 > ranges.length) {
        ranges = Arrays.copyOf(ranges, ranges.length * 2);
      }
      ranges[rangesLength++] = outputStart;
      ranges[rangesLength++] = (int) outputEnd;
      ranges[rangesLength++] = inputStart;
      ranges[rangesLength++] = increment;
      ranges[rangesLength++] = fileIndex != null ? fileIndex : -1;
    }
    return fileId;
  }

  /**
   * @return the decimal number in {@code text} from {@code start} to {@code end}, or -1 if there is
   *     none
   */
  private static int number(String text, int start, int end) {
    if (start >= end || end > text.length()) {
      return -1;
    }
    long value = 0;
    for (int i = start; i < end; i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return -1;
      }
      value = value * 10 + (c - '0');
      if (value > Integer.MAX_VALUE) {
        return -1;
      }
    }
    return (int) value;
  }
}
