package com.datadog.debugger.instrumentation;

import org.junit.Assert;
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

    Assert.assertEquals(true, map.isEmpty());
    Assert.assertEquals(null, map.getLineLabel(2));
  }

  @Test
  void ValidateLineMapReturnLinesInRangeOrNull() {
    LineMap map = new LineMap();
    LabelNode line1 = addLine(map, 1);
    LabelNode line2 = addLine(map, 2);
    LabelNode line5 = addLine(map, 5);

    Assert.assertEquals(line1, map.getLineLabel(0));
    Assert.assertEquals(line1, map.getLineLabel(1));
    Assert.assertEquals(line2, map.getLineLabel(2));
    Assert.assertEquals(line5, map.getLineLabel(3));
    Assert.assertEquals(line5, map.getLineLabel(4));
    Assert.assertEquals(line5, map.getLineLabel(5));
    Assert.assertEquals(null, map.getLineLabel(6));
  }

  @Test
  void AddingSameLabelAndLineTwiceWorks() {
    LineMap map = new LineMap();
    LabelNode line1 = addLine(map, 1);
    LabelNode line2 = addLine(map, 2);
    map.addLine(new LineNumberNode(1, line1));
    LabelNode line5 = addLine(map, 5);

    Assert.assertEquals(line1, map.getLineLabel(0));
    Assert.assertEquals(line1, map.getLineLabel(1));
    Assert.assertEquals(line2, map.getLineLabel(2));
  }

  @Test
  void AddingDifferentLabelsOverwriteLabel() {
    LineMap map = new LineMap();
    LabelNode line1 = addLine(map, 1);
    LabelNode line1b = addLine(map, 1);

    Assert.assertEquals(line1b, map.getLineLabel(0));
    Assert.assertEquals(line1b, map.getLineLabel(1));
  }
}
