package datadog.buildlogic.inlineofduty;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Conservative offline HotSpot evidence analysis. No workload or performance inference is made. */
public final class LogAnalyzer {
  private static final Map<String, String> PRIMITIVES =
      Map.of(
          "void", "V", "boolean", "Z", "byte", "B", "char", "C", "short", "S", "int", "I", "long",
          "J", "float", "F", "double", "D");
  private static final Set<String> PROHIBITIONS =
      Set.of("don't inline by annotation", "disallowed by CompileCommand");
  private static final List<String> STATES = List.of("inlined", "mixed", "refused", "uncertain");
  private static final Map<String, Integer> RANKS =
      Map.of(
          "repeated-refusal",
          0,
          "single-refusal",
          1,
          "below-threshold-refusal",
          1,
          "mixed",
          2,
          "uncertain",
          3,
          "ineligible-refusal",
          4,
          "unknown",
          5,
          "observed-success",
          6);

  private LogAnalyzer() {}

  /** Analyze unique log contents, keeping persistence findings local to each JVM log. */
  public static Map<String, Object> analyze(
      Path input, int minCompilations, boolean includeTestCode) throws IOException {
    if (minCompilations < 2) {
      throw new IllegalArgumentException("minCompilations must be at least 2");
    }
    Path source = input.toAbsolutePath().normalize();
    Path base = Files.isDirectory(source) ? source : source.getParent();
    List<Path> logs;
    if (Files.isDirectory(source)) {
      try (Stream<Path> paths = Files.walk(source)) {
        logs =
            paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("hotspot.*\\.log"))
                .sorted()
                .toList();
      }
    } else {
      logs = Files.isRegularFile(source) ? List.of(source) : List.of();
    }
    if (logs.isEmpty()) {
      throw new IOException("No hotspot*.log files found in " + source);
    }
    List<Map<String, Object>> captures = new ArrayList<>();
    List<Map<String, Object>> duplicates = new ArrayList<>();
    Map<String, String> hashes = new HashMap<>();
    Map<String, Group> groups = new TreeMap<>();
    for (Path log : logs) {
      String label = slash(base.relativize(log));
      String hash = hash(log);
      if (hashes.containsKey(hash)) {
        duplicates.add(map("ignored", label, "kept", hashes.get(hash)));
        continue;
      }
      hashes.put(hash, label);
      Parsed parsed = parse(log, label);
      parsed.capture.put("sha256", hash);
      captures.add(parsed.capture);
      for (Task task : parsed.tasks) {
        for (int i = 0; i < task.decisions.size(); i++) {
          Map<String, Object> decision = task.decisions.get(i);
          String callee = str(decision.get("callee"));
          if (!(callee.startsWith("datadog.") || callee.startsWith("com.datadog."))) {
            continue;
          }
          if (!includeTestCode
              && (callee.startsWith("datadog.trace.agent.test.")
                  || callee.startsWith("datadog.trace.test.")
                  || callee.contains(".$jacocoInit("))) {
            continue;
          }
          Map<String, Object> context =
              map(
                  "root",
                  task.root,
                  "compiler",
                  task.compiler,
                  "compile_kind",
                  task.kind,
                  "osr_bci",
                  task.osrBci,
                  "log",
                  label);
          for (String key : List.of("ancestry", "caller", "bci", "callee")) {
            context.put(key, decision.get(key));
          }
          Group group = groups.computeIfAbsent(json(context), ignored -> new Group(context));
          Map<String, Object> evidence = new TreeMap<>(decision);
          evidence.putAll(
              map(
                  "compile_id",
                  task.id,
                  "eligible",
                  task.eligible,
                  "installed",
                  task.installed,
                  "task_success",
                  task.success,
                  "uncertainty",
                  new ArrayList<>(task.uncertainty),
                  "task_stamp",
                  task.stamp,
                  "installed_stamps",
                  task.installedStamps,
                  "decision_index",
                  i,
                  "reason_category",
                  reasonCategory(str(decision.get("reason")), str(decision.get("outcome"))),
                  "evidence",
                  label + "#compile=" + task.id + "/decision=" + i));
          // Repeated raw messages in one compilation/context are a single observation.
          String identity =
              json(
                  map(
                      "compile_id",
                      task.id,
                      "outcome",
                      evidence.get("outcome"),
                      "reason",
                      evidence.get("reason"),
                      "eligible",
                      task.eligible,
                      "task_success",
                      task.success,
                      "uncertainty",
                      evidence.get("uncertainty")));
          if (group.seen.add(identity)) {
            group.decisions.add(evidence);
          }
        }
      }
    }
    List<Map<String, Object>> findings = new ArrayList<>();
    for (Group group : groups.values()) {
      group.decisions.sort(LogAnalyzer::compareHistory);
      Set<String> refusals = new HashSet<>();
      Set<String> successes = new HashSet<>();
      boolean hasRefusal = false;
      boolean uncertain = false;
      for (Map<String, Object> decision : group.decisions) {
        String outcome = str(decision.get("outcome"));
        hasRefusal |= outcome.equals("refusal");
        uncertain |=
            !list(decision.get("uncertainty")).isEmpty()
                || outcome.equals("unknown")
                || outcome.equals("intrinsic");
        if (yes(decision, "eligible") && outcome.equals("refusal")) {
          refusals.add(str(decision.get("compile_id")));
        }
        if (outcome.equals("success")) {
          successes.add(str(decision.get("compile_id")));
        }
      }
      String classification;
      if (hasRefusal && !successes.isEmpty()) classification = "mixed";
      else if (hasRefusal && uncertain) classification = "uncertain";
      else if (refusals.size() >= minCompilations) classification = "repeated-refusal";
      else if (refusals.size() == 1) classification = "single-refusal";
      else if (!refusals.isEmpty()) classification = "below-threshold-refusal";
      else if (hasRefusal) classification = "ineligible-refusal";
      else if (!successes.isEmpty()) classification = "observed-success";
      else classification = "unknown";
      Map<String, Object> finding = new TreeMap<>(group.context);
      finding.putAll(
          map(
              "classification",
              classification,
              "identity_confidence",
              "symbol-context-only; defining class loader unresolved",
              "eligible_refusal_compilations",
              refusals.size(),
              "observed_success_compilations",
              successes.size(),
              "decisions",
              group.decisions));
      findings.add(finding);
    }
    findings.sort(
        Comparator.<Map<String, Object>>comparingInt(f -> RANKS.get(str(f.get("classification"))))
            .thenComparing(f -> str(f.get("log")))
            .thenComparing(f -> str(f.get("root")))
            .thenComparing(f -> str(f.get("callee")))
            .thenComparing(LogAnalyzer::json));
    return report(captures, duplicates, findings, minCompilations, includeTestCode);
  }

  /** A fresh zero-worker report for skipped or NO-SOURCE test tasks. */
  public static Map<String, Object> emptyReport() {
    return emptyReport(2, false);
  }

  /** Preserve configured policy even when this invocation starts no test workers. */
  public static Map<String, Object> emptyReport(int minCompilations, boolean includeTestCode) {
    if (minCompilations < 2)
      throw new IllegalArgumentException("minCompilations must be at least 2");
    return report(List.of(), List.of(), List.of(), minCompilations, includeTestCode);
  }

  private static Map<String, Object> report(
      List<Map<String, Object>> captures,
      List<Map<String, Object>> duplicates,
      List<Map<String, Object>> findings,
      int minCompilations,
      boolean includeTestCode) {
    for (int i = 0; i < findings.size(); i++) {
      findings.get(i).put("context_id", "context-" + i);
    }
    Map<String, Object> policy =
        map(
            "scope",
            "One XML log/process at a time; no cross-run persistence inference.",
            "eligibility",
            "Complete XML, task_done success=1, installed nmethod with compiler=c2.",
            "identity",
            "Root method, compiler, OSR entry, full inline ancestry and caller BCI.",
            "uncertainty",
            "Late/deferred inline and intrinsics exclude the task from persistence.",
            "actionability",
            "Repeated completed installed C2 refusals within one worker and full inline context; no success at that recorded site across selected captures. Counts are recurrence, not independent proofs or performance impact.",
            "interpretation",
            "Observed decisions only; no never-inline, call-frequency or performance claim.",
            "sources",
            "Source locations and defining class loaders are unresolved.",
            "manifests",
            "Adjacent JSON manifests remain artifacts; XML VM metadata is included here.",
            "aggregation",
            "Descriptive dataset union, not equivalent experiments or combined warmup.",
            "prefixes",
            List.of("datadog.", "com.datadog."),
            "min_compilations",
            minCompilations,
            "include_test_code",
            includeTestCode,
            "default_exclusions",
            List.of(
                "JaCoCo $jacocoInit helpers", "datadog.trace.agent.test.", "datadog.trace.test."));
    Map<String, Object> report =
        map(
            "schema_version",
            1,
            "policy",
            policy,
            "captures",
            captures,
            "duplicates",
            duplicates,
            "findings",
            findings);
    report.put("summary", summarize(report));
    report.put("actionable_findings", actionableCallSites(findings));
    return report;
  }

  /**
   * Positive observations can suppress a candidate; refusals from different contexts never pool.
   */
  private static List<Map<String, Object>> actionableCallSites(List<Map<String, Object>> findings) {
    Set<String> successfulSites = new HashSet<>();
    for (Map<String, Object> finding : findings) {
      if ((int) finding.get("observed_success_compilations") > 0) {
        successfulSites.add(recordedCallSite(finding));
      }
    }
    List<Map<String, Object>> candidates =
        findings.stream()
            .filter(finding -> "repeated-refusal".equals(finding.get("classification")))
            .filter(finding -> !successfulSites.contains(recordedCallSite(finding)))
            .sorted(
                Comparator.<Map<String, Object>>comparingInt(
                        finding -> (int) finding.get("eligible_refusal_compilations"))
                    .reversed()
                    .thenComparing(finding -> str(finding.get("log")))
                    .thenComparing(finding -> str(finding.get("root")))
                    .thenComparing(finding -> str(finding.get("caller")))
                    .thenComparing(finding -> numericId(finding.get("bci")))
                    .thenComparing(LogAnalyzer::json))
            .toList();
    for (Map<String, Object> finding : candidates) {
      finding.put("actionable_label", "Persistently refused in this workload");
    }
    return candidates;
  }

  /**
   * Veto matching positive sites across workers/tiers without merging their compilation histories.
   */
  static String recordedCallSite(Map<String, Object> finding) {
    return json(
        map(
            "root",
            finding.get("root"),
            "ancestry",
            finding.get("ancestry"),
            "caller",
            finding.get("caller"),
            "bci",
            finding.get("bci"),
            "callee",
            finding.get("callee")));
  }

  private static Parsed parse(Path path, String label) throws IOException {
    List<Task> tasks = new ArrayList<>();
    Map<String, List<Map<String, String>>> installed = new HashMap<>();
    Map<String, Object> vm = new TreeMap<>();
    List<String> warnings = new ArrayList<>();
    Deque<Node> stack = new ArrayDeque<>();
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    // HotSpot escapes thousands of constructor names. Some JDK StAX builds count all predefined
    // escapes against the document's general-entity limit. No custom entity can expand here:
    // DTD events and entity references are rejected below, and external resolution is disabled.
    for (String limit : List.of("maxGeneralEntitySizeLimit", "totalEntitySizeLimit")) {
      String property = "http://www.oracle.com/xml/jaxp/properties/" + limit;
      if (factory.isPropertySupported(property)) factory.setProperty(property, "0");
    }
    factory.setXMLResolver(
        (publicId, systemId, baseUri, namespace) -> {
          throw new XMLStreamException("External entities are disabled");
        });
    boolean complete = true;
    boolean rootSeen = false;
    XMLStreamReader reader = null;
    try (InputStream source = Files.newInputStream(path)) {
      reader = factory.createXMLStreamReader(source);
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
          throw new XMLStreamException(
              "DTD and entity references are disabled", reader.getLocation());
        }
        if (event == XMLStreamConstants.START_ELEMENT) {
          String tag = reader.getLocalName();
          if (!rootSeen) {
            rootSeen = true;
            if (!tag.equals("hotspot_log")) {
              throw new XMLStreamException(
                  "Expected hotspot_log root; found " + tag, reader.getLocation());
            }
          }
          Map<String, String> attrs = new TreeMap<>();
          for (int i = 0; i < reader.getAttributeCount(); i++) {
            attrs.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
          }
          if (!stack.isEmpty()
              || tag.equals("task")
              || tag.equals("vm_version")
              || tag.equals("vm_arguments")) {
            Node node = new Node(tag, attrs);
            if (!stack.isEmpty()) stack.peek().children.add(node);
            stack.push(node);
          } else if (tag.equals("nmethod")) {
            installed
                .computeIfAbsent(attrs.get("compile_id"), ignored -> new ArrayList<>())
                .add(attrs);
          }
        } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
          if (!stack.isEmpty() && !stack.peekLast().tag.equals("task"))
            stack.peek().text.append(reader.getText());
        } else if (event == XMLStreamConstants.END_ELEMENT && !stack.isEmpty()) {
          Node node = stack.pop();
          if (stack.isEmpty()) {
            if (node.tag.equals("task")) tasks.add(task(node));
            else {
              Map<String, Object> values = new TreeMap<>();
              for (Node child : node.children) values.put(child.tag, child.text.toString().trim());
              vm.put(node.tag, values);
            }
          }
        }
      }
      if (!rootSeen) throw new XMLStreamException("Missing hotspot_log root");
    } catch (XMLStreamException exc) {
      complete = false;
      warnings.add("Incomplete/unreadable XML: " + exc.getMessage());
      if (!stack.isEmpty() && stack.peekLast().tag.equals("task")) {
        Task partial = task(stack.peekLast());
        partial.success = false;
        partial.uncertainty.add("truncated-task");
        tasks.add(partial);
      }
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (XMLStreamException ignored) {
          /* Stream is already closed. */
        }
      }
    }
    for (Task task : tasks) {
      List<Map<String, String>> candidates =
          installed.getOrDefault(task.id, List.of()).stream()
              .filter(n -> n.getOrDefault("compile_kind", "normal").equals(task.kind))
              .filter(n -> Objects.equals(n.getOrDefault("osr_bci", task.osrBci), task.osrBci))
              .toList();
      Set<String> compilers = new TreeSet<>();
      Set<String> stamps = new TreeSet<>();
      for (Map<String, String> candidate : candidates) {
        compilers.add(candidate.getOrDefault("compiler", "unknown").toLowerCase(Locale.ROOT));
        if (candidate.containsKey("stamp")) stamps.add(candidate.get("stamp"));
      }
      task.compiler = compilers.size() == 1 ? compilers.iterator().next() : "unknown";
      task.installed = !candidates.isEmpty();
      task.installedStamps = new ArrayList<>(stamps);
      task.eligible =
          complete
              && task.success
              && task.installed
              && task.compiler.equals("c2")
              && task.uncertainty.isEmpty();
    }
    return new Parsed(
        map(
            "log",
            label,
            "complete",
            complete,
            "warnings",
            warnings,
            "vm",
            vm,
            "task_count",
            tasks.size()),
        tasks);
  }

  private static Task task(Node node) {
    Task task = new Task(node.attrs);
    Map<String, String> types = new HashMap<>();
    Map<String, Map<String, String>> methods = new HashMap<>();
    Deque<Node> pending = new ArrayDeque<>();
    pending.push(node);
    while (!pending.isEmpty()) {
      Node child = pending.pop();
      pending.addAll(child.children);
      if (child.tag.equals("type") || child.tag.equals("klass"))
        types.put(child.get("id"), child.get("name"));
      if (child.tag.equals("method")) methods.put(child.get("id"), child.attrs);
      if (List.of("late_inline", "inline_id", "intrinsic").contains(child.tag))
        task.uncertainty.add(child.tag);
      if (child.tag.equals("inline_fail")
          && contains(child.get("reason"), "late", "delay", "defer")) {
        task.uncertainty.add("deferred-inline");
      }
    }
    List<Node> parses = node.children.stream().filter(n -> n.tag.equals("parse")).toList();
    Symbol root =
        parses.isEmpty()
            ? new Symbol(node.attrs.getOrDefault("method", "unresolved-root"), false)
            : method(parses.get(0).get("method"), types, methods);
    task.root = root.name;
    for (int i = 0; i < parses.size(); i++) {
      if (i > 0) task.uncertainty.add("additional-top-level-parse");
      walk(parses.get(i), List.of(), root, task, types, methods);
    }
    task.success =
        node.children.stream()
            .anyMatch(n -> n.tag.equals("task_done") && "1".equals(n.get("success")));
    return task;
  }

  private static void walk(
      Node parse,
      List<Map<String, Object>> ancestry,
      Symbol root,
      Task task,
      Map<String, String> types,
      Map<String, Map<String, String>> methods) {
    Symbol caller = method(parse.get("method"), types, methods);
    Symbol call = null;
    String bci = null;
    Map<String, Object> accepted = null;
    for (Node node : parse.children) {
      switch (node.tag) {
        case "bc" -> {
          bci = node.get("bci");
          call = null;
          accepted = null;
        }
        case "call" -> {
          call = method(node.get("method"), types, methods);
          accepted = null;
        }
        case "inline_fail", "inline_success" -> {
          Symbol callee = call == null ? new Symbol("unresolved-call", false) : call;
          Map<String, Object> edge = map("caller", caller.name, "bci", bci, "callee", callee.name);
          String reason = node.attrs.getOrDefault("reason", "");
          boolean resolved = caller.resolved && callee.resolved && bci != null && root.resolved;
          String outcome = node.tag.equals("inline_success") ? "success" : "refusal";
          if (!resolved
              || (outcome.equals("refusal")
                  && contains(
                      reason, "native", "unresolved", "intrinsic", "late", "delay", "defer")))
            outcome = "unknown";
          Map<String, Object> decision = new TreeMap<>(edge);
          decision.putAll(
              map(
                  "ancestry",
                  ancestry,
                  "outcome",
                  outcome,
                  "reason",
                  reason,
                  "resolved",
                  resolved));
          task.decisions.add(decision);
          accepted = node.tag.equals("inline_success") ? edge : null;
        }
        case "intrinsic" -> {
          if (call != null)
            task.decisions.add(
                map(
                    "ancestry",
                    ancestry,
                    "caller",
                    caller.name,
                    "bci",
                    bci,
                    "callee",
                    call.name,
                    "outcome",
                    "intrinsic",
                    "reason",
                    node.attrs.getOrDefault("id", "intrinsic"),
                    "resolved",
                    call.resolved));
          accepted = null;
        }
        case "parse" -> {
          Symbol nested = method(node.get("method"), types, methods);
          List<Map<String, Object>> path = new ArrayList<>(ancestry);
          if (accepted != null && nested.name.equals(accepted.get("callee"))) path.add(accepted);
          else {
            task.uncertainty.add("unreconciled-parse");
            path.add(map("caller", caller.name, "bci", null, "callee", nested.name));
          }
          walk(node, path, root, task, types, methods);
          accepted = null;
        }
        default -> {
          /* Other compiler diagnostics do not establish inline outcomes. */
        }
      }
    }
  }

  private static Symbol method(
      String id, Map<String, String> types, Map<String, Map<String, String>> methods) {
    Map<String, String> attrs = methods.get(id);
    if (attrs == null) return new Symbol("unresolved-method:" + id, false);
    String holder = types.get(attrs.get("holder"));
    String result = descriptor(types.get(attrs.get("return")));
    boolean resolved = holder != null && result != null;
    StringBuilder args = new StringBuilder("(");
    String arguments = attrs.getOrDefault("arguments", "").trim();
    if (!arguments.isEmpty()) {
      for (String arg : arguments.split("\\s+")) {
        String desc = descriptor(types.get(arg));
        resolved &= desc != null;
        args.append(desc == null ? "?" : desc);
      }
    }
    args.append(')').append(result == null ? "?" : result);
    return new Symbol(
        (holder == null ? "unresolved-holder:" + attrs.get("holder") : holder).replace('/', '.')
            + "."
            + attrs.getOrDefault("name", "?")
            + args,
        resolved);
  }

  private static String descriptor(String name) {
    if (name == null) return null;
    if (PRIMITIVES.containsKey(name)) return PRIMITIVES.get(name);
    return name.startsWith("[") ? name.replace('.', '/') : "L" + name.replace('.', '/') + ";";
  }

  private static String reasonCategory(String reason, String outcome) {
    if (outcome.equals("intrinsic") || contains(reason, "intrinsic")) return "intrinsic";
    if (contains(reason, "late", "delay", "defer")) return "deferred";
    if (contains(reason, "native")) return "native";
    if (contains(reason, "no static binding", "virtual call")) return "unresolved-virtual-dispatch";
    if (contains(reason, "unresolved", "not loaded")) return "unresolved";
    if (outcome.equals("success")) return "inline-success";
    if (contains(reason, "too big", "size", "too large")) return "size-limit";
    if (contains(reason, "too deep", "recursive", "depth")) return "depth-limit";
    if (contains(reason, "cold", "not reached", "executed", "frequency")) return "profile-or-cold";
    if (contains(reason, "budget", "node", "cutoff")) return "budget-limit";
    return "other";
  }

  private static Map<String, Object> summarize(Map<String, Object> report) {
    Map<String, Boolean> complete = new TreeMap<>();
    for (Map<String, Object> capture : maps(report.get("captures")))
      complete.put(str(capture.get("log")), yes(capture, "complete"));
    Map<String, List<Map<String, Object>>> observations = new TreeMap<>();
    int context = 0;
    for (Map<String, Object> finding : maps(report.get("findings"))) {
      for (Map<String, Object> decision : maps(finding.get("decisions"))) {
        Map<String, Object> item = new TreeMap<>(decision);
        item.putAll(
            map(
                "_context",
                context,
                "_compiler",
                finding.get("compiler"),
                "_log",
                finding.get("log")));
        observations
            .computeIfAbsent(str(finding.get("callee")), ignored -> new ArrayList<>())
            .add(item);
      }
      context++;
    }
    List<Map<String, Object>> methods = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : observations.entrySet()) {
      Set<String> workers = new HashSet<>(),
          successful = new HashSet<>(),
          negative = new HashSet<>(),
          refusalWorkers = new HashSet<>();
      Set<String> reasons = new TreeSet<>();
      Map<String, Set<String>> perCompilation = new TreeMap<>();
      boolean success = false, refusal = false;
      boolean prohibited = complete.values().stream().allMatch(Boolean::booleanValue);
      for (Map<String, Object> d : entry.getValue()) {
        String log = str(d.get("_log"));
        String id = json(List.of(log, str(d.get("compile_id"))));
        boolean valid =
            complete.get(log)
                && yes(d, "installed")
                && yes(d, "task_success")
                && yes(d, "resolved");
        String outcome = valid ? str(d.get("outcome")) : "unknown";
        workers.add(log);
        if (outcome.equals("success")) {
          success = true;
          successful.add(id);
        }
        if (outcome.equals("refusal")) {
          refusal = true;
          reasons.add(str(d.get("reason")));
          if ("c2".equals(d.get("_compiler"))) {
            negative.add(id);
            refusalWorkers.add(log);
          }
        }
        prohibited &=
            valid
                && outcome.equals("refusal")
                && yes(d, "eligible")
                && PROHIBITIONS.contains(d.get("reason"));
        if (!outcome.equals("success") && !outcome.equals("refusal")) outcome = "unknown";
        String unit = json(List.of(log, str(d.get("compile_id")), d.get("_context")));
        perCompilation.computeIfAbsent(unit, ignored -> new HashSet<>()).add(outcome);
      }
      Map<String, Object> counts = map("success", 0, "refusal", 0, "mixed", 0, "unresolved", 0);
      for (Set<String> outcomes : perCompilation.values()) {
        String key =
            outcomes.contains("success")
                ? (outcomes.contains("refusal") ? "mixed" : "success")
                : (outcomes.contains("refusal") ? "refusal" : "unresolved");
        counts.put(key, (int) counts.get(key) + 1);
      }
      String state =
          success ? (refusal ? "mixed" : "inlined") : (refusal ? "refused" : "uncertain");
      methods.add(
          map(
              "method",
              entry.getKey(),
              "state",
              state,
              "explicit_prohibition",
              prohibited && refusal,
              "worker_count",
              workers.size(),
              "refusal_compilations",
              negative.size(),
              "refusal_workers",
              refusalWorkers.size(),
              "reasons",
              new ArrayList<>(reasons),
              "successful_compilations",
              successful.size(),
              "decision_counts",
              counts));
    }
    Map<String, Object> states = new TreeMap<>();
    for (String state : STATES) {
      long count = methods.stream().filter(m -> state.equals(m.get("state"))).count();
      states.put(
          state,
          map(
              "count",
              count,
              "percent",
              methods.isEmpty() ? 0.0 : Math.round(1000.0 * count / methods.size()) / 10.0));
    }
    List<Map<String, Object>> refusals =
        new ArrayList<>(methods.stream().filter(m -> "refused".equals(m.get("state"))).toList());
    refusals.sort(
        Comparator.<Map<String, Object>, Boolean>comparing(m -> yes(m, "explicit_prohibition"))
            .reversed()
            .thenComparing(
                Comparator.<Map<String, Object>>comparingInt(m -> (int) m.get("refusal_workers"))
                    .reversed())
            .thenComparing(
                Comparator.<Map<String, Object>>comparingInt(
                        m -> (int) m.get("refusal_compilations"))
                    .reversed())
            .thenComparing(m -> str(m.get("method"))));
    return map(
        "unit",
        "Distinct observed method signatures, not coverage or call frequency",
        "aggregation",
        "Descriptive union; workloads and JVM configurations are not equivalent experiments",
        "total_methods",
        methods.size(),
        "worker_count",
        complete.size(),
        "duplicate_logs_ignored",
        list(report.get("duplicates")).size(),
        "states",
        states,
        "methods",
        methods,
        "prohibitions",
        methods.stream().filter(m -> yes(m, "explicit_prohibition")).toList(),
        "other_refusals",
        refusals);
  }

  /** Write a compact overview and bounded evidence export. */
  public static void writeReports(Map<String, Object> report, Path input, Path output)
      throws IOException {
    writeReports(report, input, output, 10, false);
  }

  public static void writeReports(
      Map<String, Object> report, Path input, Path output, int top, boolean fullEvidence)
      throws IOException {
    if (top < 1) throw new IllegalArgumentException("inlineOfDutyTop must be at least 1");
    PipelineArtifacts artifacts = pipelineArtifacts(report);
    if (artifacts != null) {
      for (Map<String, Object> capture : maps(report.get("captures"))) {
        artifacts.url(str(capture.get("log")));
      }
    }
    String markdown = markdownSummary(report);
    report = exportView(report, top, fullEvidence);
    String overview = overview(report, artifacts);
    String evidence = details(report, input, output, artifacts);
    Files.createDirectories(output);
    Files.writeString(output.resolve("findings.json"), json(report) + "\n", StandardCharsets.UTF_8);
    Files.writeString(output.resolve("index.html"), overview, StandardCharsets.UTF_8);
    Files.writeString(output.resolve("details.html"), evidence, StandardCharsets.UTF_8);
    Files.writeString(output.resolve("summary.md"), markdown, StandardCharsets.UTF_8);
  }

  private static String markdownSummary(Map<String, Object> report) {
    Map<String, Object> summary = object(report.get("summary"));
    StringBuilder text = new StringBuilder("<!-- inline-of-duty -->\n**JIT Inlining Report**\n\n");
    text.append(summary.get("total_methods"))
        .append(" observed methods across ")
        .append(summary.get("worker_count"))
        .append(" test JVMs.\n\n")
        .append("| Category | Methods | % |\n| :-- | --: | --: |\n");
    Map<String, String> labels =
        Map.of(
            "inlined",
            "🟢 Inline seen",
            "mixed",
            "🟡 Mixed",
            "refused",
            "🔴 Refused only",
            "uncertain",
            "⚪ Insufficient evidence");
    for (String state : STATES) {
      Map<String, Object> count = object(object(summary.get("states")).get(state));
      text.append("| ")
          .append(labels.get(state))
          .append(" | ")
          .append(count.get("count"))
          .append(" | ")
          .append(count.get("percent"))
          .append("% |\n");
    }
    text.append("\n")
        .append(list(report.get("actionable_findings")).size())
        .append(" call contexts persistently refused in this workload.\n\n")
        .append(
            "Available captures only. Percentages describe observed method signatures, not coverage or performance; refused-only does not mean never inline.\n");
    Object samplePercent = object(report.get("policy")).get("sample_percent");
    if ("representative".equals(object(report.get("policy")).get("capture_scope"))) {
      text.append("Scope: JDK 25 base and instrumentation tests.\n");
    }
    if (samplePercent instanceof Number rate && rate.intValue() < 100) {
      text.append("Captured ")
          .append(rate)
          .append("% of selected Gradle Test tasks; the sample rotates by pipeline.\n");
    }
    if (!strings(report.getOrDefault("capture_errors", List.of())).isEmpty()
        || maps(report.get("captures")).stream().anyMatch(c -> !yes(c, "complete"))) {
      text.append("\n⚠️ Capture errors: results are partial; see the aggregate report.\n");
    }
    return text.toString();
  }

  private static Map<String, Object> exportView(
      Map<String, Object> source, int top, boolean fullEvidence) {
    Map<String, Object> result = new TreeMap<>(source);
    Map<String, Object> summary = new TreeMap<>(object(source.get("summary")));
    List<Map<String, Object>> candidates = maps(source.get("actionable_findings"));
    List<Map<String, Object>> selected = candidates.stream().limit(top).toList();
    result.put(
        "export",
        Map.of(
            "top",
            top,
            "full_evidence",
            fullEvidence,
            "actionable_contexts_total",
            candidates.size(),
            "contexts_total",
            source.getOrDefault("context_count", list(source.get("findings")).size()),
            "refused_methods_total",
            maps(summary.get("other_refusals")).size()));
    if (!fullEvidence) {
      List<Map<String, Object>> limited = new ArrayList<>();
      for (Map<String, Object> finding : selected) {
        Map<String, Object> copy = new TreeMap<>(finding);
        List<Map<String, Object>> decisions = maps(finding.get("decisions"));
        copy.put("decisions_total", finding.getOrDefault("decisions_total", decisions.size()));
        copy.put("decisions", decisions.stream().limit(top).toList());
        limited.add(copy);
      }
      result.put("actionable_findings", limited);
      result.put("findings", limited);
      summary.put(
          "other_refusals", maps(summary.get("other_refusals")).stream().limit(top).toList());
      summary.remove("methods");
      summary.remove("prohibitions");
      result.put("summary", summary);
      List<Map<String, Object>> captures = new ArrayList<>();
      for (Map<String, Object> capture : maps(source.get("captures"))) {
        Map<String, Object> copy = new TreeMap<>(capture);
        copy.remove("vm");
        captures.add(copy);
      }
      result.put("captures", captures);
    }
    return result;
  }

  private static PipelineArtifacts pipelineArtifacts(Map<String, Object> report) {
    Object value = report.get("pipeline_artifacts");
    if (value == null) return null;
    if (!(value instanceof Map<?, ?>))
      throw new IllegalArgumentException("Invalid pipeline_artifacts metadata");
    Map<String, Object> metadata = object(value);
    String pipeline = str(metadata.get("pipeline_id"));
    String prefix = str(metadata.get("source_capture_prefix"));
    if (!pipeline.matches("[0-9]+")
        || !prefix.equals("workspace/build/inline-of-duty/captures/" + pipeline)) {
      throw new IllegalArgumentException("Invalid pipeline artifact capture prefix or pipeline ID");
    }
    try {
      URI project = new URI(str(metadata.get("project_url")));
      if (!"https".equalsIgnoreCase(project.getScheme())
          || project.getHost() == null
          || project.getUserInfo() != null
          || project.getRawQuery() != null
          || project.getRawFragment() != null
          || project.getPort() < -1) {
        throw new IllegalArgumentException(
            "Pipeline project_url must be an HTTPS project URL without credentials, query or fragment");
      }
      String path = project.getPath();
      if (path == null || !path.startsWith("/"))
        throw new IllegalArgumentException("Missing pipeline project path");
      if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
      if (path.isEmpty()) throw new IllegalArgumentException("Missing pipeline project path");
      if (artifactPath(path.substring(1)).size() < 2)
        throw new IllegalArgumentException("Missing pipeline project namespace");
      String projectUrl =
          new URI("https", null, project.getHost(), project.getPort(), path, null, null)
              .toASCIIString();
      return new PipelineArtifacts(projectUrl, pipeline, prefix);
    } catch (URISyntaxException exc) {
      throw new IllegalArgumentException("Invalid pipeline project_url", exc);
    }
  }

  private static List<String> artifactPath(String path) {
    if (path.isEmpty()
        || path.startsWith("/")
        || path.indexOf('\\') >= 0
        || path.chars().anyMatch(c -> c < 32 || c == 127)) {
      throw new IllegalArgumentException("Invalid pipeline artifact path");
    }
    List<String> segments = List.of(path.split("/", -1));
    if (segments.stream().anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
      throw new IllegalArgumentException("Invalid pipeline artifact path segment");
    }
    return segments;
  }

  private record PipelineArtifacts(String projectUrl, String pipelineId, String prefix) {
    String url(String log) {
      List<String> segments = artifactPath(log);
      String job = segments.get(0);
      if (!job.matches("[0-9]+") || segments.size() < 2) {
        throw new IllegalArgumentException(
            "Pipeline capture log must start with its numeric producing job ID");
      }
      try {
        String artifact = new URI(null, null, prefix + "/" + log, null).toASCIIString();
        return projectUrl + "/-/jobs/" + job + "/artifacts/file/" + artifact;
      } catch (URISyntaxException exc) {
        throw new IllegalArgumentException("Invalid pipeline artifact path", exc);
      }
    }
  }

  private static final String STYLE =
      "body{font:16px/1.5 system-ui;color:#243043;background:#f6f8fb;margin:0}main{max-width:1100px;margin:48px auto;padding:0 24px}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin:28px 0}.card{background:white;border:1px solid #dce3ec;border-top:5px solid;padding:18px;border-radius:10px}.card span,.card strong,.card small{display:block}.card strong{font-size:38px}.inlined,.observed-success{border-top-color:#26915c}.mixed{border-top-color:#c78a14}.refused,.repeated-refusal{border-top-color:#cf4141}.uncertain{border-top-color:#8190a3}table{border-collapse:collapse;width:100%;background:white;font-size:14px;margin-top:16px}th,td{padding:12px;text-align:left;border-bottom:1px solid #e1e6ed;vertical-align:top;overflow-wrap:anywhere}details{margin-top:24px}summary{cursor:pointer;font-weight:600}.muted,small{color:#59677a}footer{margin-top:32px}.warning{color:#9c3030}@media(max-width:700px){.cards{grid-template-columns:repeat(2,1fr)}}";

  private static String page(String title, String body) {
    return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
        + escape(title)
        + "</title><style>"
        + STYLE
        + "</style></head><body><main>"
        + body
        + "</main></body></html>\n";
  }

  private static String overview(Map<String, Object> report, PipelineArtifacts artifacts) {
    Map<String, Object> summary = object(report.get("summary"));
    StringBuilder body =
        new StringBuilder("<h1>JIT Inlining Report</h1><p class=\"muted\">")
            .append(summary.get("total_methods"))
            .append(" observed methods · ")
            .append(summary.get("worker_count"))
            .append(" test JVMs</p>");
    if (artifacts != null) {
      Set<String> jobs = new TreeSet<>();
      for (Map<String, Object> capture : maps(report.get("captures"))) {
        jobs.add(artifactPath(str(capture.get("log"))).get(0));
      }
      body.append("<p class=\"muted\">Pipeline ")
          .append(escape(artifacts.pipelineId()))
          .append(" · ")
          .append(jobs.size())
          .append(
              " captured test jobs. Includes available captures; jobs without artifacts are not represented.</p>");
    }
    Object samplePercent = object(report.get("policy")).get("sample_percent");
    if ("representative".equals(object(report.get("policy")).get("capture_scope"))) {
      body.append("<p class=\"muted\">Scope: JDK 25 base and instrumentation tests.</p>");
    }
    if (samplePercent instanceof Number rate && rate.intValue() < 100) {
      body.append("<p class=\"muted\">Captured ")
          .append(rate)
          .append("% of selected Gradle Test tasks; the sample rotates by pipeline.</p>");
    }
    long incomplete =
        maps(report.get("captures")).stream().filter(c -> !yes(c, "complete")).count();
    if (incomplete > 0)
      body.append("<p class=\"warning\">")
          .append(incomplete)
          .append(" incomplete capture(s): results are partial.</p>");
    for (String error : strings(report.getOrDefault("capture_errors", List.of()))) {
      body.append("<p class=\"warning\">").append(escape(error)).append("</p>");
    }
    body.append("<div class=\"cards\">");
    Map<String, String> labels =
        Map.of(
            "inlined",
            "Inline seen",
            "mixed",
            "Mixed",
            "refused",
            "Refused only",
            "uncertain",
            "Insufficient evidence");
    for (String state : STATES) {
      Map<String, Object> count = object(object(summary.get("states")).get(state));
      body.append("<div class=\"card ")
          .append(state)
          .append("\"><span>")
          .append(labels.get(state))
          .append("</span><strong>")
          .append(count.get("count"))
          .append("</strong><small>")
          .append(count.get("percent"))
          .append("%</small></div>");
    }
    body.append(
        "</div><p class=\"muted\">Each method signature is counted once across selected captures. Percentages describe observed methods, not test coverage or call frequency.</p><h2>Persistently refused in this workload</h2>");
    List<Map<String, Object>> others = maps(summary.get("other_refusals"));
    List<Map<String, Object>> candidates = maps(report.get("actionable_findings"));
    int top = ((Number) object(report.get("export")).get("top")).intValue();
    List<Map<String, Object>> shortlist = candidates.stream().limit(top).toList();
    body.append(
        shortlist.isEmpty()
            ? "<p>No call sites meet the persistence criteria in this workload.</p>"
            : callSiteTable(shortlist));
    body.append(
        "<p class=\"muted\">Showing up to "
            + top
            + " call contexts with repeated completed, installed C2 refusals in one capture and no inline success observed at that recorded site. Caller, bytecode index and full inline chain must match. Counts show recurrence, not independent proofs or performance impact.</p>");
    if (!others.isEmpty())
      body.append("<details><summary>Methods with refusals only (showing ")
          .append(others.size())
          .append(" of ")
          .append(object(report.get("export")).get("refused_methods_total"))
          .append(
              " methods)</summary><p>Method-wide recurrence is diagnostic context, not an actionable finding or a claim that a method cannot inline. Counts span observed contexts and include C1 and C2; they are not independent proofs or a measure of performance impact.</p>")
          .append(methodTable(others))
          .append("</details>");
    body.append(
            "<details><summary>How to read the totals</summary><p><b>Inline seen:</b> supported success without supported refusal. <b>Mixed:</b> both successes and refusals; they may occur in the same compilation. <b>Refused only:</b> supported refusals without supported success. <b>Insufficient evidence:</b> no supported success or refusal. C1 and C2 observations are included. Outcomes describe observed attempts, not a final lifetime decision.</p><p>Green means successful inline observed, not performance proof. Symbols and defining class loaders remain unresolved beyond recorded signatures. Different JVMs are a descriptive union, not equivalent workloads or a combined warmup history. Test harness and JaCoCo helpers are excluded by default. Copied logs ignored: ")
        .append(summary.get("duplicate_logs_ignored"))
        .append(
            ".</p></details><footer><a href=\"findings.json\">JSON</a> · <a href=\"details.html\">Detailed evidence</a></footer>");
    return page("JIT Inlining Report", body.toString());
  }

  private static String shortMethod(String symbol) {
    int descriptor = symbol.indexOf('(');
    String name = descriptor >= 0 ? symbol.substring(0, descriptor) : symbol;
    int split = name.lastIndexOf('.', name.lastIndexOf('.') - 1);
    return name.substring(split + 1);
  }

  private static String inlinePath(Map<String, Object> finding, boolean compact) {
    List<String> path = new ArrayList<>();
    for (Map<String, Object> edge : maps(finding.get("ancestry"))) {
      String caller = str(edge.get("caller"));
      path.add((compact ? shortMethod(caller) : caller) + "@" + edge.get("bci"));
    }
    String caller = str(finding.get("caller"));
    String callee = str(finding.get("callee"));
    path.add((compact ? shortMethod(caller) : caller) + "@" + finding.get("bci"));
    path.add(compact ? shortMethod(callee) : callee);
    return String.join(" → ", path);
  }

  private static String displayReasons(Collection<String> reasons) {
    Set<String> labels = new TreeSet<>();
    for (String reason : reasons) {
      labels.add(
          "unresolved-virtual-dispatch".equals(reasonCategory(reason, "refusal"))
              ? "Unresolved virtual dispatch"
              : reason);
    }
    return String.join("; ", labels);
  }

  private static String callSiteTable(List<Map<String, Object>> findings) {
    StringBuilder table =
        new StringBuilder(
            "<table><thead><tr><th>Call site / inline context</th><th>Observed reason</th><th>Completed C2 refusals</th><th>Capture</th></tr></thead><tbody>");
    for (Map<String, Object> finding : findings) {
      Set<String> reasons = new TreeSet<>();
      for (Map<String, Object> decision : maps(finding.get("decisions"))) {
        if (yes(decision, "eligible") && "refusal".equals(decision.get("outcome"))) {
          reasons.add(str(decision.get("reason")));
        }
      }
      String log = str(finding.get("log"));
      List<String> components = List.of(log.split("/"));
      String shortLog =
          String.join(
              "/", components.subList(Math.max(0, components.size() - 3), components.size()));
      String kind = str(finding.get("compile_kind"));
      table
          .append("<tr><td><a href=\"details.html#")
          .append(escape(finding.get("context_id")))
          .append("\" title=\"")
          .append(escape(inlinePath(finding, false)))
          .append("\"><code>")
          .append(escape(inlinePath(finding, true)))
          .append("</code></a><br><small>")
          .append(escape(finding.get("actionable_label")))
          .append("</small></td><td>")
          .append(escape(displayReasons(reasons)))
          .append("</td><td>")
          .append(finding.get("eligible_refusal_compilations"))
          .append("</td><td title=\"")
          .append(escape(log))
          .append("\">")
          .append(escape(shortLog))
          .append("<br><small>")
          .append(escape(kind));
      if ("osr".equals(kind)) table.append("@").append(escape(finding.get("osr_bci")));
      table.append("</small></td></tr>");
    }
    return table.append("</tbody></table>").toString();
  }

  private static String methodTable(List<Map<String, Object>> methods) {
    StringBuilder result =
        new StringBuilder(
            "<table><thead><tr><th>Method</th><th>Observed reason</th><th>C2 refusal compilations</th><th>C2 captures</th></tr></thead><tbody>");
    for (Map<String, Object> method : methods) {
      String symbol = str(method.get("method"));
      String shortName = shortMethod(symbol);
      Map<String, Object> counts = object(method.get("decision_counts"));
      result
          .append("<tr><td><code title=\"")
          .append(escape(symbol))
          .append("\">")
          .append(escape(shortName))
          .append("</code>");
      if (yes(method, "explicit_prohibition"))
        result.append("<br><b>Explicit policy prohibition</b>");
      result
          .append("<br><small>No inlining observed: ")
          .append(counts.get("refusal"))
          .append(" refusal observations across captured contexts.</small></td><td>")
          .append(escape(displayReasons(strings(method.get("reasons")))))
          .append("</td><td>")
          .append(method.get("refusal_compilations"))
          .append("</td><td>")
          .append(method.get("refusal_workers"))
          .append("</td></tr>");
    }
    return result.append("</tbody></table>").toString();
  }

  private static String details(
      Map<String, Object> report, Path input, Path output, PipelineArtifacts artifacts)
      throws IOException {
    Path base = input.toAbsolutePath().normalize();
    if (!Files.isDirectory(base)) base = base.getParent();
    StringBuilder body =
        new StringBuilder(
            "<h1>JIT Inlining Evidence</h1><p>Repeated refusal means distinct completed, installed C2 compilations in one log and full inline context. Source locations and defining class loaders remain unresolved. Late, deferred and intrinsic tasks cannot establish persistence. Outcomes do not prove performance impact or never inlining.</p>");
    body.append("<p>Export includes ")
        .append(list(report.get("findings")).size())
        .append(" of ")
        .append(object(report.get("export")).get("contexts_total"))
        .append(
            " contexts. Totals use all captures. Compact exports retain only the top qualified contexts and limited decision samples; omitted evidence remains in raw logs. Use -PinlineOfDutyFullEvidence=true to export all evidence.</p>");
    body.append("<details><summary>Worker captures</summary><ul>");
    for (Map<String, Object> capture : maps(report.get("captures"))) {
      body.append("<li><a href=\"")
          .append(escape(evidenceLink(str(capture.get("log")), base, output, artifacts)))
          .append("\">")
          .append(escape(capture.get("log")))
          .append("</a></li>");
      for (String warning : strings(capture.get("warnings")))
        body.append("<li class=\"warning\">")
            .append(escape(capture.get("log")))
            .append(": ")
            .append(escape(warning))
            .append("</li>");
    }
    body.append("</ul></details>");
    body.append("<p>")
        .append(list(report.get("findings")).size())
        .append(
            " context(s)</p><table><thead><tr><th>Classification</th><th>Root / compiler</th><th>Inline context / callee</th><th>Eligible refusal compilations</th><th>Evidence</th></tr></thead><tbody>");
    for (Map<String, Object> finding : maps(report.get("findings"))) {
      String raw = evidenceLink(str(finding.get("log")), base, output, artifacts);
      body.append("<tr id=\"")
          .append(escape(finding.get("context_id")))
          .append("\" class=\"")
          .append(escape(finding.get("classification")))
          .append("\"><td>")
          .append(escape(finding.get("classification")))
          .append("</td><td>")
          .append(escape(finding.get("root")))
          .append("<br>")
          .append(escape(finding.get("compiler")))
          .append(" / ")
          .append(escape(finding.get("compile_kind")))
          .append(" / OSR BCI ")
          .append(escape(finding.get("osr_bci")))
          .append("</td><td>");
      for (Map<String, Object> edge : maps(finding.get("ancestry")))
        body.append(escape(edge.get("caller")))
            .append('@')
            .append(escape(edge.get("bci")))
            .append(" → ");
      body.append(escape(finding.get("caller")))
          .append('@')
          .append(escape(finding.get("bci")))
          .append("<br>→ ")
          .append(escape(finding.get("callee")))
          .append("</td><td>")
          .append(finding.get("eligible_refusal_compilations"))
          .append("</td><td><a href=\"")
          .append(escape(raw))
          .append("\">")
          .append(escape(finding.get("log")))
          .append("</a><details><summary>")
          .append(list(finding.get("decisions")).size());
      int totalDecisions =
          ((Number) finding.getOrDefault("decisions_total", list(finding.get("decisions")).size()))
              .intValue();
      if (totalDecisions > list(finding.get("decisions")).size()) {
        body.append(" of ").append(totalDecisions).append(" sampled decision(s)</summary><ul>");
      } else {
        body.append(" observed decision(s)</summary><ul>");
      }
      for (Map<String, Object> d : maps(finding.get("decisions")))
        body.append("<li>Compilation ")
            .append(escape(d.get("compile_id")))
            .append(": ")
            .append(escape(d.get("outcome")))
            .append(" — ")
            .append(escape(d.get("reason")))
            .append(" [")
            .append(escape(d.get("reason_category")))
            .append("]; task stamp=")
            .append(escape(d.get("task_stamp")))
            .append("; installed stamps=")
            .append(escape(d.get("installed_stamps")))
            .append("; eligible=")
            .append(d.get("eligible"))
            .append("; uncertainty=")
            .append(escape(d.get("uncertainty")))
            .append("</li>");
      body.append("</ul></details></td></tr>");
    }
    return page("JIT Inlining Evidence", body.append("</tbody></table>").toString());
  }

  private static String evidenceLink(
      String log, Path base, Path output, PipelineArtifacts artifacts) throws IOException {
    if (artifacts != null) return artifacts.url(log);
    try {
      return new URI(
              null,
              null,
              slash(output.toAbsolutePath().normalize().relativize(base.resolve(log))),
              null)
          .toASCIIString();
    } catch (URISyntaxException failure) {
      throw new IOException("Cannot encode raw log link", failure);
    }
  }

  /**
   * Encode supported manifest/report values deterministically without a JSON runtime dependency.
   */
  public static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof Boolean || value instanceof Number) return value.toString();
    if (value instanceof Map<?, ?> values) {
      Map<String, Object> sorted = new TreeMap<>();
      values.forEach((k, v) -> sorted.put(k.toString(), v));
      List<String> entries = new ArrayList<>();
      sorted.forEach((k, v) -> entries.add(json(k) + ":" + json(v)));
      return "{" + String.join(",", entries) + "}";
    }
    if (value instanceof Collection<?> values)
      return "[" + String.join(",", values.stream().map(LogAnalyzer::json).toList()) + "]";
    StringBuilder out = new StringBuilder("\"");
    for (char c : value.toString().toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 32 || c > 126) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    return out.append('"').toString();
  }

  private static int compareHistory(Map<String, Object> a, Map<String, Object> b) {
    int compared = stamp(a.get("task_stamp")).compareTo(stamp(b.get("task_stamp")));
    if (compared == 0)
      compared = numericId(a.get("compile_id")).compareTo(numericId(b.get("compile_id")));
    if (compared == 0) compared = str(a.get("compile_id")).compareTo(str(b.get("compile_id")));
    if (compared == 0)
      compared = Integer.compare((int) a.get("decision_index"), (int) b.get("decision_index"));
    return compared == 0 ? json(a).compareTo(json(b)) : compared;
  }

  private static BigDecimal stamp(Object value) {
    try {
      return new BigDecimal(str(value));
    } catch (NumberFormatException exc) {
      return BigDecimal.valueOf(Long.MAX_VALUE);
    }
  }

  private static BigInteger numericId(Object value) {
    try {
      return new BigInteger(str(value));
    } catch (NumberFormatException exc) {
      return BigInteger.valueOf(Long.MAX_VALUE);
    }
  }

  private static String hash(Path path) throws IOException {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(path)) {
        byte[] bytes = new byte[64 * 1024];
        int length;
        while ((length = input.read(bytes)) != -1) hash.update(bytes, 0, length);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException exc) {
      throw new IllegalStateException("JVM lacks SHA-256", exc);
    }
  }

  private static String escape(Object value) {
    return str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static boolean contains(String value, String... words) {
    String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
    for (String word : words) if (lower.contains(word)) return true;
    return false;
  }

  private static boolean yes(Map<String, Object> value, String key) {
    return Boolean.TRUE.equals(value.get(key));
  }

  private static String str(Object value) {
    return String.valueOf(value);
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static Map<String, Object> map(Object... entries) {
    Map<String, Object> value = new TreeMap<>();
    for (int i = 0; i < entries.length; i += 2) value.put((String) entries[i], entries[i + 1]);
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    return (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object value) {
    return (List<String>) value;
  }

  private record Symbol(String name, boolean resolved) {}

  private record Parsed(Map<String, Object> capture, List<Task> tasks) {}

  private static final class Node {
    final String tag;
    final Map<String, String> attrs;
    final List<Node> children = new ArrayList<>();
    final StringBuilder text = new StringBuilder();

    Node(String tag, Map<String, String> attrs) {
      this.tag = tag;
      this.attrs = attrs;
    }

    String get(String key) {
      return attrs.get(key);
    }
  }

  private static final class Task {
    final String id, kind, osrBci, stamp;
    String root, compiler = "unknown";
    boolean success, installed, eligible;
    List<String> installedStamps = List.of();
    final Set<String> uncertainty = new TreeSet<>();
    final List<Map<String, Object>> decisions = new ArrayList<>();

    Task(Map<String, String> attrs) {
      id = attrs.getOrDefault("compile_id", "?");
      kind = attrs.getOrDefault("compile_kind", "normal");
      osrBci = attrs.get("osr_bci");
      stamp = attrs.get("stamp");
    }
  }

  private static final class Group {
    final Map<String, Object> context;
    final List<Map<String, Object>> decisions = new ArrayList<>();
    final Set<String> seen = new HashSet<>();

    Group(Map<String, Object> context) {
      this.context = context;
    }
  }
}
