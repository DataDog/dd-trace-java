package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.instrumentation.Types;
import com.datadog.debugger.util.ClassFileLines;
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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import datadog.trace.util.HashingUtils;

/** Stores probe location definition */
public class Where {
  private static final String UNKNOWN_RETURN_TYPE = "$com.datadog.debugger.UNKNOWN$";
  private static final Pattern JVM_CLASS_PATTERN = Pattern.compile("L([^;]+);");

  private final String typeName;
  private final String methodName;
  private final String sourceFile;
  private final String signature;
  private final SourceLine[] lines;
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

  public static Where of(String typeName, String methodName, String signature, String... lines) {
    return new Where(typeName, methodName, signature, lines, null);
  }

  public static Where of(String typeName, String methodName, String signature) {
    return new Where(typeName, methodName, signature, (SourceLine[]) null, null);
  }

  protected static SourceLine[] sourceLines(String[] defs) {
    if (defs == null || defs.length == 0) {
      return null;
    }
    SourceLine[] lines = new SourceLine[defs.length];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = SourceLine.fromString(defs[i]);
    }
    return lines;
  }

  public static Where convertLineToMethod(Where lineWhere, ClassFileLines classFileLines) {
    if (lineWhere.methodName != null && lineWhere.lines != null) {
      List<MethodNode> methodsByLine =
          classFileLines.getMethodsByLine(lineWhere.lines[0].getFrom());
      if (methodsByLine != null && !methodsByLine.isEmpty()) {
        // pick the first method, as we can have multiple methods (lambdas) on the same line
        MethodNode method = methodsByLine.get(0);
        return new Where(
            lineWhere.typeName,
            method.name,
            Types.descriptorToSignature(method.desc),
            (SourceLine[]) null,
            null);
      }
    }
    return lineWhere;
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

  public boolean isMethodNameMatching(String targetMethod) {
    return methodName == null || methodName.equals("*") || methodName.equals(targetMethod);
  }

  public enum MethodMatching {
    MATCH,
    SKIP,
    FAIL
  }

  public MethodMatching isMethodMatching(MethodNode methodNode, ClassFileLines classFileLines) {
    String targetName = methodNode.name;
    String targetMethodDescriptor = methodNode.desc;
    // try exact matching: name + FQN signature
    if (!isMethodNameMatching(targetName)) {
      return MethodMatching.FAIL;
    }
    if ((methodNode.access & Opcodes.ACC_BRIDGE) == Opcodes.ACC_BRIDGE) {
      // name is matching but method is a bridge method
      return MethodMatching.SKIP;
    }
    if (signature == null) {
      if (lines == null || lines.length == 0) {
        return MethodMatching.MATCH;
      }
      // try matching by line
      List<MethodNode> methodsByLine = classFileLines.getMethodsByLine(lines[0].getFrom());
      if (methodsByLine == null || methodsByLine.isEmpty()) {
        return MethodMatching.FAIL;
      }
      return methodsByLine.stream().anyMatch(m -> m == methodNode)
          ? MethodMatching.MATCH
          : MethodMatching.FAIL;
    }
    if (signature.equals("*") || signature.equals(targetMethodDescriptor)) {
      return MethodMatching.MATCH;
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
      return MethodMatching.MATCH;
    }
    if (probeMethodDescriptor.equals(targetMethodDescriptor)) {
      return MethodMatching.MATCH;
    }
    // fallback to signature without return type: "Ljava.lang.String;Ljava.util.Map;I"
    String noRetTypeDescriptor = removeReturnType(probeMethodDescriptor);
    targetMethodDescriptor = removeReturnType(targetMethodDescriptor);
    if (noRetTypeDescriptor.equals(targetMethodDescriptor)) {
      return MethodMatching.MATCH;
    }
    // Fallback to signature without Fully Qualified Name: "LString;LMap;I"
    String simplifiedSignature = removeFQN(targetMethodDescriptor);
    return noRetTypeDescriptor.equals(simplifiedSignature)
        ? MethodMatching.MATCH
        : MethodMatching.FAIL;
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
      Pattern classNamePattern = Pattern.compile("L" + escapeClassName(className) + ";");
      result =
          classNamePattern
              .matcher(result)
              .replaceAll("L" + escapeClassName(simplify(className)) + ";");
    }
    return result;
  }

  private static String escapeClassName(String className) {
    return className.replace("$", "\\$");
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
    int result = HashingUtils.hash(typeName, methodName, sourceFile, signature);
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
      return HashingUtils.hash(from, till);
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
