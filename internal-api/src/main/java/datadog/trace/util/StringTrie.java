package datadog.trace.util;

import datadog.trace.api.ToIntFunction;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Space-efficient string-based trie. */
public class StringTrie implements ToIntFunction<String> {

  /** Marks a leaf in the trie, where the rest of the bits are the index to be returned. */
  private static final char LEAF_MARKER = 0x8000;

  /** Marks a 'bud' in the tree; the same as a leaf except the trie continues beneath it. */
  private static final char BUD_MARKER = 0x4000;

  /** Maximum number of rows that can be indexed by a single trie. */
  private static final int MAX_ROWS_PER_TRIE = 0x4000;

  /** Maximum value that can be held in a single node of the trie. */
  private static final int MAX_NODE_VALUE = 0x3FFF;

  /** The compressed trie. */
  private final char[] trieData;

  /** The trie segments. */
  private final String[] trieSegments;

  @Override
  public int applyAsInt(String key) {
    int keyLength = key.length();
    int keyIndex = 0;
    char[] data = trieData;
    String[] segments = trieSegments;
    int dataIndex = 0;
    int result = -1;

    char branchCount = data[dataIndex++];

    while (keyIndex < keyLength) {
      char c = key.charAt(keyIndex++);

      // trie is ordered, so we can use binary search to pick the right branch
      int branchIndex = Arrays.binarySearch(data, dataIndex, dataIndex + branchCount, c);

      if (branchIndex < 0) {
        break; // no match from this point onwards
      }

      int valueIndex = branchIndex + branchCount;
      char value = data[valueIndex];
      if ((value & LEAF_MARKER) != 0) {
        if (keyIndex == keyLength || c == '.' || c == '$') {
          result = value & ~LEAF_MARKER;
        }
        break;
      }

      // 'buds' are just like leaves unless the key still has characters left
      if ((value & BUD_MARKER) != 0) {
        result = value & ~BUD_MARKER;
        if (keyIndex == keyLength) {
          break;
        }
      } else if (value > 0) {
        // check rest of segment matches before moving key to next decision point
        String segment = segments[value - 1];
        if (!key.regionMatches(keyIndex, segment, 0, segment.length())) {
          break;
        }
        keyIndex += segment.length();
      }

      // move the data to the appropriate branch...
      if (branchIndex > dataIndex) {
        int jumpIndex = valueIndex + branchCount - 1;
        dataIndex += data[jumpIndex];
      }

      // ...always include moving past the current node
      dataIndex += (branchCount * 3) - 1;

      // handle special case when next branching point is just a leaf after a segment
      branchCount = data[dataIndex++];
      if ((branchCount & LEAF_MARKER) != 0) {
        if (keyIndex == keyLength || (c = key.charAt(keyIndex - 1)) == '.' || c == '$') {
          result = branchCount & ~LEAF_MARKER;
        }
        break;
      }
    }

    return result;
  }

  protected StringTrie(char[] data, String[] segments) {
    this.trieData = data;
    this.trieSegments = segments;
  }

  /** Builds a new trie for the given string-to-int mapping. */
  public static ToIntFunction<String> buildTrie(SortedMap<String, Integer> mapping) {
    return new Builder(mapping).buildTrie();
  }

  private static class Builder {
    private final StringBuilder buf = new StringBuilder();
    private final List<String> segments = new ArrayList<>();

    private final String[] keys;
    private final char[] values;

    Builder(SortedMap<String, Integer> mapping) {
      int numEntries = mapping.size();
      if (numEntries > MAX_ROWS_PER_TRIE) {
        throw new IllegalArgumentException(
            "Too many entries: " + numEntries + " > " + MAX_ROWS_PER_TRIE);
      }
      this.keys = new String[numEntries];
      this.values = new char[numEntries];
      int i = 0;
      for (Map.Entry<String, Integer> entry : mapping.entrySet()) {
        this.keys[i] = entry.getKey();
        int value = entry.getValue();
        if (value < 0) {
          throw new IllegalArgumentException("Value is too small: " + value + " < 0");
        }
        if (value > MAX_NODE_VALUE) {
          throw new IllegalArgumentException("Value is too big: " + value + " > " + MAX_NODE_VALUE);
        }
        this.values[i] = (char) value;
        i++;
      }
    }

    ToIntFunction<String> buildTrie() {
      buildSubTrie(0, 0, keys.length);
      char[] data = new char[buf.length()];
      buf.getChars(0, data.length, data, 0);
      return new StringTrie(data, segments.toArray(new String[0]));
    }

    /** Recursively builds a trie for a slice of rows at a particular column. */
    private void buildSubTrie(int column, int row, int rowLimit) {
      int trieStart = buf.length();

      int prevRow = row;
      int branchCount = 0;
      int nextJump = 0;

      while (prevRow < rowLimit) {
        String key = keys[prevRow];
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
          // same row, record this key segment and process rest of the row as sub trie
          buf.insert(valueIndex, recordSegment(key, column, nextColumn));
          buildSubTrie(nextColumn, prevRow, nextRow);
        } else {
          // build next row as sub trie, this tells us if current value is a leaf or bud
          buildSubTrie(nextColumn, prevRow + 1, nextRow);
          if (subTrieStart == buf.length()) {
            // no more branches, so record segment leading up to the leaf...
            buf.insert(valueIndex, recordSegment(key, column, nextColumn));
            // ...and replace zero branch count with value marked as a leaf
            buf.setCharAt(subTrieStart, (char) (values[prevRow] | LEAF_MARKER));
          } else {
            // we added more branches, so record value and mark it as a bud
            buf.insert(valueIndex, (char) (values[prevRow] | BUD_MARKER));
          }
        }

        if (nextRow < rowLimit) {
          // child sub-tries have been added, so can now calculate jump to next branch
          int jumpIndex = valueIndex + 1 + branchCount;
          nextJump += buf.length() - subTrieStart;
          buf.insert(jumpIndex, (char) nextJump);
        }

        prevRow = nextRow;
        branchCount++;
      }

      buf.insert(trieStart, (char) branchCount);
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
        String key = keys[r];
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
      String key = keys[row];
      int columnLimit = key.length();
      for (int c = column + 1; c < columnLimit; c++) {
        if (nextPivotRow(key.charAt(c), c, row, rowLimit) < rowLimit) {
          return c;
        }
      }
      return columnLimit;
    }

    /**
     * Adds the current segment to the segment table, returning its position (starting from one).
     */
    private char recordSegment(String key, int column, int nextColumn) {
      if (nextColumn - column > 1) {
        // ignore the first character as we already matched that in the branch
        String segment = key.substring(column + 1, nextColumn);
        int segmentPosition = 1 + segments.indexOf(segment);
        if (segmentPosition == 0) {
          segments.add(segment);
          segmentPosition = segments.size();
        }
        return (char) segmentPosition;
      }
      return 0; // segment doesn't contain anything after the branch character
    }
  }

  /**
   * Accepts trie files containing lines "{number} {text}" and generates their Java representation.
   */
  private static class Generator {
    private static final Pattern MAPPING_LINE = Pattern.compile("^\\s*([0-9]+)\\s+([^\\s#]+)");

    public static void main(String[] args) throws IOException {
      if (args.length < 3) {
        throw new IllegalArgumentException("Expected: input-folder output-folder [file.trie ...]");
      }
      Path inputFolder = Paths.get(args[0]);
      if (!inputFolder.toFile().isDirectory()) {
        throw new IllegalArgumentException("Bad input folder: " + inputFolder);
      }
      Path outputFolder = Paths.get(args[1]);
      if (!outputFolder.toFile().isDirectory()) {
        throw new IllegalArgumentException("Bad output folder: " + outputFolder);
      }
      for (int i = 2; i < args.length; i++) {
        Path triePath = Paths.get(args[i]);
        String trieName = triePath.getFileName().toString();
        if (trieName.endsWith(".trie")) {
          String className =
              Character.toUpperCase(trieName.charAt(0))
                  + trieName.substring(1, trieName.length() - 5)
                  + "Trie";
          Path pkgPath = inputFolder.relativize(triePath.getParent());
          String pkgName = pkgPath.toString().replace(File.separatorChar, '.');
          Path javaPath = outputFolder.resolve(pkgPath).resolve(className + ".java");
          generateJavaFile(triePath, javaPath, pkgName, className);
        }
      }
    }

    private static void generateJavaFile(
        Path triePath, Path javaPath, String pkgName, String className) throws IOException {
      SortedMap<String, Integer> mapping = new TreeMap<>();
      for (String l : Files.readAllLines(triePath, StandardCharsets.UTF_8)) {
        Matcher m = MAPPING_LINE.matcher(l);
        if (m.find()) {
          mapping.put(m.group(2), Integer.valueOf(m.group(1)));
        }
      }
      StringTrie trie = (StringTrie) buildTrie(mapping);
      List<String> lines = new ArrayList<>();
      lines.add("package " + pkgName + ';');
      lines.add("");
      lines.add("import datadog.trace.api.ToIntFunction;");
      lines.add("import datadog.trace.util.StringTrie;");
      lines.add("");
      lines.add("// Generated from '" + triePath.getFileName() + "' - DO NOT EDIT!");
      lines.add("public class " + className + " extends StringTrie {");
      lines.add("  private static final String TRIE_DATA =");
      StringBuilder buf = new StringBuilder();
      buf.append("      \"");
      for (char c : trie.trieData) {
        if (c <= 0x00FF) {
          buf.append(String.format("\\%03o", (int) c));
        } else {
          buf.append(String.format("\\u%04x", (int) c));
        }
        if (buf.length() > 110) {
          lines.add(buf.append('"').toString());
          buf.setLength(0);
          buf.append("          + \"");
        }
      }
      lines.add(buf.append("\";").toString());
      lines.add("  private static final String[] TRIE_SEGMENTS = {");
      for (String s : trie.trieSegments) {
        lines.add("    \"" + s + "\",");
      }
      lines.add("  };");
      lines.add("");
      lines.add("  public static final ToIntFunction<String> INSTANCE = new " + className + "();");
      lines.add("");
      lines.add("  private " + className + "() {");
      lines.add("    super(TRIE_DATA.toCharArray(), TRIE_SEGMENTS);");
      lines.add("  }");
      lines.add("}");
      Files.write(javaPath, lines, StandardCharsets.UTF_8);
    }
  }
}
