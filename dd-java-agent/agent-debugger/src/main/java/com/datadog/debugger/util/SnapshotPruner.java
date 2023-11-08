package com.datadog.debugger.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Supplier;

public class SnapshotPruner {
  private static final String NOT_CAPTURED_REASON = "notCapturedReason";
  private static final String DEPTH = "depth";
  private static final String PRUNED = "{\"pruned\":true}";

  private State state = State.OBJECT;
  private final Deque<Node> stack = new ArrayDeque<>(32);
  private int currentLevel;
  private int strMatchIdx;
  private Supplier<State> onStringMatches;
  private String matchingString;
  private Node root;

  public static String prune(String snapshot, int maxTargetedSize, int minLevel) {
    int delta = snapshot.length() - maxTargetedSize;
    if (delta <= 0) {
      return snapshot;
    }
    SnapshotPruner snapshotPruner = new SnapshotPruner(snapshot);
    Collection<Node> leaves = snapshotPruner.getLeaves(minLevel);
    PriorityQueue<Node> sortedLeaves =
        new PriorityQueue<>(
            Comparator.comparing((Node n) -> n.notCapturedDepth)
                .thenComparingInt((Node n) -> n.level)
                .thenComparing((Node n) -> n.notCaptured)
                .thenComparingInt(Node::size)
                .reversed());
    sortedLeaves.addAll(leaves);
    int total = 0;
    Map<Integer, Node> nodes = new HashMap<>();
    while (!sortedLeaves.isEmpty()) {
      Node leaf = sortedLeaves.poll();
      nodes.put(leaf.start, leaf);
      total += leaf.size() - PRUNED.length();
      if (total > delta) break;
      Node parent = leaf.parent;
      if (parent == null) {
        break;
      }
      parent.pruned++;
      if (parent.pruned >= parent.children.size() && parent.level >= minLevel) {
        // We have pruned all the children of this parent node, so we can
        // treat it as a leaf now.
        parent.notCaptured = true;
        parent.notCapturedDepth = true;
        sortedLeaves.offer(parent);
        for (Node child : parent.children) {
          nodes.remove(child.start);
          total -= child.size() - PRUNED.length();
        }
      }
    }
    List<Node> prunedNodes = new ArrayList<>(nodes.values());
    prunedNodes.sort(Comparator.comparing((Node n) -> n.start));
    StringBuilder sb = new StringBuilder();
    sb.append(snapshot, 0, prunedNodes.get(0).start);
    for (int i = 1; i < prunedNodes.size(); i++) {
      sb.append(PRUNED);
      sb.append(snapshot, prunedNodes.get(i - 1).end + 1, prunedNodes.get(i).start);
    }
    sb.append(PRUNED);
    sb.append(snapshot, prunedNodes.get(prunedNodes.size() - 1).end + 1, snapshot.length());
    return sb.toString();
  }

  private Collection<Node> getLeaves(int minLevel) {
    if (root == null) {
      return Collections.emptyList();
    }
    return root.getLeaves(minLevel);
  }

  private SnapshotPruner(String snapshot) {
    for (int i = 0; i < snapshot.length(); i++) {
      state = state.parse(this, snapshot.charAt(i), i);
      if (state == null) {
        break;
      }
    }
  }

  enum State {
    OBJECT {
      @Override
      public State parse(SnapshotPruner pruner, char c, int index) {
        switch (c) {
          case '{':
            {
              Node n = new Node(index, pruner.currentLevel);
              pruner.currentLevel++;
              if (!pruner.stack.isEmpty()) {
                n.parent = pruner.stack.peekLast();
                n.parent.children.add(n);
              }
              pruner.stack.addLast(n);
              return this;
            }
          case '}':
            {
              Node n = pruner.stack.removeLast();
              n.end = index;
              pruner.currentLevel--;
              if (pruner.stack.isEmpty()) {
                pruner.root = n;
                return null;
              }
              return this;
            }
          case '"':
            {
              pruner.strMatchIdx = 0;
              pruner.matchingString = NOT_CAPTURED_REASON;
              pruner.onStringMatches =
                  () -> {
                    Node n = pruner.stack.peekLast();
                    if (n == null) {
                      throw new IllegalStateException("empty stack");
                    }
                    n.notCaptured = true;
                    return NOT_CAPTURED;
                  };
              return STRING;
            }
          default:
            return this;
        }
      }
    },
    STRING {
      @Override
      public State parse(SnapshotPruner pruner, char c, int index) {
        switch (c) {
          case '"':
            {
              if (pruner.strMatchIdx == pruner.matchingString.length()) {
                return pruner.onStringMatches.get();
              }
              return OBJECT;
            }
          case '\\':
            {
              pruner.strMatchIdx = -1;
              return ESCAPE;
            }
          default:
            if (pruner.strMatchIdx > -1) {
              char current = pruner.matchingString.charAt(pruner.strMatchIdx++);
              if (c != current) {
                pruner.strMatchIdx = -1;
              }
            }
            return this;
        }
      }
    },
    NOT_CAPTURED {
      @Override
      public State parse(SnapshotPruner pruner, char c, int index) {
        switch (c) {
          case '"':
            {
              pruner.strMatchIdx = 0;
              pruner.matchingString = DEPTH;
              pruner.onStringMatches =
                  () -> {
                    Node n = pruner.stack.peekLast();
                    if (n == null) {
                      throw new IllegalStateException("empty stack");
                    }
                    n.notCapturedDepth = true;
                    return OBJECT;
                  };
              return STRING;
            }
          case ' ':
          case ':':
          case '\n':
          case '\t':
          case '\r':
            return this;
          default:
            return OBJECT;
        }
      }
    },
    ESCAPE {
      @Override
      public State parse(SnapshotPruner pruner, char c, int index) {
        return STRING;
      }
    };

    public abstract State parse(SnapshotPruner pruner, char c, int index);
  }

  private static class Node {
    int pruned;
    Node parent;
    List<Node> children = new ArrayList<>();
    final int start;
    int end;
    final int level;
    boolean notCaptured;
    boolean notCapturedDepth;

    public Node(int start, int level) {
      this.start = start;
      this.level = level;
    }

    public Collection<Node> getLeaves(int minLevel) {
      if (children.isEmpty() && level >= minLevel) {
        return Collections.singleton(this);
      }
      Collection<Node> results = new ArrayList<>();
      for (int i = children.size() - 1; i >= 0; i--) {
        results.addAll(children.get(i).getLeaves(minLevel));
      }
      return results;
    }

    public int size() {
      return end - start + 1;
    }
  }
}
