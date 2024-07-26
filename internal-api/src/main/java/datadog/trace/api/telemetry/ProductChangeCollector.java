package datadog.trace.api.telemetry;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class ProductChangeCollector {

  private static final ProductChangeCollector INSTANCE = new ProductChangeCollector();
  private final Queue<Product> products = new LinkedBlockingQueue<>();

  private ProductChangeCollector() {}

  public static ProductChangeCollector get() {
    return INSTANCE;
  }

  public synchronized void update(final Product product) {
    products.offer(product);
  }

  public synchronized List<Product> drain() {
    if (products.isEmpty()) {
      return Collections.emptyList();
    }

    List<Product> list = new LinkedList<>();

    Product product;
    while ((product = products.poll()) != null) {
      list.add(product);
    }

    return list;
  }
}
