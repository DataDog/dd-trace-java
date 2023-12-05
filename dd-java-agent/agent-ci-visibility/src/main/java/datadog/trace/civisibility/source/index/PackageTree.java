package datadog.trace.civisibility.source.index;

import datadog.trace.api.Config;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * This class maintains the list of packages present in a repository.
 *
 * <p>If a package includes classes, all of its children packages are ignored (that is, if there are
 * packages a/b, a/b/c, a/b/d, all of which contain classes, only a/b will be retained - a/b/c and
 * a/b/d will be discarded as redundant).
 *
 * <p>The limit on the length of the list is configurable. If there are more packages present than
 * the limit, coarsening will happen (for instance, if there are packages a/b/c, a/b/d, b/c/d,
 * b/c/e/f and the limit is 2, the packages will be coarsened and a/b, b/c will be retained as the
 * result).
 *
 * <p>The intent is to be as specific as possible without exceeding the limit, so coarsening applies
 * first to the "longest" package names (not in terms of the actual string length, but in terms of
 * the number of segments separated by "."). If there are multiple packages with the same length,
 * those that have more children will be coarsened first.
 */
public class PackageTree {

  private final Node root = new Node(null, "");

  private final int rootPackagesLimit;

  public PackageTree(Config config) {
    rootPackagesLimit = config.getCiVisibilityCoverageRootPackagesLimit();
  }

  void add(Path packagePath) {
    if (packagePath.toString().isEmpty()) {
      return;
    }
    root.add(packagePath.iterator());
  }

  List<String> asList() {
    truncateIfNeeded(root);

    List<String> childrenPackages = new ArrayList<>(rootPackagesLimit);
    for (Node child : root.children.values()) {
      child.stringify(childrenPackages, "");
    }
    return childrenPackages;
  }

  private void truncateIfNeeded(Node root) {
    Deque<List<Node>> nodesByDepth = new ArrayDeque<>();

    List<Node> current = Collections.singletonList(root);
    while (!current.isEmpty()) {
      List<Node> next = new ArrayList<>();
      for (Node treeNode : current) {
        next.addAll(treeNode.children.values());
      }
      nodesByDepth.push(current);
      current = next;
    }

    // start truncating with the deepest nodes
    // (i.e. most specific packages names)
    while (!nodesByDepth.isEmpty()) {
      List<Node> nodes = nodesByDepth.pop();
      // sorting the nodes now as leafChildren counts might have changed
      // if their children were truncated
      nodes.sort(Comparator.comparingInt(node -> -node.leafChildren));

      for (Node node : nodes) {
        if (root.leafChildren <= rootPackagesLimit) {
          // stop as soon as we have truncated enough, even if it's mid-level
          return;
        } else {
          node.truncate();
        }
      }
    }
  }

  private static final class Node {
    private final Node parent;
    private final String name;
    private Map<String, Node> children = new HashMap<>();
    private int leafChildren;
    private boolean leaf;

    private Node(Node parent, String name) {
      this.parent = parent;
      this.name = name;
    }

    private int add(Iterator<Path> iterator) {
      if (leaf) {
        return 0;

      } else if (!iterator.hasNext()) {
        leaf = true;
        if (leafChildren == 0) {
          return ++leafChildren;
        } else {
          // what used to be a non-leaf is now a leaf,
          // truncating children
          int delta = 1 - leafChildren;
          children = Collections.emptyMap();
          leafChildren = 1;
          return delta;
        }

      } else {
        Path element = iterator.next();
        Node child =
            children.computeIfAbsent(element.toString(), nodeName -> new Node(this, nodeName));
        int delta = child.add(iterator);
        leafChildren += delta;
        return delta;
      }
    }

    private void truncate() {
      children = Collections.emptyMap();
      leaf = true;

      int delta = leafChildren - 1;
      Node current = this;
      while (current != null) {
        current.leafChildren -= delta;
        current = current.parent;
      }
    }

    private void stringify(List<String> childrenPackages, String currentPath) {
      currentPath += name + ".";
      if (leaf) {
        childrenPackages.add(currentPath + "*");
      } else {
        for (Node child : children.values()) {
          child.stringify(childrenPackages, currentPath);
        }
      }
    }
  }
}
