package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.ptags.PTagsFactory.PTags.decisionMakerTagValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.propagation.ptags.TagElement.Encoding;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class DecisionMakerTagValueTest {
  @TableTest({
    "scenario          | mechanism | expected",
    "default           | 0         | '-0'    ",
    "agent rate        | 1         | '-1'    ",
    "manual            | 4         | '-4'    ",
    "ai guard          | 13        | '-13'   ",
    "last table entry  | 15        | '-15'   ",
    "beyond table      | 16        | '-16'   ",
    "well beyond table | 127       | '-127'  "
  })
  void formatsMechanismAsDecisionMaker(int mechanism, String expected) {
    TagValue value = decisionMakerTagValue(mechanism);

    assertEquals(expected, value.forType(Encoding.DATADOG).toString());
    assertEquals(TagValue.from(expected), value);
  }

  @Test
  void reusesInstanceForKnownMechanisms() {
    assertSame(
        decisionMakerTagValue(SamplingMechanism.MANUAL),
        decisionMakerTagValue(SamplingMechanism.MANUAL));
    assertSame(
        decisionMakerTagValue(SamplingMechanism.AI_GUARD),
        decisionMakerTagValue(SamplingMechanism.AI_GUARD));
  }
}
