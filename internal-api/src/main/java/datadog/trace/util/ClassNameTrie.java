package datadog.trace.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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

  /** A branch has at most 3 control characters: key, value, and optional jump offset/id. */
  private static final int BRANCH_CONTROL_CHARS = 3;

  /** Constant to account for the fact that the last branch doesn't have a jump offset/id. */
  private static final int NO_END_JUMP = 1;

  private static final int FILE_MAGIC = 0xDD097213;

  /** The compressed trie. */
  private final char[] trieData;

  /** Long jump offsets. */
  private final int[] longJumps;

  public int apply(String key) {
    return apply(trieData, longJumps, key);
  }

  public int apply(String key, int fromIndex) {
    return apply(trieData, longJumps, key, fromIndex);
  }

  public static int apply(char[] data, int[] longJumps, String key) {
    return apply(data, longJumps, key, 0);
  }

  public static int apply(char[] data, int[] longJumps, String key, int fromIndex) {
    int keyLength = key.length();
    int keyIndex = fromIndex;
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
        int branchJump = data[valueIndex + branchCount - 1];
        if ((branchJump & LONG_JUMP_MARKER) != 0) {
          branchJump = longJumps[branchJump & ~LONG_JUMP_MARKER];
        }
        dataIndex += branchJump;
      }

      // ...always include moving past the current node
      dataIndex += (branchCount * BRANCH_CONTROL_CHARS) - NO_END_JUMP;

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

  /** Reads trie content from an external resource. */
  public static ClassNameTrie readFrom(DataInput in) throws IOException {
    int magic = in.readInt();
    if (magic != FILE_MAGIC) {
      throw new IOException("Unexpected file magic " + magic);
    }
    int trieLength = in.readInt();
    char[] trieData = new char[trieLength];
    for (int i = 0; i < trieLength; i++) {
      byte b = in.readByte();
      char c;
      if ((b & 0x80) == 0) {
        // read 7-bit non-zero char as 1 byte
        c = (char) b;
      } else if ((b & 0xE0) == 0xE0) {
        // read 16-bit char as 3 bytes (4+6+6)
        c = (char) (((b & 0x0F) << 12) | ((in.readByte() & 0x3F) << 6) | (in.readByte() & 0x3F));
      } else {
        // read 11-bit char as 2 bytes (5+6)
        c = (char) (((b & 0x1F) << 6) | (in.readByte() & 0x3F));
      }
      trieData[i] = c;
    }
    int longJumpCount = in.readInt();
    int[] longJumps;
    if (longJumpCount > 0) {
      longJumps = new int[longJumpCount];
      for (int i = 0; i < longJumpCount; i++) {
        longJumps[i] = in.readInt();
      }
    } else {
      longJumps = null;
    }
    return new ClassNameTrie(trieData, longJumps);
  }

  ClassNameTrie(char[] trieData, int[] longJumps) {
    this.trieData = trieData;
    this.longJumps = longJumps;
  }

  /** Builds an in-memory trie that represents a mapping of {class-name} to {number}. */
  public static class Builder {
    public static final ClassNameTrie EMPTY_TRIE = new ClassNameTrie(new char[] {0x0000}, null);

    private static final Pattern MAPPING_LINE = Pattern.compile("^\\s*(?:([0-9]+)\\s+)?([^\\s#]+)");

    private char[] trieData;
    private int trieLength;
    private int[] longJumps;
    private int longJumpCount;

    public Builder() {}

    public Builder(ClassNameTrie trie) {
      trieData = trie.trieData;
      trieLength = trieData.length;
      longJumps = trie.longJumps;
      longJumpCount = null != longJumps ? longJumps.length : 0;
    }

    public boolean isEmpty() {
      return trieLength == 0;
    }

    /** Allow querying while the class-name trie is being built. */
    public int apply(String key) {
      return trieLength > 0 ? ClassNameTrie.apply(trieData, longJumps, key) : -1;
    }

    public ClassNameTrie buildTrie() {
      if (trieLength == 0) {
        return EMPTY_TRIE;
      }
      // avoid unnecessary allocation when compaction isn't required
      if (trieData.length > trieLength) {
        trieData = Arrays.copyOfRange(trieData, 0, trieLength);
      }
      if (null != longJumps && longJumps.length > longJumpCount) {
        longJumps = Arrays.copyOfRange(longJumps, 0, longJumpCount);
      }
      return new ClassNameTrie(trieData, longJumps);
    }

    /** Writes trie content to an external resource. */
    public void writeTo(DataOutput out) throws IOException {
      out.writeInt(FILE_MAGIC);
      out.writeInt(trieLength);
      for (int i = 0; i < trieLength; i++) {
        char c = trieData[i];
        if (c >= 0x0001 && c <= 0x007F) {
          // write 7-bit non-zero char as 1 byte
          out.writeByte(c);
        } else if (c > 0x07FF) {
          // write 16-bit char as 3 bytes (4+6+6)
          out.writeByte(0xE0 | ((c >> 12) & 0x0F));
          out.writeByte(0x80 | ((c >> 6) & 0x3F));
          out.writeByte(0x80 | (c & 0x3F));
        } else {
          // write 11-bit char as 2 bytes (5+6)
          out.writeByte(0xC0 | ((c >> 6) & 0x1F));
          out.writeByte(0x80 | (c & 0x3F));
        }
      }
      out.writeInt(longJumpCount);
      for (int i = 0; i < longJumpCount; i++) {
        out.writeInt(longJumps[i]);
      }
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

    /** Merges a new class-name mapping into the current builder */
    public void put(String className, int number) {
      if (null == className || className.isEmpty()) {
        throw new IllegalArgumentException("Null or empty class name");
      }
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

      if (trieLength == 0) {
        int keyLength = key.length();
        trieLength = (keyLength > 1 ? 3 : 2) + keyLength;
        trieData = new char[8192]; // create table on first mapping
        trieData[0] = (char) 1;
        trieData[1] = key.charAt(0);
        if (keyLength > 1) {
          trieData[2] = (char) (keyLength - 1);
          key.getChars(1, keyLength, trieData, 3);
        }
        trieData[trieLength - 1] = (char) (value | LEAF_MARKER);
      } else {
        insertMapping(key, value);
      }
    }

    /** Makes a hole in the current trie data to fit a new node/branch, etc. */
    private void makeHole(int start, int length) {
      char[] oldData = trieData;
      if (trieLength + length > oldData.length) {
        trieData = new char[Math.max(trieLength + length, oldData.length + (oldData.length >> 1))];
        System.arraycopy(oldData, 0, trieData, 0, start);
      }
      System.arraycopy(oldData, start, trieData, start + length, trieLength - start);
      trieLength += length;
    }

    /** Moves jump values that won't fit into the long-jump table and replaces them with an id. */
    private char setJump(int jump) {
      if (jump < LONG_JUMP_MARKER) {
        return (char) jump; // jump is small enough to fit into the trie
      }
      if (longJumpCount == 0) {
        longJumps = new int[16]; // create table on first long-jump
      } else if (longJumpCount == longJumps.length) {
        int[] oldJumps = longJumps;
        // expand table by 50% to fit additional long-jumps
        longJumps = new int[longJumpCount + (longJumpCount >> 1)];
        System.arraycopy(oldJumps, 0, longJumps, 0, longJumpCount);
      }
      longJumps[longJumpCount] = jump;
      return (char) (longJumpCount++ | LONG_JUMP_MARKER);
    }

    /** Restores jump values previously moved into the long-jump table. */
    private int getJump(char jump) {
      return (jump & LONG_JUMP_MARKER) == 0 ? jump : longJumps[jump & ~LONG_JUMP_MARKER];
    }

    /**
     * Increases jump by the given offset, this may result in it moving into the long-jump table.
     */
    private char updateJump(char jump, int offset) {
      if (jump < LONG_JUMP_MARKER) {
        return setJump(jump + offset);
      }
      longJumps[jump & ~LONG_JUMP_MARKER] += offset;
      return jump;
    }

    private void insertMapping(String key, char valueToInsert) {
      BitSet jumpsToOffset = new BitSet();

      int keyLength = key.length();
      int keyIndex = 0;
      int dataIndex = 0;
      int subTrieEnd = trieLength;
      int jumpOffset = 0;

      while (keyIndex < keyLength) {
        char c = key.charAt(keyIndex++);
        char branchCount = trieData[dataIndex++];

        // trie is ordered, so we can use binary search to pick the right branch
        int branchIndex = Arrays.binarySearch(trieData, dataIndex, dataIndex + branchCount, c);

        if (branchIndex < 0) {
          jumpOffset =
              insertBranch(
                  dataIndex,
                  key,
                  keyIndex - 1,
                  valueToInsert,
                  branchCount,
                  ~branchIndex - dataIndex,
                  subTrieEnd);
          break;
        }

        int valueIndex = branchIndex + branchCount;
        char value = trieData[valueIndex];

        if ((value & (LEAF_MARKER | BUD_MARKER)) != 0 && keyIndex == keyLength) {
          // duplicate key: overwrite existing value in the tree with the new value
          trieData[valueIndex] = (char) ((value & ~MAX_NODE_VALUE) | valueToInsert);
          return;
        }

        int branch = branchIndex - dataIndex;
        if (branch < branchCount - 1) {
          int nextJumpIndex = valueIndex + branchCount;
          int nextBranchJump = getJump(trieData[nextJumpIndex]);

          // update subTrieEnd to reflect we've moved down a left/centre branch
          subTrieEnd =
              dataIndex + (branchCount * BRANCH_CONTROL_CHARS) - NO_END_JUMP + nextBranchJump;

          // remember to update jump offsets on right once we know how much we've added
          for (int b = branch + 1; b < branchCount; b++) {
            jumpsToOffset.set(nextJumpIndex++);
          }
        }

        // move on to the segment/node for the picked branch...
        if (branch > 0) {
          dataIndex += getJump(trieData[valueIndex + branchCount - 1]);
        }

        // ...always include moving past the current node
        dataIndex += (branchCount * BRANCH_CONTROL_CHARS) - NO_END_JUMP;

        if ((value & LEAF_MARKER) != 0) {
          // change leaf branch to a bud and append our new leaf node below it
          trieData[valueIndex] = (char) ((value & ~LEAF_MARKER) | BUD_MARKER);
          jumpOffset = appendLeaf(dataIndex, key, keyIndex, valueToInsert);
          break;
        } else if ((value & BUD_MARKER) != 0) {
          // ignore bud as we still have more of the key to match
          continue;
        } else if (keyIndex == keyLength) {
          // branch originally led to a segment - record it's now a bud
          trieData[valueIndex] = (char) (valueToInsert | BUD_MARKER);
          if (value > 0) {
            // add the usual node preamble before the old segment
            jumpOffset = prependNode(dataIndex, value - 1);
          }
          break;
        }

        // must be a segment - try to match as much as possible before we insert a bud/leaf
        if (value > 0) {
          int segmentLength = value;
          int segmentEnd = dataIndex + segmentLength;
          while (keyIndex < keyLength && dataIndex < segmentEnd) {
            c = key.charAt(keyIndex);
            if (c != trieData[dataIndex]) {
              break;
            }
            keyIndex++;
            dataIndex++;
          }
          if (dataIndex < segmentEnd) {
            if (keyIndex == keyLength) {
              // key is shorter than segment; add bud at the point the key ends
              trieData[valueIndex] -= segmentEnd - (dataIndex - 1);
              jumpOffset = insertBud(dataIndex - 1, valueToInsert, segmentEnd);
            } else {
              // key diverges from segment; add leaf on left/right to capture that
              trieData[valueIndex] -= segmentEnd - dataIndex;
              if (c < trieData[dataIndex]) {
                jumpOffset = insertLeafLeft(dataIndex, key, keyIndex, valueToInsert, segmentEnd);
              } else {
                jumpOffset =
                    insertLeafRight(
                        dataIndex, key, keyIndex, valueToInsert, segmentEnd, subTrieEnd);
              }
            }
            break; // nothing more to add
          }

          // peek ahead - it will either be a node or a leaf
          value = trieData[dataIndex];
          if ((value & LEAF_MARKER) != 0) {
            if (keyIndex < keyLength) {
              // key goes past leaf segment, extend old leaf with a bud node and add our new leaf
              trieData[valueIndex]--;
              jumpOffset = appendLeaf(dataIndex, key, keyIndex, valueToInsert);
              break;
            } else {
              // duplicate key: overwrite existing value in the tree with the new value
              trieData[dataIndex] = (char) ((value & ~MAX_NODE_VALUE) | valueToInsert);
              return;
            }
          } else /* segment is followed by a node */ {
            if (keyIndex == keyLength) {
              // key stops at boundary between segment and node, insert bud node just before this
              trieData[valueIndex]--;
              jumpOffset = prependNode(dataIndex - 1, valueToInsert | BUD_MARKER);
              break;
            }
          }
        }
      }

      if (jumpOffset > 0) {
        // now we know how much we added, update all jumps that need to jump past our addition
        for (int i = jumpsToOffset.nextSetBit(0); i >= 0; i = jumpsToOffset.nextSetBit(i + 1)) {
          trieData[i] = updateJump(trieData[i], jumpOffset);
        }
      }
    }

    private int insertBranch(
        int dataIndex,
        String key,
        int keyIndex,
        int value,
        int branchCount,
        int newBranch,
        int subTrieEnd) {

      int remainingKeyLength = key.length() - keyIndex;
      int insertedCharacters = 3 /* segment-length, jump, value */ + remainingKeyLength;

      // can collapse branch if the key only has a single character left
      boolean collapseRight = remainingKeyLength == 1;
      if (collapseRight) {
        remainingKeyLength = 0;
        insertedCharacters = 3; /* branch-key, value, jump */
      }

      int i = dataIndex + newBranch, j = i + insertedCharacters;

      makeHole(i, insertedCharacters);

      // update branch count leading into our branches
      trieData[dataIndex - 1] = (char) (branchCount + 1);

      // insert our new branch key
      trieData[i++] = key.charAt(keyIndex);
      System.arraycopy(trieData, j, trieData, i, branchCount);
      i += branchCount;
      j += branchCount;

      // insert our new branch value
      trieData[i++] = (char) (collapseRight ? value | LEAF_MARKER : remainingKeyLength - 1);

      int subTrieStart = dataIndex + (branchCount * BRANCH_CONTROL_CHARS) - NO_END_JUMP;

      int precedingJump;
      if (newBranch < branchCount) {
        // adding branch on left/centre
        System.arraycopy(trieData, j, trieData, i, branchCount);
        i += branchCount;
        j += branchCount;
        precedingJump = newBranch > 0 ? getJump(trieData[i - 1]) : 0;
        // calculate jump for next branch, using previous jump as a reference
        trieData[i++] = setJump(precedingJump + remainingKeyLength);
        for (int b = newBranch + 1; b < branchCount; b++) {
          // update old branch jumps on right to account for added content
          trieData[i++] = updateJump(trieData[j++], remainingKeyLength);
        }
      } else {
        // adding branch on right
        System.arraycopy(trieData, j, trieData, i, branchCount - 1);
        i += branchCount - 1;
        j += branchCount - 1;
        // calculate jump needed to reach our new branch based on the size of the old sub-trie
        precedingJump = subTrieEnd - subTrieStart;
        trieData[i++] = setJump(precedingJump);
      }

      // now move up the sub-trie content before our new branch
      System.arraycopy(trieData, subTrieStart + insertedCharacters, trieData, i, precedingJump);
      i += precedingJump;

      if (!collapseRight) {
        // lastly add the rest of our key as a leaf segment under our new branch
        key.getChars(keyIndex + 1, key.length(), trieData, i);
        i += remainingKeyLength - 1;
        trieData[i++] = (char) (value | LEAF_MARKER);
      }

      return insertedCharacters;
    }

    private int prependNode(int dataIndex, int value) {
      int insertedCharacters = 2; /* branch count, value */

      // can collapse branch if it would lead to a leaf segment of zero-length
      boolean collapseRight = value == 0 && (trieData[dataIndex + 1] & LEAF_MARKER) != 0;
      if (collapseRight) {
        insertedCharacters--;
      }

      int i = dataIndex, j = i + insertedCharacters;

      makeHole(i, insertedCharacters);

      trieData[i++] = 1;
      trieData[i++] = trieData[j];
      if (!collapseRight) {
        trieData[i++] = (char) value;
      }

      return insertedCharacters;
    }

    private int insertBud(int dataIndex, int value, int segmentEnd) {
      int insertedCharacters = 4; // branch count (bud), value, branch count (rest), segment-length
      int pivot = dataIndex + 2;

      // can collapse right branch if it would lead to a leaf segment of zero-length
      boolean collapseRight = pivot == segmentEnd && (trieData[segmentEnd] & LEAF_MARKER) != 0;
      if (collapseRight) {
        insertedCharacters = 3;
      }

      int i = dataIndex, j = i + insertedCharacters;

      makeHole(i, insertedCharacters);

      trieData[i++] = 1;
      trieData[i++] = trieData[j];
      trieData[i++] = (char) (value | BUD_MARKER);
      trieData[i++] = 1;
      trieData[i++] = trieData[j + 1];
      if (!collapseRight) {
        trieData[i++] = (char) (segmentEnd - pivot);
      }

      return insertedCharacters;
    }

    private int insertLeafLeft(int dataIndex, String key, int keyIndex, int value, int segmentEnd) {
      int remainingKeyLength = key.length() - keyIndex;
      int insertedCharacters =
          5 /* branch count, 2 * segment-length, jump, value */ + remainingKeyLength;
      int pivot = dataIndex + 1;

      // can collapse left branch if the key only has a single character left
      boolean collapseLeft = remainingKeyLength == 1;
      if (collapseLeft) {
        insertedCharacters--;
      }
      // can collapse right branch if it would lead to a leaf segment of zero-length
      boolean collapseRight = pivot == segmentEnd && (trieData[segmentEnd] & LEAF_MARKER) != 0;
      if (collapseRight) {
        insertedCharacters--;
        pivot++;
      }

      int i = dataIndex, j = i + insertedCharacters;

      makeHole(i, insertedCharacters);

      trieData[i++] = 2;
      trieData[i++] = key.charAt(keyIndex);
      trieData[i++] = trieData[j];
      trieData[i++] = (char) (collapseLeft ? value | LEAF_MARKER : remainingKeyLength - 1);
      trieData[i++] = (char) (collapseRight ? trieData[j + 1] : segmentEnd - pivot);
      if (!collapseLeft) {
        trieData[i++] = (char) remainingKeyLength;
        key.getChars(keyIndex + 1, key.length(), trieData, i);
        i += remainingKeyLength - 1;
        trieData[i++] = (char) (value | LEAF_MARKER);
      } else {
        trieData[i++] = 0;
      }

      return insertedCharacters;
    }

    private int insertLeafRight(
        int dataIndex, String key, int keyIndex, int value, int segmentEnd, int subTrieEnd) {
      int remainingKeyLength = key.length() - keyIndex;
      int insertedCharacters =
          5 /* branch count, 2 * segment-length, jump, value */ + remainingKeyLength;
      int pivot = dataIndex + 1;

      // can collapse left branch if it would lead to a leaf segment of zero-length
      boolean collapseLeft = pivot == segmentEnd && (trieData[segmentEnd] & LEAF_MARKER) != 0;
      if (collapseLeft) {
        insertedCharacters--;
        pivot++;
      }
      // can collapse right branch if the key only has a single character left
      boolean collapseRight = remainingKeyLength == 1;
      if (collapseRight) {
        insertedCharacters--;
      }

      int i = dataIndex, j = i + insertedCharacters;

      makeHole(i, insertedCharacters);

      trieData[i++] = 2;
      trieData[i++] = trieData[j];
      trieData[i++] = key.charAt(keyIndex);
      trieData[i++] = (char) (collapseLeft ? trieData[j + 1] : segmentEnd - pivot);
      trieData[i++] = (char) (collapseRight ? value | LEAF_MARKER : remainingKeyLength - 1);
      trieData[i++] = setJump(subTrieEnd - pivot);
      System.arraycopy(trieData, pivot + insertedCharacters, trieData, i, subTrieEnd - pivot);
      i += subTrieEnd - pivot;
      if (!collapseRight) {
        key.getChars(keyIndex + 1, key.length(), trieData, i);
        i += remainingKeyLength - 1;
        trieData[i++] = (char) (value | LEAF_MARKER);
      }

      return insertedCharacters;
    }

    private int appendLeaf(int dataIndex, String key, int keyIndex, int value) {
      int remainingKeyLength = key.length() - keyIndex;
      int insertedCharacters = 3 /* branch count, segment-length, value */ + remainingKeyLength;

      // if we're adding this to a leaf segment then we need to introduce a bud first
      boolean insertBud = dataIndex < trieLength && (trieData[dataIndex] & LEAF_MARKER) != 0;
      if (insertBud) {
        insertedCharacters++;
      }
      // can collapse right branch if the key only has a single character left
      boolean collapseRight = remainingKeyLength == 1;
      if (collapseRight) {
        insertedCharacters--;
      }

      int i = dataIndex, j = i + insertedCharacters;

      makeHole(i, insertedCharacters);

      if (insertBud) {
        char c = trieData[i - 1];
        trieData[i - 1] = 1;
        trieData[i++] = c;
        trieData[i++] = (char) ((trieData[j] & ~LEAF_MARKER) | BUD_MARKER);
      }
      trieData[i++] = 1;
      trieData[i++] = key.charAt(keyIndex);
      if (!collapseRight) {
        trieData[i++] = (char) (remainingKeyLength - 1);
        key.getChars(keyIndex + 1, key.length(), trieData, i);
        i += remainingKeyLength - 1;
      }
      trieData[i++] = (char) (value | LEAF_MARKER);

      return insertedCharacters;
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
      ClassNameTrie.Builder trie = new ClassNameTrie.Builder();
      trie.readClassNameMapping(triePath);
      List<String> lines = new ArrayList<>();
      if (!pkgName.isEmpty()) {
        lines.add("package " + pkgName + ';');
      }
      lines.add("");
      lines.add("import datadog.trace.util.ClassNameTrie;");
      lines.add("");
      lines.add("// Generated from '" + triePath.getFileName() + "' - DO NOT EDIT!");
      lines.add("public final class " + className + " {");
      lines.add("");
      boolean hasLongJumps = generateJavaTrie(lines, "", trie);
      lines.add("");
      lines.add("  public static int apply(String key) {");
      if (hasLongJumps) {
        lines.add("    return ClassNameTrie.apply(TRIE_DATA, LONG_JUMPS, key);");
      } else {
        lines.add("    return ClassNameTrie.apply(TRIE_DATA, null, key);");
      }
      lines.add("  }");
      lines.add("");
      lines.add("  public static int apply(String key, int fromIndex) {");
      if (hasLongJumps) {
        lines.add("    return ClassNameTrie.apply(TRIE_DATA, LONG_JUMPS, key, fromIndex);");
      } else {
        lines.add("    return ClassNameTrie.apply(TRIE_DATA, null, key, fromIndex);");
      }
      lines.add("  }");
      lines.add("");
      lines.add("  private " + className + "() {}");
      lines.add("}");
      Files.write(javaPath, lines, StandardCharsets.UTF_8);
    }

    /** Writes the Java form of the trie as a series of lines. */
    public static boolean generateJavaTrie(
        List<String> lines, String prefix, ClassNameTrie.Builder trie) {
      boolean hasLongJumps = trie.longJumpCount > 0;
      int firstLineNumber = lines.size();
      int chunk = 1;
      lines.add("  private static final String " + prefix + "TRIE_TEXT_" + chunk + " =");
      int chunkSize = 0;
      StringBuilder buf = new StringBuilder();
      buf.append("      \"");
      for (int i = 0; i < trie.trieLength; i++) {
        char c = trie.trieData[i];
        if (++chunkSize > 10_000) {
          chunk++;
          chunkSize = 0;
          lines.add(buf + "\";");
          lines.add("");
          lines.add("  private static final String " + prefix + "TRIE_TEXT_" + chunk + " =");
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
        lines.add("  private static final char[] " + prefix + "TRIE_DATA;");
        lines.add("  static {");
        lines.add("    int dataLength = 0;");
        for (int n = 1; n <= chunk; n++) {
          lines.add("    dataLength += " + prefix + "TRIE_TEXT_" + n + ".length();");
        }
        lines.add("    " + prefix + "TRIE_DATA = new char[dataLength];");
        lines.add("    int dataIndex = 0;");
        lines.add("    String chunk;");
        for (int n = 1; n <= chunk; n++) {
          lines.add("    chunk = " + prefix + "TRIE_TEXT_" + n + ";");
          lines.add("    chunk.getChars(0, chunk.length(), " + prefix + "TRIE_DATA, dataIndex);");
          lines.add("    dataIndex += chunk.length();");
        }
        lines.add("  }");
      } else {
        // only one chunk so can simplify char array creation
        lines.set(firstLineNumber, "  private static final String " + prefix + "TRIE_TEXT =");
        lines.add(
            "  private static final char[] "
                + prefix
                + "TRIE_DATA = "
                + prefix
                + "TRIE_TEXT.toCharArray();");
      }
      if (hasLongJumps) {
        lines.add("");
        lines.add("  private static final int[] " + prefix + "LONG_JUMPS = {");
        buf.setLength(0);
        buf.append("   ");
        for (int i = 0; i < trie.longJumpCount; i++) {
          int jump = trie.longJumps[i];
          if (buf.length() > 90) {
            lines.add(buf.toString());
            buf.setLength(0);
            buf.append("   ");
          }
          buf.append(' ').append(String.format("0x%06X", jump)).append(',');
        }
        lines.add(buf.toString());
        lines.add("  };");
      }
      return hasLongJumps;
    }
  }
}
