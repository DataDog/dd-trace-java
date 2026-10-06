package datadog.trace.agent.test.coverage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Extracts a lossless declared-call graph and deterministic structural views from library jars. */
public final class LibraryGraphAnalyzer {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Set<String> OBJECT_METHODS =
      Set.of(
          "toString()Ljava/lang/String;",
          "hashCode()I",
          "equals(Ljava/lang/Object;)Z",
          "clone()Ljava/lang/Object;",
          "finalize()V");

  private LibraryGraphAnalyzer() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException("Usage: <output-directory> <jar> [<jar> ...]");
    }
    Path output = Path.of(args[0]);
    Files.createDirectories(output);

    Graph graph = new Graph();
    for (int i = 1; i < args.length; i++) {
      graph.scan(Path.of(args[i]));
    }
    graph.finish();
    Analysis analysis = Analysis.build(graph);

    writeJson(output.resolve("raw-graph.json"), graph.toJson());
    writeJson(output.resolve("analysis.json"), analysis);
    writeJson(output.resolve("core-graph.json"), analysis.coreGraph(graph));
    Files.writeString(
        output.resolve("analysis.md"), analysis.markdown(graph), StandardCharsets.UTF_8);

    System.out.println("Library graph written to " + output.toAbsolutePath());
    System.out.println(
        graph.definedMethods.size()
            + " methods, "
            + (graph.methodInvocationInstructions + graph.invokeDynamicInstructions)
            + " invocation instructions ("
            + graph.invokeDynamicInstructions
            + " invokedynamic), maximum k-core "
            + analysis.maximumCore);
  }

  private static void writeJson(Path path, Object value) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      GSON.toJson(value, writer);
    }
  }

  static final class Graph {
    final List<Artifact> artifacts = new ArrayList<>();
    final Map<String, TypeNode> types = new TreeMap<>();
    final Map<String, MethodNode> definedMethods = new TreeMap<>();
    final Set<String> externalMethods = new TreeSet<>();
    final List<CallEdge> calls = new ArrayList<>();
    final List<DynamicCallSite> dynamicCallSites = new ArrayList<>();
    final List<TypeEdge> typeEdges = new ArrayList<>();
    int methodInvocationInstructions;
    int invokeDynamicInstructions;
    int lambdaMetafactoryCallSites;

    void scan(Path jarPath) throws IOException {
      Artifact artifact = Artifact.from(jarPath);
      artifacts.add(artifact);
      try (JarFile jar = new JarFile(jarPath.toFile())) {
        List<JarEntry> entries =
            jar.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().endsWith(".class"))
                .filter(entry -> !entry.getName().equals("module-info.class"))
                .filter(entry -> !entry.getName().startsWith("META-INF/versions/"))
                .sorted(Comparator.comparing(JarEntry::getName))
                .toList();
        for (JarEntry entry : entries) {
          try (InputStream input = jar.getInputStream(entry)) {
            scanClass(new ClassReader(input), artifact);
          }
        }
      }
    }

    private void scanClass(ClassReader reader, Artifact artifact) {
      reader.accept(
          new ClassVisitor(Opcodes.ASM9) {
            private TypeNode type;

            @Override
            public void visit(
                int version,
                int access,
                String name,
                String signature,
                String superName,
                String[] interfaces) {
              type = new TypeNode(name, artifact.coordinate, access, superName);
              if (interfaces != null) {
                Collections.addAll(type.interfaces, interfaces);
              }
              types.put(name, type);
              if (superName != null) {
                typeEdges.add(new TypeEdge(name, superName, "EXTENDS"));
              }
              for (String implemented : type.interfaces) {
                typeEdges.add(new TypeEdge(name, implemented, "IMPLEMENTS"));
              }
            }

            @Override
            public void visitSource(String source, String debug) {
              type.sourceFile = source;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
              type.annotations.add(descriptor);
              return null;
            }

            @Override
            public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
              MethodNode method =
                  new MethodNode(type.name, name, descriptor, type.artifact, access);
              if (exceptions != null) {
                Collections.addAll(method.exceptions, exceptions);
              }
              definedMethods.put(method.id, method);
              type.methods.add(method.id);
              return new MethodVisitor(Opcodes.ASM9) {
                int currentLine = -1;
                int dynamicOrdinal;

                @Override
                public void visitLineNumber(int line, Label start) {
                  currentLine = line;
                  method.firstLine = Math.min(method.firstLine, line);
                  method.lastLine = Math.max(method.lastLine, line);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                  method.annotations.add(annotation);
                  return null;
                }

                @Override
                public void visitMethodInsn(
                    int opcode,
                    String owner,
                    String calledName,
                    String calledDescriptor,
                    boolean isInterface) {
                  methodInvocationInstructions++;
                  calls.add(
                      new CallEdge(
                          method.id,
                          methodId(owner, calledName, calledDescriptor),
                          opcodeName(opcode),
                          currentLine,
                          false));
                }

                @Override
                public void visitInvokeDynamicInsn(
                    String dynamicName,
                    String dynamicDescriptor,
                    Handle bootstrapMethodHandle,
                    Object... bootstrapMethodArguments) {
                  invokeDynamicInstructions++;
                  if (isLambdaBootstrap(bootstrapMethodHandle)) {
                    lambdaMetafactoryCallSites++;
                  }
                  String dynamicTarget =
                      "invokedynamic/"
                          + dynamicName
                          + dynamicDescriptor
                          + "@"
                          + method.id
                          + ":"
                          + currentLine
                          + ":"
                          + dynamicOrdinal++;
                  DynamicCallSite callSite =
                      new DynamicCallSite(
                          dynamicTarget,
                          method.id,
                          dynamicName,
                          dynamicDescriptor,
                          handleId(bootstrapMethodHandle),
                          currentLine);
                  dynamicCallSites.add(callSite);
                  calls.add(
                      new CallEdge(method.id, dynamicTarget, "INVOKEDYNAMIC", currentLine, true));
                  calls.add(
                      new CallEdge(
                          method.id,
                          handleId(bootstrapMethodHandle),
                          "BOOTSTRAP_METHOD",
                          currentLine,
                          true));
                  for (Object argument : bootstrapMethodArguments) {
                    if (argument instanceof Handle) {
                      Handle handle = (Handle) argument;
                      String target = handleId(handle);
                      callSite.handleArguments.add(target);
                      calls.add(
                          new CallEdge(
                              method.id,
                              target,
                              isLambdaBootstrap(bootstrapMethodHandle)
                                  ? "LAMBDA_IMPLEMENTATION"
                                  : "BOOTSTRAP_ARGUMENT_HANDLE",
                              currentLine,
                              true));
                    } else {
                      callSite.constantArguments.add(String.valueOf(argument));
                    }
                  }
                }

                @Override
                public void visitEnd() {
                  if (method.firstLine == Integer.MAX_VALUE) {
                    method.firstLine = -1;
                  }
                }
              };
            }
          },
          ClassReader.SKIP_FRAMES);
    }

    void finish() {
      for (MethodNode method : definedMethods.values()) {
        method.noiseReason = noiseReason(method);
      }
      for (CallEdge call : calls) {
        if (!definedMethods.containsKey(call.to)) {
          externalMethods.add(call.to);
        }
      }
      calls.sort(
          Comparator.comparing((CallEdge edge) -> edge.from)
              .thenComparing(edge -> edge.to)
              .thenComparingInt(edge -> edge.line));
      typeEdges.sort(
          Comparator.comparing((TypeEdge edge) -> edge.from)
              .thenComparing(edge -> edge.to)
              .thenComparing(edge -> edge.kind));
    }

    private static String noiseReason(MethodNode method) {
      if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) {
        return "synthetic";
      }
      if ((method.access & Opcodes.ACC_BRIDGE) != 0) {
        return "bridge";
      }
      if (OBJECT_METHODS.contains(method.name + method.descriptor)) {
        return "object-override";
      }
      return null;
    }

    Map<String, Object> toJson() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("schemaVersion", 1);
      json.put("artifacts", artifacts);
      json.put("types", types.values());
      json.put("definedMethods", definedMethods.values());
      json.put("externalMethods", externalMethods);
      json.put("methodInvocationInstructions", methodInvocationInstructions);
      json.put("invokeDynamicInstructions", invokeDynamicInstructions);
      json.put("lambdaMetafactoryCallSites", lambdaMetafactoryCallSites);
      json.put("calls", calls);
      json.put("dynamicCallSites", dynamicCallSites);
      json.put("typeEdges", typeEdges);
      return json;
    }
  }

  static final class Analysis {
    int definedMethodCount;
    int externalTargetCount;
    int callSiteCount;
    int derivedCallEdgeCount;
    int invokeDynamicCallSiteCount;
    int lambdaMetafactoryCallSiteCount;
    int uniqueInternalEdgeCount;
    int simplifiedMethodCount;
    int simplifiedEdgeCount;
    int weakComponentCount;
    int largestWeakComponent;
    int maximumCore;
    Map<String, Integer> removedByReason = new TreeMap<>();
    List<RankedNode> highestDegree = new ArrayList<>();
    List<RankedNode> highestPageRank = new ArrayList<>();
    List<RankedNode> densestCore = new ArrayList<>();
    List<AggregateEdge> classEdges = new ArrayList<>();
    List<AggregateEdge> packageEdges = new ArrayList<>();
    transient Map<String, Integer> coreNumbers = new HashMap<>();
    transient Map<String, Double> pageRanks = new HashMap<>();
    transient Set<String> keptMethods = new TreeSet<>();
    transient Set<String> keptEdges = new TreeSet<>();

    static Analysis build(Graph graph) {
      Analysis result = new Analysis();
      result.definedMethodCount = graph.definedMethods.size();
      result.externalTargetCount = graph.externalMethods.size();
      result.callSiteCount = graph.methodInvocationInstructions + graph.invokeDynamicInstructions;
      result.derivedCallEdgeCount = graph.calls.size();
      result.invokeDynamicCallSiteCount = graph.invokeDynamicInstructions;
      result.lambdaMetafactoryCallSiteCount = graph.lambdaMetafactoryCallSites;

      Map<String, Set<String>> outgoing = new HashMap<>();
      Map<String, Set<String>> incoming = new HashMap<>();
      for (String id : graph.definedMethods.keySet()) {
        outgoing.put(id, new HashSet<>());
        incoming.put(id, new HashSet<>());
      }
      for (CallEdge call : graph.calls) {
        if (graph.definedMethods.containsKey(call.to)) {
          outgoing.get(call.from).add(call.to);
          incoming.get(call.to).add(call.from);
        }
      }
      result.uniqueInternalEdgeCount = outgoing.values().stream().mapToInt(Collection::size).sum();

      for (MethodNode method : graph.definedMethods.values()) {
        if (method.noiseReason != null) {
          result.removedByReason.merge(method.noiseReason, 1, Integer::sum);
        } else if (outgoing.get(method.id).isEmpty() && incoming.get(method.id).isEmpty()) {
          result.removedByReason.merge("isolated", 1, Integer::sum);
        } else {
          result.keptMethods.add(method.id);
        }
      }
      result.simplifiedMethodCount = result.keptMethods.size();

      Map<String, Set<String>> directed = new HashMap<>();
      Map<String, Set<String>> undirected = new HashMap<>();
      for (String id : result.keptMethods) {
        directed.put(id, new HashSet<>());
        undirected.put(id, new HashSet<>());
      }
      for (Map.Entry<String, Set<String>> entry : outgoing.entrySet()) {
        if (!result.keptMethods.contains(entry.getKey())) {
          continue;
        }
        for (String target : entry.getValue()) {
          if (result.keptMethods.contains(target)) {
            directed.get(entry.getKey()).add(target);
            undirected.get(entry.getKey()).add(target);
            undirected.get(target).add(entry.getKey());
            result.keptEdges.add(entry.getKey() + "\n" + target);
          }
        }
      }
      result.simplifiedEdgeCount = result.keptEdges.size();

      List<Integer> componentSizes = weakComponentSizes(undirected);
      result.weakComponentCount = componentSizes.size();
      result.largestWeakComponent = componentSizes.isEmpty() ? 0 : componentSizes.get(0);
      result.coreNumbers = coreNumbers(undirected);
      result.maximumCore =
          result.coreNumbers.values().stream().mapToInt(Integer::intValue).max().orElse(0);
      result.pageRanks = pageRank(directed);

      result.highestDegree =
          top(
              result.keptMethods,
              id -> (double) undirected.get(id).size(),
              result.coreNumbers,
              result.pageRanks,
              40);
      result.highestPageRank =
          top(result.keptMethods, result.pageRanks::get, result.coreNumbers, result.pageRanks, 40);
      result.densestCore =
          top(
              result.keptMethods.stream()
                  .filter(id -> result.coreNumbers.get(id) == result.maximumCore)
                  .toList(),
              id -> (double) undirected.get(id).size(),
              result.coreNumbers,
              result.pageRanks,
              80);

      result.classEdges = aggregateEdges(result.keptEdges, LibraryGraphAnalyzer::owner);
      result.packageEdges = aggregateEdges(result.keptEdges, id -> packageName(owner(id)));
      return result;
    }

    Map<String, Object> coreGraph(Graph graph) {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("schemaVersion", 1);
      json.put("view", "maximum-k-core");
      json.put("k", maximumCore);
      List<Map<String, Object>> nodes = new ArrayList<>();
      Set<String> core = new TreeSet<>();
      for (Map.Entry<String, Integer> entry : coreNumbers.entrySet()) {
        if (entry.getValue() == maximumCore) {
          core.add(entry.getKey());
          MethodNode method = graph.definedMethods.get(entry.getKey());
          Map<String, Object> node = new LinkedHashMap<>();
          node.put("id", method.id);
          node.put("owner", method.owner);
          node.put("name", method.name);
          node.put("descriptor", method.descriptor);
          node.put("pageRank", pageRanks.get(method.id));
          nodes.add(node);
        }
      }
      List<Map<String, String>> edges = new ArrayList<>();
      for (String edge : keptEdges) {
        int separator = edge.indexOf('\n');
        String from = edge.substring(0, separator);
        String to = edge.substring(separator + 1);
        if (core.contains(from) && core.contains(to)) {
          edges.add(Map.of("from", from, "to", to));
        }
      }
      json.put("nodes", nodes);
      json.put("edges", edges);
      return json;
    }

    String markdown(Graph graph) {
      StringBuilder out = new StringBuilder();
      out.append("# Spring WebMVC 6.0.2 library graph\n\n");
      out.append(
          "Generated from resolved bytecode. Static call edges are possible declared calls, not observed execution.\n\n");
      out.append("## Scope\n\n");
      for (Artifact artifact : graph.artifacts) {
        out.append("- `")
            .append(artifact.coordinate)
            .append("` — `")
            .append(artifact.file)
            .append("`\n");
      }
      out.append("\n## Size\n\n");
      out.append("| Measure | Count |\n| --- | ---: |\n");
      row(out, "Defined methods", definedMethodCount);
      row(out, "External symbolic method targets", externalTargetCount);
      row(out, "Bytecode invocation instructions", callSiteCount);
      row(out, "invokedynamic call sites", invokeDynamicCallSiteCount);
      row(out, "LambdaMetafactory call sites", lambdaMetafactoryCallSiteCount);
      row(out, "Derived call edges", derivedCallEdgeCount);
      row(out, "Unique internal method edges", uniqueInternalEdgeCount);
      row(out, "Methods in simplified graph", simplifiedMethodCount);
      row(out, "Edges in simplified graph", simplifiedEdgeCount);
      row(out, "Weak components", weakComponentCount);
      row(out, "Largest weak component", largestWeakComponent);
      row(out, "Maximum k-core", maximumCore);

      out.append("\n## Deterministic removals\n\n");
      out.append("The raw graph remains unchanged. The simplified view excludes:\n\n");
      for (Map.Entry<String, Integer> entry : removedByReason.entrySet()) {
        out.append("- ")
            .append(entry.getKey())
            .append(": ")
            .append(entry.getValue())
            .append(" methods\n");
      }
      rankedTable(out, "Highest undirected degree", highestDegree);
      rankedTable(out, "Highest PageRank", highestPageRank);
      rankedTable(out, "Maximum k-core", densestCore);

      out.append("\n## Interpretation limits\n\n");
      out.append(
          "- Virtual calls are recorded at their declared symbolic target; override expansion is not implemented yet.\n");
      out.append(
          "- Reflection, dependency injection, servlet redispatch, and executor callbacks require semantic or runtime edges.\n");
      out.append(
          "- High centrality indicates graph structure, not instrumentation importance or runtime frequency.\n");
      out.append(
          "- External targets remain in the raw graph but are not included in the current core calculation.\n");
      return out.toString();
    }

    private static void row(StringBuilder out, String name, int value) {
      out.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    private static void rankedTable(StringBuilder out, String title, List<RankedNode> nodes) {
      out.append("\n## ").append(title).append("\n\n");
      out.append("| Method | Score | Core | PageRank |\n| --- | ---: | ---: | ---: |\n");
      for (RankedNode node : nodes.subList(0, Math.min(25, nodes.size()))) {
        out.append("| `")
            .append(node.id.replace("|", "\\|"))
            .append("` | ")
            .append(String.format(Locale.ROOT, "%.6f", node.score))
            .append(" | ")
            .append(node.core)
            .append(" | ")
            .append(String.format(Locale.ROOT, "%.8f", node.pageRank))
            .append(" |\n");
      }
    }

    private static List<Integer> weakComponentSizes(Map<String, Set<String>> graph) {
      Set<String> unseen = new HashSet<>(graph.keySet());
      List<Integer> sizes = new ArrayList<>();
      while (!unseen.isEmpty()) {
        String start = unseen.iterator().next();
        unseen.remove(start);
        int size = 0;
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
          String current = queue.removeFirst();
          size++;
          for (String adjacent : graph.get(current)) {
            if (unseen.remove(adjacent)) {
              queue.addLast(adjacent);
            }
          }
        }
        sizes.add(size);
      }
      sizes.sort(Comparator.reverseOrder());
      return sizes;
    }

    private static Map<String, Integer> coreNumbers(Map<String, Set<String>> graph) {
      Map<String, Integer> degree = new HashMap<>();
      Map<String, Integer> core = new HashMap<>();
      PriorityQueue<DegreeNode> queue =
          new PriorityQueue<>(
              Comparator.comparingInt((DegreeNode node) -> node.degree)
                  .thenComparing(node -> node.id));
      for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
        degree.put(entry.getKey(), entry.getValue().size());
        queue.add(new DegreeNode(entry.getKey(), entry.getValue().size()));
      }
      Set<String> removed = new HashSet<>();
      int runningCore = 0;
      while (!queue.isEmpty()) {
        DegreeNode candidate = queue.remove();
        if (removed.contains(candidate.id) || degree.get(candidate.id) != candidate.degree) {
          continue;
        }
        removed.add(candidate.id);
        runningCore = Math.max(runningCore, candidate.degree);
        core.put(candidate.id, runningCore);
        for (String adjacent : graph.get(candidate.id)) {
          if (!removed.contains(adjacent)) {
            int updated = degree.get(adjacent) - 1;
            degree.put(adjacent, updated);
            queue.add(new DegreeNode(adjacent, updated));
          }
        }
      }
      return core;
    }

    private static Map<String, Double> pageRank(Map<String, Set<String>> graph) {
      int size = graph.size();
      if (size == 0) {
        return Map.of();
      }
      double initial = 1.0 / size;
      Map<String, Double> ranks = new HashMap<>();
      for (String id : graph.keySet()) {
        ranks.put(id, initial);
      }
      for (int iteration = 0; iteration < 40; iteration++) {
        Map<String, Double> next = new HashMap<>();
        graph.keySet().forEach(id -> next.put(id, 0.15 / size));
        double dangling = 0;
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
          if (entry.getValue().isEmpty()) {
            dangling += ranks.get(entry.getKey());
          } else {
            double share = 0.85 * ranks.get(entry.getKey()) / entry.getValue().size();
            for (String target : entry.getValue()) {
              next.merge(target, share, Double::sum);
            }
          }
        }
        double danglingShare = 0.85 * dangling / size;
        next.replaceAll((id, value) -> value + danglingShare);
        ranks = next;
      }
      return ranks;
    }

    private static List<RankedNode> top(
        Collection<String> ids,
        Score score,
        Map<String, Integer> core,
        Map<String, Double> pageRank,
        int limit) {
      return ids.stream()
          .map(
              id ->
                  new RankedNode(
                      id, score.value(id), core.getOrDefault(id, 0), pageRank.getOrDefault(id, 0d)))
          .sorted(
              Comparator.comparingDouble((RankedNode node) -> node.score)
                  .reversed()
                  .thenComparing(node -> node.id))
          .limit(limit)
          .toList();
    }

    private static List<AggregateEdge> aggregateEdges(Set<String> methodEdges, NameMapper mapper) {
      Map<String, Integer> weights = new HashMap<>();
      for (String edge : methodEdges) {
        int separator = edge.indexOf('\n');
        String from = mapper.map(edge.substring(0, separator));
        String to = mapper.map(edge.substring(separator + 1));
        if (!from.equals(to)) {
          weights.merge(from + "\n" + to, 1, Integer::sum);
        }
      }
      return weights.entrySet().stream()
          .map(
              entry -> {
                int separator = entry.getKey().indexOf('\n');
                return new AggregateEdge(
                    entry.getKey().substring(0, separator),
                    entry.getKey().substring(separator + 1),
                    entry.getValue());
              })
          .sorted(
              Comparator.comparingInt((AggregateEdge edge) -> edge.weight)
                  .reversed()
                  .thenComparing(edge -> edge.from)
                  .thenComparing(edge -> edge.to))
          .limit(500)
          .toList();
    }
  }

  interface Score {
    double value(String id);
  }

  interface NameMapper {
    String map(String id);
  }

  static final class Artifact {
    String coordinate;
    String file;
    long size;
    String sha256;

    static Artifact from(Path path) throws IOException {
      Artifact artifact = new Artifact();
      artifact.file = path.toAbsolutePath().toString();
      artifact.size = Files.size(path);
      artifact.sha256 = sha256(path);
      String name = path.getFileName().toString();
      if (name.endsWith(".jar")) {
        artifact.coordinate = name.substring(0, name.length() - ".jar".length());
      } else if (name.endsWith(".jmod")) {
        artifact.coordinate = name.substring(0, name.length() - ".jmod".length());
      } else {
        throw new IllegalArgumentException("Unsupported graph input: " + path);
      }
      return artifact;
    }

    private static String sha256(Path path) throws IOException {
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
          }
        }
        return HexFormat.of().formatHex(digest.digest());
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("SHA-256 unavailable", impossible);
      }
    }
  }

  static final class TypeNode {
    String name;
    String artifact;
    int access;
    String superName;
    String sourceFile;
    List<String> interfaces = new ArrayList<>();
    List<String> annotations = new ArrayList<>();
    List<String> methods = new ArrayList<>();

    TypeNode(String name, String artifact, int access, String superName) {
      this.name = name;
      this.artifact = artifact;
      this.access = access;
      this.superName = superName;
    }
  }

  static final class MethodNode {
    String id;
    String owner;
    String name;
    String descriptor;
    String artifact;
    int access;
    int firstLine = Integer.MAX_VALUE;
    int lastLine = -1;
    String noiseReason;
    List<String> annotations = new ArrayList<>();
    List<String> exceptions = new ArrayList<>();

    MethodNode(String owner, String name, String descriptor, String artifact, int access) {
      this.id = methodId(owner, name, descriptor);
      this.owner = owner;
      this.name = name;
      this.descriptor = descriptor;
      this.artifact = artifact;
      this.access = access;
    }
  }

  static final class CallEdge {
    String from;
    String to;
    String opcode;
    int line;
    boolean dynamic;

    CallEdge(String from, String to, String opcode, int line, boolean dynamic) {
      this.from = from;
      this.to = to;
      this.opcode = opcode;
      this.line = line;
      this.dynamic = dynamic;
    }
  }

  static final class TypeEdge {
    String from;
    String to;
    String kind;

    TypeEdge(String from, String to, String kind) {
      this.from = from;
      this.to = to;
      this.kind = kind;
    }
  }

  static final class DynamicCallSite {
    String id;
    String caller;
    String name;
    String descriptor;
    String bootstrapMethod;
    int line;
    List<String> handleArguments = new ArrayList<>();
    List<String> constantArguments = new ArrayList<>();

    DynamicCallSite(
        String id,
        String caller,
        String name,
        String descriptor,
        String bootstrapMethod,
        int line) {
      this.id = id;
      this.caller = caller;
      this.name = name;
      this.descriptor = descriptor;
      this.bootstrapMethod = bootstrapMethod;
      this.line = line;
    }
  }

  static final class RankedNode {
    String id;
    double score;
    int core;
    double pageRank;

    RankedNode(String id, double score, int core, double pageRank) {
      this.id = id;
      this.score = score;
      this.core = core;
      this.pageRank = pageRank;
    }
  }

  static final class AggregateEdge {
    String from;
    String to;
    int weight;

    AggregateEdge(String from, String to, int weight) {
      this.from = from;
      this.to = to;
      this.weight = weight;
    }
  }

  static final class DegreeNode {
    final String id;
    final int degree;

    DegreeNode(String id, int degree) {
      this.id = id;
      this.degree = degree;
    }
  }

  private static String methodId(String owner, String name, String descriptor) {
    return owner + "#" + name + descriptor;
  }

  private static String handleId(Handle handle) {
    return methodId(handle.getOwner(), handle.getName(), handle.getDesc());
  }

  private static boolean isLambdaBootstrap(Handle bootstrapMethod) {
    return "java/lang/invoke/LambdaMetafactory".equals(bootstrapMethod.getOwner());
  }

  private static String owner(String methodId) {
    int separator = methodId.indexOf('#');
    return separator < 0 ? methodId : methodId.substring(0, separator);
  }

  private static String packageName(String owner) {
    int separator = owner.lastIndexOf('/');
    return separator < 0 ? "<default>" : owner.substring(0, separator);
  }

  private static String opcodeName(int opcode) {
    return switch (opcode) {
      case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
      case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
      case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
      case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
      default -> "OPCODE_" + opcode;
    };
  }
}
