package com.datadog.appsec.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class AppSecConfigFactory {

  private static final Yaml yaml;
  private static final JsonAdapter<AppSecConfig> jsonAdapter;

  static {
    yaml = new Yaml(new AppSecConfigConstructor());

    jsonAdapter =
        new Moshi.Builder().add(new LegacyConfigJsonAdapter()).build().adapter(AppSecConfig.class);
  }

  private AppSecConfigFactory() {}

  public static AppSecConfig fromYamlFile(File file) throws FileNotFoundException {
    InputStream inputStream = new FileInputStream(file);
    return yaml.loadAs(inputStream, AppSecConfig.class);
  }

  public static String toLegacyFormat(AppSecConfig appSecConfig) {
    return jsonAdapter.toJson(appSecConfig);
  }

  // We're using dedicated constructor class for AppSecConfig because it's
  // loaded by appsec Classloader, but Moshi loaded by shared Classloader.
  // Also this constructor disables case sensitivity for Enums that allows
  // to use uppercase Enums with lowercase values.
  private static class AppSecConfigConstructor extends Constructor {
    private final ClassLoader loader;

    private static final LoaderOptions loaderOptions;

    static {
      loaderOptions = new LoaderOptions();
      loaderOptions.setEnumCaseSensitive(false);
    }

    public AppSecConfigConstructor() {
      this(Object.class, AppSecConfig.class.getClassLoader());
    }

    public AppSecConfigConstructor(Class<?> theRoot, ClassLoader theLoader) {
      super(theRoot, loaderOptions);
      if (theLoader == null) {
        throw new NullPointerException("Loader must be provided.");
      }
      this.loader = theLoader;
    }

    @Override
    protected Class<?> getClassForName(String name) throws ClassNotFoundException {
      return Class.forName(name, true, loader);
    }
  }
}
