package datadog.trace.agent.test.checkpoints

class TimelinePrinter {
  private static String[] emptySpaces = new String[128]

  static void print(def spanEvents, def threadEvents, def orderedEvents, def invalidEvents = Collections.emptySet()) {
    if (!orderedEvents.isEmpty()) {
      System.err.println("=== Activity checkpoints by thread ordered by time")
      if (!invalidEvents.empty) {
        System.err.println("===== Invalid event sequences were detected. " +
                            "Affected spans are highlited by '***'")
      }
      // allows rendering threads top to bottom by when they were first encountered
      Map<String, BitSet> timelines = new LinkedHashMap<>()
      int maxNameLength = 0
      String[] renderings = new String[orderedEvents.size()]
      for (String threadName : threadEvents.keySet()) {
        maxNameLength = Math.max(maxNameLength, threadName.length())
      }
      int position = 0
      for (Event event : orderedEvents) {
        if (invalidEvents.contains(event)) {
          renderings[position] = "***" + event.name + "/" + event.spanId + "***"
        } else {
          renderings[position] = event.name + "/" +event.spanId
        }
        BitSet timeline = timelines[event.threadName]
        if (null == timeline) {
          timelines[event.threadName] = timeline = new BitSet()
        }
        timeline.set(position++)
      }
      for (Map.Entry<String, BitSet> timeline : timelines) {
        String threadName = timeline.key
        System.err.print(threadName)
        System.err.print(":")
        System.err.print(repeat(" ", maxNameLength - threadName.length() + 1))
        System.err.print("|")
        BitSet positions = timeline.value
        int next = positions.nextSetBit(0)
        for (int i = 0; i < renderings.length; ++i) {
          if (renderings[i] == null) {
            break
          }

          System.err.print("-")
          if (i == next) {
            System.err.print(renderings[i])
            next = positions.nextSetBit(next + 1)
          } else {
            System.err.print(getEmptySpace(renderings[i] != null ? renderings[i].length() : 0))
          }
          System.err.print("-|")
        }
        System.err.println()
      }
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
