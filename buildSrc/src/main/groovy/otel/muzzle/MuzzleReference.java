package otel.muzzle;

import java.util.ArrayList;
import java.util.List;

public class MuzzleReference {
  public List<String> sources = new ArrayList<>();
  public int flags = 0;
  public String className;
  public String superName;
  public List<String> interfaces = new ArrayList<>();
  public List<Field> fields = new ArrayList<>();
  public List<Method> methods = new ArrayList<>();

  @Override
  public String toString() {
    return "Reference{" +
        "sources=" + sources +
        ", flags=" + flags +
        ", className='" + className + '\'' +
        ", superName='" + superName + '\'' +
        ", interfaces=" + interfaces + '\'' +
        ", fields="+ fields + '\'' +
        ", methods="+ methods +
        '}';
  }

  public static class Field {
    public List<String> sources;
    public int flags;
    public String name;
    public String fieldType;

    @Override
    public String toString() {
      return "Field{" +
          "sources=" + sources +
          ", flags=" + flags +
          ", name='" + name + '\'' +
          ", fieldType='" + fieldType + '\'' +
          '}';
    }
  }

  public static class Method {
    public List<String> sources;
    public int flags;
    public String name;
    public String methodType;

    @Override
    public String toString() {
      return "Method{" +
          "sources=" + sources +
          ", flags=" + flags +
          ", name='" + name + '\'' +
          ", methodType='" + methodType + '\'' +
          '}';
    }
  }
}
