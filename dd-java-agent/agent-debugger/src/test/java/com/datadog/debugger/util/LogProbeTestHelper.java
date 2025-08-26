package com.datadog.debugger.util;

import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.LogProbe;
import datadog.config.util.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogProbeTestHelper {

  public static List<LogProbe.Segment> parseTemplate(String template) {
    if (template == null) {
      return Collections.emptyList();
    }
    List<LogProbe.Segment> result = new ArrayList<>();
    int currentIdx = 0;
    int startStrIdx = 0;
    do {
      int startIdx = template.indexOf('{', currentIdx);
      if (startIdx == -1) {
        addStrSegment(result, template.substring(startStrIdx));
        return result;
      }
      if (startIdx + 1 < template.length() && template.charAt(startIdx + 1) == '{') {
        currentIdx = startIdx + 2;
        continue;
      }
      int endIdx = template.indexOf('}', startIdx);
      if (endIdx == -1) {
        addStrSegment(result, template.substring(startStrIdx));
        currentIdx = startIdx + 1;
        startStrIdx = currentIdx;
      } else {
        if (startStrIdx != startIdx) {
          addStrSegment(result, template.substring(startStrIdx, startIdx));
        }
        String expr = template.substring(startIdx + 1, endIdx);

        result.add(new LogProbe.Segment(new ValueScript(ValueScript.parseRefPath(expr), expr)));
        currentIdx = endIdx + 1;
        startStrIdx = currentIdx;
      }
    } while (currentIdx < template.length());
    return result;
  }

  private static void addStrSegment(List<LogProbe.Segment> segments, String str) {
    str = Strings.replace(str, "{{", "{");
    str = Strings.replace(str, "}}", "}");
    segments.add(new LogProbe.Segment(str));
  }
}
