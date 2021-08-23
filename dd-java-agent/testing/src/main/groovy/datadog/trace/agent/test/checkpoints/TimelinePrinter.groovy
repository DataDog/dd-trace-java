package datadog.trace.agent.test.checkpoints

class TimelinePrinter {
  private static String[] emptySpaces = new String[128]

  static void print(def spanEvents, def threadEvents, def orderedEvents, def invalidEvents, PrintStream out) {
    if (!orderedEvents.isEmpty()) {
      out.println("=== Activity checkpoints by thread ordered by time")
      if (!invalidEvents.empty) {
        out.println("===== Invalid event sequences were detected. " +
          "Affected spans are highlited by '***'")
      }
      // allows rendering threads top to bottom by when they were first encountered
      Map<Thread, BitSet> timelines = new LinkedHashMap<>()
      int maxNameLength = 0
      String[] renderings = new String[orderedEvents.size()]
      for (Thread thread : threadEvents.keySet()) {
        maxNameLength = Math.max(maxNameLength, thread.name.length())
      }
      int position = 0
      for (Event event : orderedEvents) {
        if (invalidEvents.contains(event)) {
          renderings[position] = "***" + event.name + "/" + event.spanId + "***"
        } else {
          renderings[position] = event.name + "/" +event.spanId
        }
        BitSet timeline = timelines[event.thread]
        if (null == timeline) {
          timelines[event.thread] = timeline = new BitSet()
        }
        timeline.set(position++)
      }
      for (Map.Entry<Thread, BitSet> timeline : timelines) {
        Thread thread = timeline.key
        out.print(thread.name)
        out.print(":")
        out.print(repeat(" ", maxNameLength - thread.name.length() + 1))
        out.print("|")
        BitSet positions = timeline.value
        int next = positions.nextSetBit(0)
        for (int i = 0; i < renderings.length; ++i) {
          if (renderings[i] == null) {
            break
          }

          out.print("-")
          if (i == next) {
            out.print(renderings[i])
            next = positions.nextSetBit(next + 1)
          } else {
            out.print(getEmptySpace(renderings[i] != null ? renderings[i].length() : 0))
          }
          out.print("-|")
        }
        out.println()
      }
      out.println("")
    }
  }

  private static String getEmptySpace(int width) {
    if (width >= emptySpaces.length) {
      return repeat("-", width)
    }
    String space = emptySpaces[width]
    if (null == space) {
      space = emptySpaces[width] = repeat("-", width)
    }
    return space
  }

  private static String repeat(String x, int length) {
    StringBuilder sb = new StringBuilder(x.length() * length)
    for (int i = 0; i < length; ++i) {
      sb.append(x)
    }
    return sb.toString()
  }
}
