package com.datadog.debugger.util;

import static com.datadog.debugger.sink.SnapshotSink.MAX_SNAPSHOT_SIZE;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SnapshotPrunerTest {
  @Test
  public void noPruning() {
    assertEquals("[]", SnapshotPruner.prune("[]", MAX_SNAPSHOT_SIZE, 0));
    assertEquals("{}", SnapshotPruner.prune("{}", MAX_SNAPSHOT_SIZE, 0));
    assertEquals("[{},{},{}]", SnapshotPruner.prune("[{},{},{}]", MAX_SNAPSHOT_SIZE, 0));
    assertEquals(
        "{\"foo\":[[],[],[]]}", SnapshotPruner.prune("{\"foo\":[[],[],[]]}", MAX_SNAPSHOT_SIZE, 0));
    assertEquals(
        "{\"foo\":\"bar\"}", SnapshotPruner.prune("{\"foo\":\"bar\"}", MAX_SNAPSHOT_SIZE, 0));
    assertEquals("{\"foo\":1001}", SnapshotPruner.prune("{\"foo\":1001}", MAX_SNAPSHOT_SIZE, 0));
    assertEquals("{\"foo\":3.14}", SnapshotPruner.prune("{\"foo\":3.14}", MAX_SNAPSHOT_SIZE, 0));
    assertEquals("{\"foo\":true}", SnapshotPruner.prune("{\"foo\":true}", MAX_SNAPSHOT_SIZE, 0));
    assertEquals(
        "{\"foo\":{\"name\":\"value\"}}",
        SnapshotPruner.prune("{\"foo\":{\"name\":\"value\"}}", MAX_SNAPSHOT_SIZE, 0));
    assertEquals(
        "{\"foo1\":{\"foo2\":{\"foo3\":{\"foo4\":{\"foo5\":{}}}}}}",
        SnapshotPruner.prune(
            "{\"foo1\":{\"foo2\":{\"foo3\":{\"foo4\":{\"foo5\":{}}}}}}", MAX_SNAPSHOT_SIZE, 0));
  }

  @Test
  public void basic() {
    assertEquals("aaaa{\"pruned\":true}bbbb", SnapshotPruner.prune("aaaa{}bbbb", 8, 0));
    assertEquals(
        "aa{\"pruned\":true}bb",
        SnapshotPruner.prune("aa{\"notCapturedReason\": \"depth\"}bb", 8, 0));
    assertEquals(
        "aa{\"pruned\":true}bb",
        SnapshotPruner.prune("aa{\"notCapturedReason\": \"collectionSize\"}bb", 8, 0));
  }

  @Test
  public void priorityPruning() {
    final String INPUT =
        "{\n"
            + "  \"elements\":[\n"
            + "    {\n"
            + "      \"type\": \"list\",\n"
            + "      \"notCapturedReason\": \"collectionSize\"\n"
            + "    },\n"
            + "    {\n"
            + "      \"type\": \"complex\",\n"
            + "      \"notCapturedReason\": \"depth\"\n"
            + "    },\n"
            + "    {\n"
            + "      \"type\": \"deep\",\n"
            + "      \"subobject\": {\n"
            + "        \"type\": \"complex\",\n"
            + "        \"value\": \"subobject\"\n"
            + "      }\n"
            + "    }\n"
            + "    {\n"
            + "      \"type\": \"complex\",\n"
            + "      \"value\": \"sfsfsdfklsdfslkfjsdfkjsdklfjsdflksdjfsdlfjsdklfsjdfklsjfksfjslkdfjskdlf\"\n"
            + "    }\n"
            + "  ]\n"
            + "}";
    assertEquals(
        "{\n"
            + "  \"elements\":[\n"
            + "    {\n"
            + "      \"type\": \"list\",\n"
            + "      \"notCapturedReason\": \"collectionSize\"\n"
            + "    },\n"
            + "    {\"pruned\":true},\n"
            + "    {\n"
            + "      \"type\": \"deep\",\n"
            + "      \"subobject\": {\n"
            + "        \"type\": \"complex\",\n"
            + "        \"value\": \"subobject\"\n"
            + "      }\n"
            + "    }\n"
            + "    {\n"
            + "      \"type\": \"complex\",\n"
            + "      \"value\": \"sfsfsdfklsdfslkfjsdfkjsdklfjsdflksdjfsdlfjsdklfsjdfklsjfksfjslkdfjskdlf\"\n"
            + "    }\n"
            + "  ]\n"
            + "}",
        SnapshotPruner.prune(INPUT, 400, 0));
    assertEquals(
        "{\n"
            + "  \"elements\":[\n"
            + "    {\n"
            + "      \"type\": \"list\",\n"
            + "      \"notCapturedReason\": \"collectionSize\"\n"
            + "    },\n"
            + "    {\"pruned\":true},\n"
            + "    {\"pruned\":true}\n"
            + "    {\n"
            + "      \"type\": \"complex\",\n"
            + "      \"value\": \"sfsfsdfklsdfslkfjsdfkjsdklfjsdflksdjfsdlfjsdklfsjdfklsjfksfjslkdfjskdlf\"\n"
            + "    }\n"
            + "  ]\n"
            + "}",
        SnapshotPruner.prune(INPUT, 300, 0));
    assertEquals(
        "{\n"
            + "  \"elements\":[\n"
            + "    {\"pruned\":true},\n"
            + "    {\"pruned\":true},\n"
            + "    {\"pruned\":true}\n"
            + "    {\n"
            + "      \"type\": \"complex\",\n"
            + "      \"value\": \"sfsfsdfklsdfslkfjsdfkjsdklfjsdflksdjfsdlfjsdklfsjdfklsjfksfjslkdfjskdlf\"\n"
            + "    }\n"
            + "  ]\n"
            + "}",
        SnapshotPruner.prune(INPUT, 250, 0));
    assertEquals(
        "{\n"
            + "  \"elements\":[\n"
            + "    {\"pruned\":true},\n"
            + "    {\"pruned\":true},\n"
            + "    {\"pruned\":true}\n"
            + "    {\"pruned\":true}\n"
            + "  ]\n"
            + "}",
        SnapshotPruner.prune(INPUT, 200, 0));
  }

  @Test
  public void sizeReduction() {
    final String INPUT =
        "{\n"
            + "                \"keep\": {\"type\": \"list\", \"size\":2, \"elements\": [\n"
            + "                    {\"type\": \"str\", \"value\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},\n"
            + "                    {\"type\": \"str\", \"value\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}\n"
            + "                ]},\n"
            + "                \"prune\": {\"type\": \"list\", \"size\":2, \"elements\": [\n"
            + "                    {\"type\": \"Custom\", \"notCapturedReason\": \"depth\"},\n"
            + "                    {\"type\": \"Custom\", \"notCapturedReason\": \"depth\"}\n"
            + "                ]}\n"
            + "            }";
    assertEquals(
        "{\n"
            + "                \"keep\": {\"type\": \"list\", \"size\":2, \"elements\": [\n"
            + "                    {\"type\": \"str\", \"value\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},\n"
            + "                    {\"type\": \"str\", \"value\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}\n"
            + "                ]},\n"
            + "                \"prune\": {\"type\": \"list\", \"size\":2, \"elements\": [\n"
            + "                    {\"type\": \"Custom\", \"notCapturedReason\": \"depth\"},\n"
            + "                    {\"type\": \"Custom\", \"notCapturedReason\": \"depth\"}\n"
            + "                ]}\n"
            + "            }",
        SnapshotPruner.prune(INPUT, 800, 0));
    assertEquals(
        "{\n"
            + "                \"keep\": {\"type\": \"list\", \"size\":2, \"elements\": [\n"
            + "                    {\"type\": \"str\", \"value\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},\n"
            + "                    {\"type\": \"str\", \"value\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}\n"
            + "                ]},\n"
            + "                \"prune\": {\"type\": \"list\", \"size\":2, \"elements\": [\n"
            + "                    {\"pruned\":true},\n"
            + "                    {\"pruned\":true}\n"
            + "                ]}\n"
            + "            }",
        SnapshotPruner.prune(INPUT, 440, 0));
    assertEquals(
        "{\n"
            + "                \"keep\": {\"type\": \"list\", \"size\":2, \"elements\": [\n"
            + "                    {\"type\": \"str\", \"value\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},\n"
            + "                    {\"type\": \"str\", \"value\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}\n"
            + "                ]},\n"
            + "                \"prune\": {\"pruned\":true}\n"
            + "            }",
        SnapshotPruner.prune(INPUT, 350, 0));
    assertEquals(
        "{\n"
            + "                \"keep\": {\"type\": \"list\", \"size\":2, \"elements\": [\n"
            + "                    {\"type\": \"str\", \"value\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},\n"
            + "                    {\"pruned\":true}\n"
            + "                ]},\n"
            + "                \"prune\": {\"pruned\":true}\n"
            + "            }",
        SnapshotPruner.prune(INPUT, 270, 0));
    assertEquals(
        "{\n"
            + "                \"keep\": {\"type\": \"list\", \"size\":2, \"elements\": [\n"
            + "                    {\"pruned\":true},\n"
            + "                    {\"pruned\":true}\n"
            + "                ]},\n"
            + "                \"prune\": {\"pruned\":true}\n"
            + "            }",
        SnapshotPruner.prune(INPUT, 240, 0));
    assertEquals(
        "{\n"
            + "                \"keep\": {\"pruned\":true},\n"
            + "                \"prune\": {\"pruned\":true}\n"
            + "            }",
        SnapshotPruner.prune(INPUT, 120, 0));
    assertEquals("{\"pruned\":true}", SnapshotPruner.prune(INPUT, 20, 0));
  }

  @Test
  public void sliceSmallSnapshot() throws Exception {
    final int MIN_LEVEL = 6;
    String inputSmallSnapshot =
        utils.TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot.json").trim();
    assertEquals(
        inputSmallSnapshot,
        SnapshotPruner.prune(inputSmallSnapshot, inputSmallSnapshot.length(), MIN_LEVEL));
    String smallSnapshot =
        utils.TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_pruned0.json")
            .trim();
    assertEquals(smallSnapshot, SnapshotPruner.prune(inputSmallSnapshot, 1500, MIN_LEVEL));
    smallSnapshot =
        utils.TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_pruned1.json")
            .trim();
    assertEquals(smallSnapshot, SnapshotPruner.prune(inputSmallSnapshot, 1250, MIN_LEVEL));
    smallSnapshot =
        utils.TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_pruned2.json")
            .trim();
    assertEquals(smallSnapshot, SnapshotPruner.prune(inputSmallSnapshot, 1100, MIN_LEVEL));
    smallSnapshot =
        utils.TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_pruned3.json")
            .trim();
    assertEquals(smallSnapshot, SnapshotPruner.prune(inputSmallSnapshot, 1000, MIN_LEVEL));
    smallSnapshot =
        utils.TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_pruned4.json")
            .trim();
    assertEquals(smallSnapshot, SnapshotPruner.prune(inputSmallSnapshot, 500, MIN_LEVEL));
  }
}
