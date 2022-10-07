package com.datadog.iast.taint

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType

class TaintUtils {

  static final String OPEN_MARK = "==>"

  static final String CLOSE_MARK = "<=="

  static String taintFormat(final String s, final Range[] ranges) {
    if (ranges == null || ranges.length == 0) {
      return s
    }
    if (s == null || s.isEmpty()) {
      return s
    }
    int pos = 0
    String res = ""
    for (int i = 0; i < ranges.length; i++) {
      final Range cur = ranges[i]
      if (cur.start > pos) {
        res += s[pos..cur.start - 1]
        pos = cur.start
      }
      res += OPEN_MARK
      res += s[cur.start..cur.start + cur.length - 1]
      res += CLOSE_MARK
      pos = cur.start + cur.length
    }
    if (pos < s.length()) {
      res += s[pos..s.length()-1]
    }
    res
  }

  static Range[] fromTaintFormat(final String s) {
    if (s == null || s.isEmpty()) {
      return null
    }
    def ranges = new ArrayList<Range>()
    int pos = 0
    for (int i = 0; i < s.length(); i++) {
      if (s.startsWith(OPEN_MARK, i)) {
        int upTo = s.indexOf(CLOSE_MARK, i + OPEN_MARK.length())
        assert upTo > i + OPEN_MARK.length()
        int start = pos
        int length = (upTo - i) - OPEN_MARK.length()
        assert length >= 0
        ranges.add(new Range(start, length, new Source(SourceType.NONE, null, null)))
        pos += length
        i += OPEN_MARK.length() + length + CLOSE_MARK.length() - 1
      } else {
        pos++
      }
    }
    return (ranges.size() == 0)? null : ranges as Range[]
  }

  static String getStringFromTaintFormat(final String s) {
    if (s == null) {
      return null
    }
    new String(s.replace(OPEN_MARK, "").replace(CLOSE_MARK, ""))
  }

  static String addFromTaintFormat(final TaintedObjects tos, final String s) {
    final ranges = fromTaintFormat(s)
    if (ranges == null || ranges.length == 0) {
      return s
    }
    final resultString = getStringFromTaintFormat(s)
    tos.taint(resultString, ranges)
    return resultString
  }

  static Range toRange(List<Integer> lst) {
    toRange(lst.get(0), lst.get(1))
  }

  static Range toRange(int start, int length) {
    new Range(start, length, new Source(SourceType.NONE, null, null))
  }

  static Range[] toRanges(List<List<Integer>> lst) {
    if (lst == null) {
      return null
    }
    lst.collect { toRange(it) } as Range[]
  }
}
