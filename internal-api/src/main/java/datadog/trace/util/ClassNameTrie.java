package datadog.trace.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable space-efficient trie that captures a mapping of package/class names to numbers.
 *
 * <p>Each node of the trie is represented as a series of {@code char}s using this layout:
 *
 * <pre>
 * +--------------------------------------+
 * | number of branches                   |
 * +--------------------------------------+--------------------------------------+----
 * | char for branch 0                    | char for branch 1                    | ...
 * +--------------------------------------+--------------------------------------+----
 * | segment-length/leaf/bud for branch 0 | segment-length/leaf/bud for branch 1 | ...
 * +--------------------------------------+--------------------------------------+----
 * | offset to jump to branch 1           | offset to jump to branch 2           | ...
 * +--------------------------------------+--------------------------------------+----
 * </pre>
 *
 * Each node is followed by its child nodes according to branch order, separated by the characters
 * expected for that segment of the trie. Segments that represent the end of a name with no further
 * branches are followed by a leaf character instead of a child node.
 *
 * <p>Leaves mark a definite end of the match, while buds mark a potential end that could continue
 * to a different result if there are more characters to match. A match is a success when either the
 * entire key is matched or the match is a glob.
 *
 * <p>The jump for branch 0 is assumed to be 0 and is always omitted, that is any continuation of
 * the trie for branch 0 immediately follows the current node. Long jumps that don't fit into a char
 * are replaced by an index into a long jump table.
 *
 * <p>For example this mapping:
 *
 * <pre>
 * 2 akka.stream.*
 * 0 akka.stream.impl.FanIn$SubInput
 * 0 akka.stream.impl.FanOut$SubstreamSubscription
 * 0 akka.stream.impl.fusing.ActorGraphInterpreter$*
 * 0 akka.stream.stage.GraphStageLogic$*
 * 0 akka.stream.stage.TimerGraphStageLogic$*
 * 2 ch.qos.logback.*
 * 0 ch.qos.logback.classic.Logger
 * 0 ch.qos.logback.classic.spi.LoggingEvent*
 * 0 ch.qos.logback.core.AsyncAppenderBase$Worker
 * </pre>
 *
 * is generated into a string trie with this structure: (formatted for readability)
 *
 * <pre>
 * | 2 | a | c | length (10) | length (13) | jump (150)
 * kka.stream | 1 | . | bud+glob (2)
 *            | 2 | i | s | length (4) | length (5) | jump (83)
 *            mpl. | 2 | F | f | length (2) | length (28) | jump (44)
 *                 an | 2 | I | O | length (10) | length (24) | jump (11)
 *                    n$SubInput | leaf (0)
 *                    ut$SubstreamSubscription | leaf (0)
 *                 using.ActorGraphInterpreter$ | leaf+glob (0)
 *            tage. | 2 | G | T | length (15) | length (20) | jump (16)
 *                  raphStageLogic$ | leaf+glob (0)
 *                  imerGraphStageLogic$ | leaf+glob (0)
 * h.qos.logback | 1 | . | bud+glob (2)
 *               | 1 | c | length (0)
 *               | 2 | l | o | length (6) | length (27) | jump (40)
 *               assic. | 2 | L | s | length (5) | length (14) | jump (6)
 *                      ogger | leaf (0)
 *                      pi.LoggingEvent | leaf+glob (0)
 *               re.AsyncAppenderBase$Worker | leaf(0)
 * </pre>
 */
public final class ClassNameTrie {

  /** Marks a leaf in the trie, where the rest of the bits are the index to be returned. */
  private static final char LEAF_MARKER = 0x8000;

  /** Marks a 'bud' in the trie; the same as a leaf except the trie continues beneath it. */
  private static final char BUD_MARKER = 0x4000;

  /** Marks a glob in the trie, where a match succeeds even if the key has extra characters. */
  private static final char GLOB_MARKER = 0x2000;

  /** Maximum value that can be held in a single node of the trie. */
  private static final char MAX_NODE_VALUE = 0x1FFF;

  /** Marks a long jump that was replaced by an index into the long jump table. */
  private static final char LONG_JUMP_MARKER = 0x8000;

  private static final int[] NO_LONG_JUMPS = {};

  /** The compressed trie. */
  private final char[] trieData;

  /** Long jump offsets. */
  private final int[] longJumps;

  public int apply(String key) {
    char[] data = trieData;
    int keyLength = key.length();
    int keyIndex = 0;
    int dataIndex = 0;
    int result = -1;

    while (keyIndex < keyLength) {
      char c = key.charAt(keyIndex++);
      char branchCount = data[dataIndex++];

      // trie is ordered, so we can use binary search to pick the right branch
      int branchIndex =
          Arrays.binarySearch(data, dataIndex, dataIndex + branchCount, c == '/' ? '.' : c);

      if (branchIndex < 0) {
        return result; // key doesn't match against any future branches
      }

      int valueIndex = branchIndex + branchCount;
      char value = data[valueIndex];
      int segmentLength = 0;

      if ((value & (LEAF_MARKER | BUD_MARKER)) != 0) {
        // update result if we've matched the key, or we're at a glob
        if (keyIndex == keyLength || (value & GLOB_MARKER) != 0) {
          result = value & MAX_NODE_VALUE;
        }
        // stop if there's no more characters left in the key, or we've reached a leaf
        if (keyIndex == keyLength || (value & LEAF_MARKER) != 0) {
          return result;
        }
      } else {
        segmentLength = value; // value is the length of the segment before the next node
      }

      // move on to the segment/node for the picked branch...
      if (branchIndex > dataIndex) {
        int jumpIndex = valueIndex + branchCount - 1;
        int nextJump = data[jumpIndex];
        if ((nextJump & LONG_JUMP_MARKER) != 0) {
          nextJump = longJumps[nextJump & ~LONG_JUMP_MARKER];
        }
        dataIndex += nextJump;
      }

      // ...always include moving past the current node
      dataIndex += (branchCount * 3) - 1;

      // attempt to match any inline segment that precedes the next node
      if (segmentLength > 0) {
        if (keyLength - keyIndex < segmentLength) {
          return result; // not enough characters left in the key
        }
        int segmentEnd = dataIndex + segmentLength;
        while (dataIndex < segmentEnd) {
          c = key.charAt(keyIndex++);
          if ((c == '/' ? '.' : c) != data[dataIndex++]) {
            return result; // segment doesn't match
          }
        }
        // peek ahead - it will either be a node or a leaf
        value = data[dataIndex];
        if ((value & LEAF_MARKER) != 0) {
          // update result if we've matched the key, or we're at a glob
          if (keyIndex == keyLength || (value & GLOB_MARKER) != 0) {
            result = value & MAX_NODE_VALUE;
          }
          return result; // no more characters left to match in the trie
        }
      }
    }

    return result; // no more characters left to match in the key
  }

  public static ClassNameTrie create(String trieData) {
    return create(trieData, NO_LONG_JUMPS);
  }

  public static ClassNameTrie create(String[] trieData) {
    return create(trieData, NO_LONG_JUMPS);
  }

  public static ClassNameTrie create(String trieData, int[] longJumps) {
    return new ClassNameTrie(trieData.toCharArray(), longJumps);
  }

  public static ClassNameTrie create(String[] trieData, int[] longJumps) {
    int dataLength = 0;
    for (String chunk : trieData) {
      dataLength += chunk.length();
    }
    char[] data = new char[dataLength];
    int dataIndex = 0;
    for (String chunk : trieData) {
      int chunkLength = chunk.length();
      System.arraycopy(chunk.toCharArray(), 0, data, dataIndex, chunkLength);
      dataIndex += chunkLength;
    }
    return new ClassNameTrie(data, longJumps);
  }

  private ClassNameTrie(char[] trieData, int[] longJumps) {
    this.trieData = trieData;
    this.longJumps = longJumps;
  }

  /** Builds an in-memory trie that represents a mapping of {class-name} to {number}. */
  public static class Builder {
    private static final Pattern MAPPING_LINE = Pattern.compile("^\\s*(?:([0-9]+)\\s+)?([^\\s#]+)");

    private final List<String> keys = new ArrayList<>();
    private final StringBuilder values = new StringBuilder();

    private final StringBuilder buf = new StringBuilder();
    private int[] longJumps = {};

    public void put(String className, int number) {
      if (number < 0) {
        throw new IllegalArgumentException("Number for " + className + " is negative: " + number);
      }
      if (number > MAX_NODE_VALUE) {
        throw new IllegalArgumentException("Number for " + className + " is too big: " + number);
      }
      String key;
      char value;
      // package/class-names ending in '*' are marked as globs
      if (className.charAt(className.length() - 1) == '*') {
        key = className.substring(0, className.length() - 1);
        value = (char) (number | GLOB_MARKER);
      } else {
        key = className;
        value = (char) number;
      }
      // invert binarySearch result to get insertion point that maintains the natural order
      int index = ~Collections.binarySearch(keys, key);
      if (index >= 0) {
        keys.add(index, key);
        values.insert(index, value);
      } // else ignore class names that already exist in the trie
    }

    /** Reads a class-name mapping file into the current builder */
    public void readClassNameMapping(Path triePath) throws IOException {
      for (String l : Files.readAllLines(triePath, StandardCharsets.UTF_8)) {
        Matcher m = MAPPING_LINE.matcher(l);
        if (m.find()) {
          put(m.group(2), m.group(1) != null ? Integer.parseInt(m.group(1)) : 1);
        }
      }
    }

    public boolean isEmpty() {
      return keys.isEmpty();
    }

    public ClassNameTrie buildTrie() {
      buildSubTrie(0, 0, keys.size());
      char[] data = new char[buf.length()];
      buf.getChars(0, data.length, data, 0);
      return new ClassNameTrie(data, longJumps);
    }

    /** Recursively builds a trie for a slice of rows at a particular column. */
    private void buildSubTrie(int column, int row, int rowLimit) {
      int trieStart = buf.length();

      int prevRow = row;
      int branchCount = 0;
      int nextJump = 0;

      while (prevRow < rowLimit) {
        String key = keys.get(prevRow);
        int columnLimit = key.length();

        char pivot = key.charAt(column);

        // find the row that marks the start of the next branch, and the end of this one
        int nextRow = nextPivotRow(pivot, column, prevRow, rowLimit);

        // find the column along this branch that marks the next decision point/pivot
        int nextColumn = nextPivotColumn(column, prevRow, nextRow);

        // adjust pivot point if it would involve adding a bud spanning more than one column
        if (nextColumn == columnLimit && nextColumn - column > 1 && nextRow - prevRow > 1) {
          // move it back so this becomes a jump branch followed immediately by sub-trie bud
          nextColumn--;
        }

        // record the character for this branch
        int branchIndex = trieStart + branchCount;
        buf.insert(branchIndex, pivot);

        int valueIndex = branchIndex + 1 + branchCount;

        // any sub tries will start after the value (to be inserted)
        int subTrieStart = buf.length() + 1;

        if (nextColumn < columnLimit) {
          // same row, record the segment before processing rest of the row as sub trie
          if (nextColumn - column > 1) {
            String segment = key.substring(column + 1, nextColumn);
            buf.insert(valueIndex, (char) segment.length());
            buf.append(segment);
          } else {
            buf.insert(valueIndex, (char) 0);
          }
          buildSubTrie(nextColumn, prevRow, nextRow);
        } else {
          // build next row as sub trie, this tells us if current value is a leaf or bud
          buildSubTrie(nextColumn, prevRow + 1, nextRow);
          if (subTrieStart > buf.length()) {
            // no more branches so record last segment followed by a leaf
            if (nextColumn - column > 1) {
              String segment = key.substring(column + 1, nextColumn);
              buf.insert(valueIndex, (char) segment.length());
              buf.append(segment);
              buf.append((char) (values.charAt(prevRow) | LEAF_MARKER));
            } else {
              buf.insert(valueIndex, (char) (values.charAt(prevRow) | LEAF_MARKER));
            }
          } else {
            // we added more branches, so record value and mark it as a bud
            buf.insert(valueIndex, (char) (values.charAt(prevRow) | BUD_MARKER));
          }
        }

        if (nextRow < rowLimit) {
          // child sub-tries have been added, so can now calculate jump to next branch
          int jumpIndex = valueIndex + 1 + branchCount;
          nextJump += buf.length() - subTrieStart;
          if (nextJump >= LONG_JUMP_MARKER) {
            // jump too big for a single char, record its long jump index instead
            int longJumpIndex = longJumps.length;
            longJumps = Arrays.copyOf(longJumps, longJumpIndex + 1);
            longJumps[longJumpIndex] = nextJump;
            buf.insert(jumpIndex, (char) (LONG_JUMP_MARKER | longJumpIndex));
          } else {
            buf.insert(jumpIndex, (char) nextJump);
          }
        }

        prevRow = nextRow;
        branchCount++;
      }

      if (branchCount > 0) {
        buf.insert(trieStart, (char) branchCount);
      }
    }

    /**
     * Finds the next row that has a different character in the selected column to the given one, or
     * is too short to include the column. This determines the span of rows that fall under the
     * given character in the trie.
     *
     * <p>Returns the row just after the end of the range if all rows have the same character.
     */
    private int nextPivotRow(char pivot, int column, int row, int rowLimit) {
      for (int r = row + 1; r < rowLimit; r++) {
        String key = keys.get(r);
        if (key.length() <= column || key.charAt(column) != pivot) {
          return r;
        }
      }
      return rowLimit;
    }

    /**
     * Finds the next column in the current row whose character differs in at least one other row.
     * This helps identify the longest common prefix from the current pivot point to the next one.
     *
     * <p>Returns the column just after the end of the current row if all rows are identical.
     */
    private int nextPivotColumn(int column, int row, int rowLimit) {
      String key = keys.get(row);
      int columnLimit = key.length();
      for (int c = column + 1; c < columnLimit; c++) {
        if (nextPivotRow(key.charAt(c), c, row, rowLimit) < rowLimit) {
          return c;
        }
      }
      return columnLimit;
    }
  }

  /** Generates Java source for a trie described as a series of "{number} {class-name}" lines. */
  public static class JavaGenerator {
    public static void main(String[] args) throws IOException {
      if (args.length < 2) {
        throw new IllegalArgumentException("Expected: trie-dir java-dir [file.trie ...]");
      }
      Path trieDir = Paths.get(args[0]).toAbsolutePath().normalize();
      if (!Files.isDirectory(trieDir)) {
        throw new IllegalArgumentException("Bad trie directory: " + trieDir);
      }
      Path javaDir = Paths.get(args[1]).toAbsolutePath().normalize();
      if (!Files.isDirectory(javaDir)) {
        throw new IllegalArgumentException("Bad java directory: " + javaDir);
      }
      for (int i = 2; i < args.length; i++) {
        Path triePath = trieDir.resolve(args[i]).normalize();
        String className = toClassName(triePath.getFileName().toString());
        Path pkgPath = trieDir.relativize(triePath.getParent());
        String pkgName = pkgPath.toString().replace(File.separatorChar, '.');
        Path javaPath = javaDir.resolve(pkgPath).resolve(className + ".java");
        generateJavaFile(triePath, javaPath, pkgName, className);
      }
    }

    /** Converts snake-case trie names to camel-case Java class names. */
    private static String toClassName(String trieName) {
      StringBuilder className = new StringBuilder();
      boolean upperNext = true;
      for (int i = 0; i < trieName.length(); i++) {
        char c = trieName.charAt(i);
        if (c == '_' | c == '.') {
          upperNext = true;
        } else {
          className.append(upperNext ? Character.toUpperCase(c) : c);
          upperNext = false;
        }
      }
      return className.toString();
    }

    /** Reads a trie file and writes out the equivalent Java file. */
    private static void generateJavaFile(
        Path triePath, Path javaPath, String pkgName, String className) throws IOException {
      ClassNameTrie.Builder builder = new ClassNameTrie.Builder();
      builder.readClassNameMapping(triePath);
      List<String> lines = new ArrayList<>();
      if (!pkgName.isEmpty()) {
        lines.add("package " + pkgName + ';');
      }
      lines.add("");
      lines.add("import datadog.trace.util.ClassNameTrie;");
      lines.add("");
      lines.add("// Generated from '" + triePath.getFileName() + "' - DO NOT EDIT!");
      lines.add("public final class " + className + " {");
      lines.add("  public static int apply(String key) {");
      lines.add("    return TRIE.apply(key);");
      lines.add("  }");
      lines.add("");
      generateJavaTrie(lines, "", builder.buildTrie());
      lines.add("  private " + className + "() {}");
      lines.add("}");
      Files.write(javaPath, lines, StandardCharsets.UTF_8);
    }

    /** Writes the Java form of the trie as a series of lines. */
    public static void generateJavaTrie(List<String> lines, String prefix, ClassNameTrie trie) {
      boolean hasLongJumps = trie.longJumps.length > 0;
      int firstLineNumber = lines.size();
      int chunk = 1;
      lines.add("  private static final String " + prefix + "TRIE_DATA_" + chunk + " =");
      int chunkSize = 0;
      StringBuilder buf = new StringBuilder();
      buf.append("      \"");
      for (char c : trie.trieData) {
        if (++chunkSize > 10_000) {
          chunk++;
          chunkSize = 0;
          lines.add(buf + "\";");
          lines.add("  private static final String " + prefix + "TRIE_DATA_" + chunk + " =");
          buf.setLength(0);
          buf.append("      \"");
        } else if (buf.length() > 120) {
          lines.add(buf + "\"");
          buf.setLength(0);
          buf.append("          + \"");
        }
        if (c <= 0x00FF) {
          buf.append(String.format("\\%03o", (int) c));
        } else {
          buf.append(String.format("\\u%04x", (int) c));
        }
      }
      lines.add(buf + "\";");
      lines.add("");
      if (chunk > 1) {
        lines.add("  private static final String[] " + prefix + "TRIE_DATA = {");
        for (int n = 1; n < chunk; n++) {
          lines.add("    TRIE_DATA_" + n + ',');
        }
        lines.add("  };");
        lines.add("");
      } else {
        // only one chunk, simplify the first chunk name to match the constructor call
        lines.set(firstLineNumber, "  private static final String " + prefix + "TRIE_DATA =");
      }
      if (hasLongJumps) {
        lines.add("  private static final int[] " + prefix + "LONG_JUMPS = {");
        buf.setLength(0);
        buf.append("   ");
        for (int j : trie.longJumps) {
          if (buf.length() > 90) {
            lines.add(buf.toString());
            buf.setLength(0);
            buf.append("   ");
          }
          buf.append(' ').append(String.format("0x%06X", j)).append(',');
        }
        lines.add(buf.toString());
        lines.add("  };");
        lines.add("");
      }
      if (hasLongJumps) {
        lines.add(
            "  private static final ClassNameTrie "
                + prefix
                + "TRIE = ClassNameTrie.create("
                + prefix
                + "TRIE_DATA, "
                + prefix
                + "LONG_JUMPS);");
      } else {
        lines.add(
            "  private static final ClassNameTrie "
                + prefix
                + "TRIE = ClassNameTrie.create("
                + prefix
                + "TRIE_DATA);");
      }
      lines.add("");
    }
  }
}
