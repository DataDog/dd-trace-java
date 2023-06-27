package com.datadog.debugger.instrumentation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;

public class LineMapTest {

  LabelNode addLine(LineMap map, int line) {
    LabelNode label = new LabelNode();
    LineNumberNode lineNode = new LineNumberNode(line, label);
    map.addLine(lineNode);
    return label;
  }

  @Test
  void isEmptyReturnTrue() {
    LineMap map = new LineMap();

    Assertions.assertEquals(true, map.isEmpty());
    Assertions.assertEquals(null, map.getLineLabel(2));
  }

  @Test
  void ValidateLineMapReturnLinesInRangeOrNull() {
    LineMap map = new LineMap();
    LabelNode line1 = addLine(map, 1);
    LabelNode line2 = addLine(map, 2);
    LabelNode line5 = addLine(map, 5);

    Assertions.assertEquals(line1, map.getLineLabel(0));
    Assertions.assertEquals(line1, map.getLineLabel(1));
    Assertions.assertEquals(line2, map.getLineLabel(2));
    Assertions.assertEquals(line5, map.getLineLabel(3));
    Assertions.assertEquals(line5, map.getLineLabel(4));
    Assertions.assertEquals(line5, map.getLineLabel(5));
    Assertions.assertEquals(null, map.getLineLabel(6));
  }

  @Test
  void AddingSameLabelAndLineTwiceWorks() {
    LineMap map = new LineMap();
    LabelNode line1 = addLine(map, 1);
    LabelNode line2 = addLine(map, 2);
    map.addLine(new LineNumberNode(1, line1));
    LabelNode line5 = addLine(map, 5);

    Assertions.assertEquals(line1, map.getLineLabel(0));
    Assertions.assertEquals(line1, map.getLineLabel(1));
    Assertions.assertEquals(line2, map.getLineLabel(2));
  }

  @Test
  void AddingDifferentLabelsDontOverwriteLabel() {
    LineMap map = new LineMap();
    LabelNode line1 = addLine(map, 1);
    LabelNode line1b = addLine(map, 1);

    Assertions.assertEquals(line1, map.getLineLabel(0));
    Assertions.assertEquals(line1, map.getLineLabel(1));
  }
}
