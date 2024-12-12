package datadog.smoketest.springboot;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PropagationController {

  private final Map<String, Supplier<List<String>>> suppliers = new ConcurrentHashMap<>();

  @GetMapping("/{language}")
  public List<String> propagation(@PathVariable("language") final String language) {
    return supplierForLanguage(language).get();
  }

  @GetMapping("/{language}/{method}")
  public String propagation(
      @PathVariable("language") final String language,
      @PathVariable("method") final String method,
      @RequestParam("param") final String param) {
    return invokeMethod(supplierForLanguage(language), method, param);
  }

  @SuppressWarnings("unchecked")
  private Supplier<List<String>> supplierForLanguage(final String language) {
    return suppliers.computeIfAbsent(
        language,
        l -> {
          try {
            String name = Character.toUpperCase(l.charAt(0)) + l.substring(1) + "Propagation";
            Class<?> instance = Thread.currentThread().getContextClassLoader().loadClass(name);
            return (Supplier<List<String>>) instance.newInstance();
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private String invokeMethod(
      final Supplier<List<String>> supplier, final String name, final String param) {
    try {
      final Method method = supplier.getClass().getDeclaredMethod(name, String.class);
      return (String) method.invoke(supplier, param);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
