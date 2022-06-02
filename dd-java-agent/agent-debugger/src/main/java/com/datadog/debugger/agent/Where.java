package com.datadog.debugger.agent;

import com.datadog.debugger.instrumentation.Types;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stores probe location definition */
public class Where {
  private static final String UNKNOWN_RETURN_TYPE = "$com.datadog.debugger.UNKNOWN$";
  private static final Pattern JVM_CLASS_PATTERN = Pattern.compile("L([^;]+);");

  private String typeName;
  private String methodName;
  private String sourceFile;
  private String signature;
  private SourceLine[] lines;
  // used as cache for matching signature
  private String probeMethodDescriptor;

  Where(
      String typeName, String methodName, String signature, SourceLine[] lines, String sourceFile) {
    this.typeName = typeName;
    this.methodName = methodName;
    this.sourceFile = sourceFile;
    this.signature = signature;
    this.lines = lines;
  }

  Where(String typeName, String methodName, String signature, String[] lines, String sourceFile) {
    this(typeName, methodName, signature, sourceLines(lines), sourceFile);
  }

  public Where() {}

  public Where typeName(String typeName) {
    this.typeName = typeName;
    return this;
  }

  public Where methodName(String methodName) {
    this.methodName = methodName;
    return this;
  }

  public Where signature(String signature) {
    this.signature = signature;
    return this;
  }

  public Where lines(String... lines) {
    this.lines = sourceLines(lines);
    return this;
  }

  public Where sourceFile(String sourceFile) {
    this.sourceFile = sourceFile;
    return this;
  }

  protected static SourceLine[] sourceLines(String[] defs) {
    if (defs == null) {
      return null;
    }
    SourceLine[] lines = new SourceLine[defs.length];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = SourceLine.fromString(defs[i]);
    }
    return lines;
  }

  public String getTypeName() {
    return typeName;
  }

  public String getMethodName() {
    return methodName;
  }

  public String getSourceFile() {
    return sourceFile;
  }

  public String getSignature() {
    return signature;
  }

  public String[] getLines() {
    if (lines == null) {
      return null;
    }
    String[] defs = new String[lines.length];
    for (int i = 0; i < defs.length; i++) {
      defs[i] = lines[i].toString();
    }
    return defs;
  }

  public SourceLine[] getSourceLines() {
    return lines;
  }

  public boolean isTypeMatching(String targetType) {
    return typeName == null || typeName.equals("*") || typeName.equals(targetType);
  }

  public boolean isMethodMatching(String targetMethod) {
    return methodName == null || methodName.equals("*") || methodName.equals(targetMethod);
  }

  public boolean isMethodMatching(String targetName, String targetMethodDescriptor) {
    // try exact matching: name + FQN signature
    if (!isMethodMatching(targetName)) {
      return false;
    }
    if (signature == null || signature.equals("*") || signature.equals(targetMethodDescriptor)) {
      return true;
    }
    // try full JVM signature: "(Ljava.lang.String;Ljava.util.Map;I)V"
    if (probeMethodDescriptor == null) {
      probeMethodDescriptor = signature.trim();
      if (!probeMethodDescriptor.isEmpty()) {
        if (isMissingReturnType(probeMethodDescriptor)) {
          probeMethodDescriptor = UNKNOWN_RETURN_TYPE + " " + probeMethodDescriptor;
        }
        probeMethodDescriptor = Types.descriptorFromSignature(probeMethodDescriptor);
      }
    }
    if (probeMethodDescriptor.isEmpty()) {
      return true;
    }
    if (probeMethodDescriptor.equals(targetMethodDescriptor)) {
      return true;
    }
    // fallback to signature without return type: "Ljava.lang.String;Ljava.util.Map;I"
    String noRetTypeDescriptor = removeReturnType(probeMethodDescriptor);
    targetMethodDescriptor = removeReturnType(targetMethodDescriptor);
    if (noRetTypeDescriptor.equals(targetMethodDescriptor)) {
      return true;
    }
    // Fallback to signature without Fully Qualified Name: "LString;LMap;I"
    String simplifiedSignature = removeFQN(targetMethodDescriptor);
    return noRetTypeDescriptor.equals(simplifiedSignature);
  }

  private static boolean isMissingReturnType(String probeMethodDescriptor) {
    return probeMethodDescriptor.startsWith("(");
  }

  private static String removeFQN(String targetSignature) {
    Matcher matcher = JVM_CLASS_PATTERN.matcher(targetSignature);
    List<String> classes = new ArrayList<>();
    while (matcher.find()) {
      String className = matcher.group(1);
      classes.add(className);
    }
    String result = targetSignature;
    for (String className : classes) {
      Pattern classNamePattern = Pattern.compile("L" + className + ";");
      result = classNamePattern.matcher(result).replaceAll("L" + simplify(className) + ";");
    }
    return result;
  }

  private static String simplify(String fqnClass) {
    if (fqnClass == null) {
      return null;
    }
    int index = fqnClass.lastIndexOf('.');
    if (index == -1) {
      index = fqnClass.lastIndexOf('/');
      if (index == -1) {
        return fqnClass;
      }
    }
    return fqnClass.substring(index + 1);
  }

  private static String removeReturnType(String javaSignature) {
    if (javaSignature == null) {
      return null;
    }
    int leftParen = javaSignature.indexOf('(');
    int rightParen = javaSignature.indexOf(')');
    if (leftParen == -1 || rightParen == -1 || leftParen > rightParen) {
      return javaSignature;
    }
    return javaSignature.substring(leftParen + 1, rightParen);
  }

  public boolean isSignatureMatching(String targetSignature) {
    if (signature == null || signature.equals("*")) {
      return true;
    }
    if (signature.equals(targetSignature)) {
      return true;
    }
    return Types.descriptorFromSignature(signature).equals(targetSignature);
  }

  @Generated
  @Override
  public String toString() {
    return "Where{"
        + "typeName='"
        + typeName
        + '\''
        + ", methodName='"
        + methodName
        + '\''
        + ", sourceFile='"
        + sourceFile
        + '\''
        + ", signature='"
        + signature
        + '\''
        + ", lines="
        + Arrays.toString(lines)
        + '}';
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Where where = (Where) o;
    return Objects.equals(typeName, where.typeName)
        && Objects.equals(methodName, where.methodName)
        && Objects.equals(sourceFile, where.sourceFile)
        && Objects.equals(signature, where.signature)
        && Arrays.equals(lines, where.lines);
  }

  @Generated
  @Override
  public int hashCode() {
    int result = Objects.hash(typeName, methodName, sourceFile, signature);
    result = 31 * result + Arrays.hashCode(lines);
    return result;
  }

  public static class SourceLine {
    private final int from, till;

    public SourceLine(int line) {
      this(line, line);
    }

    public SourceLine(int from, int till) {
      this.from = from;
      this.till = till;
    }

    public boolean isSingleLine() {
      return from == till;
    }

    public int getFrom() {
      return from;
    }

    public int getTill() {
      return till;
    }

    @Override
    public String toString() {
      if (isSingleLine()) {
        return String.valueOf(from);
      } else {
        return from + "-" + till;
      }
    }

    public static SourceLine fromString(String lineDef) {
      int delimIndex = lineDef.indexOf('-');
      if (delimIndex == -1) {
        int line = Integer.parseInt(lineDef);
        return new SourceLine(line, line);
      } else {
        int lineFrom = Integer.parseInt(lineDef.substring(0, delimIndex));
        int lineTill = Integer.parseInt(lineDef.substring(delimIndex + 1));
        return new SourceLine(lineFrom, lineTill);
      }
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SourceLine that = (SourceLine) o;
      return from == that.from && till == that.till;
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(from, till);
    }
  }

  public static class SourceLineAdapter extends JsonAdapter<SourceLine[]> {
    private static String[] STRING_ARRAY = new String[0];

    @Override
    public Where.SourceLine[] fromJson(JsonReader jsonReader) throws IOException {
      if (jsonReader.peek() == JsonReader.Token.NULL) {
        jsonReader.nextNull();
        return null;
      }
      List<String> lines = new ArrayList<>();
      jsonReader.beginArray();
      while (jsonReader.hasNext()) {
        lines.add(jsonReader.nextString());
      }
      jsonReader.endArray();
      return sourceLines(lines.toArray(STRING_ARRAY));
    }

    @Override
    public void toJson(JsonWriter jsonWriter, Where.SourceLine[] sourceLines) throws IOException {
      if (sourceLines == null) {
        jsonWriter.nullValue();
        return;
      }
      jsonWriter.beginArray();
      for (SourceLine sourceLine : sourceLines) {
        jsonWriter.value(sourceLine.toString());
      }
      jsonWriter.endArray();
    }
  }
}
