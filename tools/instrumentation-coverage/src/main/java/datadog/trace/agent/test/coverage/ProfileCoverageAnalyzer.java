package datadog.trace.agent.test.coverage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/** Compares sampled JFR stacks with exact method-entry evidence from the prototype collector. */
public final class ProfileCoverageAnalyzer {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private ProfileCoverageAnalyzer() {}

  public static void main(String[] args) throws IOException {
    if (args.length < 4) {
      throw new IllegalArgumentException(
          "Usage: ProfileCoverageAnalyzer <recording.jfr> <output-directory> "
              + "<library-prefix> <entry-report.json> [entry-report.json ...]");
    }

    Path recording = Path.of(args[0]);
    Path outputDirectory = Path.of(args[1]);
    String libraryPrefix = args[2];
    List<Path> entryReports = new ArrayList<>();
    for (int i = 3; i < args.length; i++) {
      entryReports.add(Path.of(args[i]));
    }

    EntryEvidence entryEvidence = readEntryEvidence(entryReports);
    Analysis analysis = analyze(recording, libraryPrefix, entryEvidence);
    Files.createDirectories(outputDirectory);
    Files.writeString(
        outputDirectory.resolve("profile-analysis.json"),
        GSON.toJson(analysis.toJson()),
        StandardCharsets.UTF_8);
    Files.writeString(
        outputDirectory.resolve("profile-analysis.md"),
        analysis.toMarkdown(recording, libraryPrefix),
        StandardCharsets.UTF_8);
  }

  private static EntryEvidence readEntryEvidence(List<Path> reports) throws IOException {
    Set<String> selected = new LinkedHashSet<>();
    Set<String> observed = new LinkedHashSet<>();
    for (Path report : reports) {
      JsonObject root = GSON.fromJson(Files.readString(report), JsonObject.class);
      for (JsonElement element : root.getAsJsonArray("methods")) {
        JsonObject method = element.getAsJsonObject();
        String key = method.get("method").getAsString();
        selected.add(key);
        if (!method.getAsJsonArray("observations").isEmpty()) {
          observed.add(key);
        }
      }
    }
    return new EntryEvidence(selected, observed);
  }

  private static Analysis analyze(Path recording, String libraryPrefix, EntryEvidence entryEvidence)
      throws IOException {
    Map<String, SignalStats> signals = new LinkedHashMap<>();
    signals.put("cpu", new SignalStats());
    signals.put("allocation", new SignalStats());
    signals.put("blocking", new SignalStats());
    Set<String> eventTypes = new LinkedHashSet<>();
    Map<String, Integer> allLibraryMethods = new TreeMap<>();

    try (RecordingFile input = new RecordingFile(recording)) {
      while (input.hasMoreEvents()) {
        RecordedEvent event = input.readEvent();
        String eventType = event.getEventType().getName();
        eventTypes.add(eventType);
        String signal = signal(eventType);
        if (signal == null) {
          continue;
        }
        SignalStats stats = signals.get(signal);
        stats.events++;
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) {
          continue;
        }
        stats.eventsWithStack++;
        boolean libraryStack = false;
        List<String> libraryPath = new ArrayList<>();
        for (RecordedFrame frame : stack.getFrames()) {
          RecordedMethod method = frame.getMethod();
          // Some JFR recordings contain synthetic or VM frames without Java method metadata.
          if (method == null || method.getType() == null) {
            continue;
          }
          String owner = method.getType().getName();
          String methodKey = owner + "." + method.getName() + method.getDescriptor();
          if (owner.startsWith(libraryPrefix)) {
            libraryStack = true;
            stats.libraryFrames++;
            stats.libraryMethods.merge(methodKey, 1, Integer::sum);
            allLibraryMethods.merge(methodKey, 1, Integer::sum);
            if (libraryPath.size() < 10) {
              int separator = owner.lastIndexOf('.');
              libraryPath.add(owner.substring(separator + 1) + "." + method.getName());
            }
          }
          if (entryEvidence.selected.contains(methodKey)) {
            stats.selectedMethods.merge(methodKey, 1, Integer::sum);
          }
        }
        if (libraryStack) {
          stats.eventsWithLibraryStack++;
          stats.libraryPaths.merge(String.join(" ← ", libraryPath), 1, Integer::sum);
        }
      }
    }

    Set<String> sampledSelected = new LinkedHashSet<>();
    for (SignalStats stats : signals.values()) {
      sampledSelected.addAll(stats.selectedMethods.keySet());
    }
    Set<String> outsideInventory = new LinkedHashSet<>(allLibraryMethods.keySet());
    outsideInventory.removeAll(entryEvidence.selected);
    Set<String> sampledWithEntryEvidence = new LinkedHashSet<>(sampledSelected);
    sampledWithEntryEvidence.retainAll(entryEvidence.observed);
    Set<String> sampledWithoutEntryEvidence = new LinkedHashSet<>(sampledSelected);
    sampledWithoutEntryEvidence.removeAll(entryEvidence.observed);
    Set<String> datadogEvents = new LinkedHashSet<>();
    for (String eventType : eventTypes) {
      if (eventType.startsWith("datadog.")) {
        datadogEvents.add(eventType);
      }
    }
    return new Analysis(
        entryEvidence,
        signals,
        allLibraryMethods,
        sampledSelected,
        sampledWithEntryEvidence,
        sampledWithoutEntryEvidence,
        outsideInventory,
        datadogEvents);
  }

  private static String signal(String eventType) {
    switch (eventType) {
      case "jdk.ExecutionSample":
      case "jdk.NativeMethodSample":
        return "cpu";
      case "jdk.ObjectAllocationSample":
        return "allocation";
      case "jdk.ThreadPark":
      case "jdk.JavaMonitorWait":
      case "jdk.SocketRead":
        return "blocking";
      default:
        return null;
    }
  }

  private static JsonArray methodCounts(Map<String, Integer> methods) {
    JsonArray values = new JsonArray();
    methods.entrySet().stream()
        .sorted(
            Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
        .forEach(
            entry -> {
              JsonObject value = new JsonObject();
              value.addProperty("method", entry.getKey());
              value.addProperty("stackOccurrences", entry.getValue());
              values.add(value);
            });
    return values;
  }

  private static JsonArray pathCounts(Map<String, Integer> paths) {
    JsonArray values = new JsonArray();
    paths.entrySet().stream()
        .sorted(
            Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(12)
        .forEach(
            entry -> {
              JsonObject value = new JsonObject();
              value.addProperty("path", entry.getKey());
              value.addProperty("samples", entry.getValue());
              values.add(value);
            });
    return values;
  }

  private static final class SignalStats {
    int events;
    int eventsWithStack;
    int eventsWithLibraryStack;
    int libraryFrames;
    final Map<String, Integer> libraryMethods = new TreeMap<>();
    final Map<String, Integer> selectedMethods = new TreeMap<>();
    final Map<String, Integer> libraryPaths = new TreeMap<>();

    JsonObject toJson() {
      JsonObject value = new JsonObject();
      value.addProperty("events", events);
      value.addProperty("eventsWithStack", eventsWithStack);
      value.addProperty("eventsWithLibraryStack", eventsWithLibraryStack);
      value.addProperty("libraryFrames", libraryFrames);
      value.addProperty("distinctLibraryMethods", libraryMethods.size());
      value.addProperty("distinctSelectedMethods", selectedMethods.size());
      value.add("libraryMethods", methodCounts(libraryMethods));
      value.add("selectedMethods", methodCounts(selectedMethods));
      value.add("representativePaths", pathCounts(libraryPaths));
      return value;
    }
  }

  private record EntryEvidence(Set<String> selected, Set<String> observed) {}

  private record Analysis(
      EntryEvidence entryEvidence,
      Map<String, SignalStats> signals,
      Map<String, Integer> allLibraryMethods,
      Set<String> sampledSelected,
      Set<String> sampledWithEntryEvidence,
      Set<String> sampledWithoutEntryEvidence,
      Set<String> outsideInventory,
      Set<String> datadogEvents) {

    JsonObject toJson() {
      JsonObject root = new JsonObject();
      JsonObject comparison = new JsonObject();
      comparison.addProperty("selectedMethods", entryEvidence.selected.size());
      comparison.addProperty("entryObservedMethods", entryEvidence.observed.size());
      comparison.addProperty("profileSampledSelectedMethods", sampledSelected.size());
      comparison.addProperty("profileSampledWithEntryEvidence", sampledWithEntryEvidence.size());
      comparison.addProperty(
          "profileSampledWithoutEntryEvidence", sampledWithoutEntryEvidence.size());
      comparison.addProperty("profileSampledLibraryMethods", allLibraryMethods.size());
      comparison.addProperty("profileSampledOutsideInventory", outsideInventory.size());
      root.add("comparison", comparison);
      JsonObject signalValues = new JsonObject();
      signals.forEach((name, stats) -> signalValues.add(name, stats.toJson()));
      root.add("signals", signalValues);
      root.add("sampledLibraryMethods", methodCounts(allLibraryMethods));
      root.add("sampledWithoutEntryEvidence", GSON.toJsonTree(sampledWithoutEntryEvidence));
      root.add("outsideInventory", methodCounts(filter(allLibraryMethods, outsideInventory)));
      root.add("datadogEventTypes", GSON.toJsonTree(datadogEvents));
      root.addProperty("genericContextStateAvailable", false);
      root.addProperty(
          "contextNote",
          "Standard JFR stacks do not expose generic Context root/non-root state. "
              + "No Datadog context event was present in this recording.");
      return root;
    }

    String toMarkdown(Path recording, String libraryPrefix) {
      StringBuilder out = new StringBuilder();
      out.append("# Profiling coverage experiment\n\n");
      out.append("Recording: `").append(recording).append("`\n\n");
      out.append("Library prefix: `").append(libraryPrefix).append("`\n\n");
      out.append(
          "| Evidence | Distinct selected methods | Distinct library methods | Events with library stack |\n");
      out.append("|---|---:|---:|---:|\n");
      out.append("| Exact method entry | ")
          .append(entryEvidence.observed.size())
          .append(" / ")
          .append(entryEvidence.selected.size())
          .append(" | — | — |\n");
      signals.forEach(
          (name, stats) ->
              out.append("| ")
                  .append(name)
                  .append(" profile | ")
                  .append(stats.selectedMethods.size())
                  .append(" / ")
                  .append(entryEvidence.selected.size())
                  .append(" | ")
                  .append(stats.libraryMethods.size())
                  .append(" | ")
                  .append(stats.eventsWithLibraryStack)
                  .append(" / ")
                  .append(stats.events)
                  .append(" |\n"));
      out.append("\nAcross all profile signals, ")
          .append(sampledSelected.size())
          .append(" selected methods and ")
          .append(allLibraryMethods.size())
          .append(" library methods appeared in stacks; ")
          .append(outsideInventory.size())
          .append(
              " library methods were outside the declared inventory. Absence from a sampled stack ")
          .append("does not mean a method did not execute.\n\n");
      out.append(sampledWithoutEntryEvidence.size())
          .append(
              " selected methods appeared in profile stacks without entry evidence. The profile covers "
                  + "the whole test JVM, while entry collection is limited to test feature windows, so "
                  + "these are inventory or collection-window candidates rather than contradictions.\n\n");
      out.append("## Context attribution\n\n");
      if (datadogEvents.isEmpty()) {
        out.append(
            "No `datadog.*` JFR event types were present. Standard JFR samples cannot distinguish "
                + "root from non-root generic Context.\n");
      } else {
        out.append("Datadog event types present: ").append(datadogEvents).append(".\n");
      }
      return out.toString();
    }

    private static Map<String, Integer> filter(
        Map<String, Integer> values, Set<String> selectedKeys) {
      Map<String, Integer> filtered = new TreeMap<>();
      for (String key : selectedKeys) {
        filtered.put(key, values.get(key));
      }
      return filtered;
    }
  }
}
