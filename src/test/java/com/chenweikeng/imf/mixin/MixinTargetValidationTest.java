package com.chenweikeng.imf.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Verifies mixin selectors against the exact Minecraft classes on the test runtime classpath. */
class MixinTargetValidationTest {
  private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
  private static final String PSEUDO_DESC = "Lorg/spongepowered/asm/mixin/Pseudo;";
  private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
  private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
  private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
  private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
  private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";

  private final Map<String, ClassNode> classCache = new HashMap<>();

  @Test
  void allRequiredMixinTargetsExist() throws IOException {
    JsonObject config = readMixinConfig();
    String mixinPackage = config.get("package").getAsString();
    List<String> failures = new ArrayList<>();

    for (String mixinName : mixinNames(config)) {
      String mixinClassName = mixinPackage + "." + mixinName;
      ClassNode mixinClass = readRequiredClass(mixinClassName.replace('.', '/'));
      AnnotationNode mixinAnnotation = findAnnotation(mixinClass, MIXIN_DESC);
      assertNotNull(mixinAnnotation, mixinClassName + " has no @Mixin annotation");

      boolean pseudo = findAnnotation(mixinClass, PSEUDO_DESC) != null;
      for (String targetName : mixinTargets(mixinAnnotation)) {
        ClassNode targetClass = readClass(targetName);
        if (targetClass == null && pseudo) {
          continue;
        }
        if (targetClass == null) {
          failures.add(mixinClassName + " targets missing class " + targetName.replace('/', '.'));
          continue;
        }
        validateMixin(mixinClassName, mixinClass, targetClass, failures);
      }
    }

    assertTrue(failures.isEmpty(), "Invalid mixin targets:\n" + String.join("\n", failures));
  }

  @Test
  void automaticCursorReleaseSuppressesPauseWithoutOwningWindowFocus() throws IOException {
    JsonObject config = readMixinConfig();
    List<String> configuredMixins = mixinNames(config);
    assertFalse(
        configuredMixins.contains("NraWindowMixin"),
        "Window.onFocus must remain vanilla-owned so TextInputManager observes real focus loss");

    String mixinPackage = config.get("package").getAsString();
    ClassNode minecraftMixin =
        readRequiredClass((mixinPackage + ".NraMinecraftMixin").replace('.', '/'));
    boolean hasCancellablePauseInjection = false;
    for (MethodNode method : minecraftMixin.methods) {
      for (AnnotationNode annotation : annotations(method)) {
        if (INJECT_DESC.equals(annotation.desc)
            && stringValues(annotation, "method").contains("pauseIfInactive")
            && booleanValue(annotation, "cancellable")) {
          hasCancellablePauseInjection = true;
        }
      }
    }

    assertTrue(
        hasCancellablePauseInjection,
        "NraMinecraftMixin must suppress pauseIfInactive without suppressing window focus loss");
  }

  private JsonObject readMixinConfig() throws IOException {
    try (InputStream stream = resource("imf.mixins.json")) {
      assertNotNull(stream, "imf.mixins.json is missing from the test runtime classpath");
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  private static List<String> mixinNames(JsonObject config) {
    List<String> names = new ArrayList<>();
    addStrings(config.getAsJsonArray("mixins"), names);
    addStrings(config.getAsJsonArray("client"), names);
    return names;
  }

  private static void addStrings(JsonArray array, List<String> destination) {
    if (array != null) {
      array.forEach(element -> destination.add(element.getAsString()));
    }
  }

  private void validateMixin(
      String mixinName, ClassNode mixinClass, ClassNode targetClass, List<String> failures) {
    for (FieldNode field : mixinClass.fields) {
      if (findAnnotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW_DESC) != null
          && findField(targetClass, field.name, field.desc) == null) {
        failures.add(
            mixinName
                + " shadows missing field "
                + targetClass.name.replace('/', '.')
                + "#"
                + field.name
                + " "
                + field.desc);
      }
    }

    for (MethodNode method : mixinClass.methods) {
      if (findAnnotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW_DESC)
              != null
          && findMethod(targetClass, method.name, method.desc) == null) {
        failures.add(
            mixinName
                + " shadows missing method "
                + targetClass.name.replace('/', '.')
                + "#"
                + method.name
                + method.desc);
      }
      validateAccessorOrInvoker(mixinName, method, targetClass, failures);
      for (AnnotationNode annotation : annotations(method)) {
        if (isInjectionAnnotation(annotation)) {
          for (String selector : stringValues(annotation, "method")) {
            validateMethodSelector(mixinName, selector, targetClass, failures);
            if (INJECT_DESC.equals(annotation.desc)) {
              validateInjectHandler(mixinName, method, selector, targetClass, failures);
            }
          }
          validateAtTargets(mixinName, annotation, failures);
        }
      }
    }
  }

  private void validateAccessorOrInvoker(
      String mixinName, MethodNode method, ClassNode targetClass, List<String> failures) {
    AnnotationNode accessor =
        findAnnotation(method.visibleAnnotations, method.invisibleAnnotations, ACCESSOR_DESC);
    if (accessor != null) {
      String fieldName = stringValue(accessor, "value");
      if (!fieldName.isEmpty() && findField(targetClass, fieldName, null) == null) {
        failures.add(
            mixinName
                + " accesses missing field "
                + targetClass.name.replace('/', '.')
                + "#"
                + fieldName);
      }
    }

    AnnotationNode invoker =
        findAnnotation(method.visibleAnnotations, method.invisibleAnnotations, INVOKER_DESC);
    if (invoker != null) {
      String methodName = stringValue(invoker, "value");
      if (!methodName.isEmpty() && findMethod(targetClass, methodName, null) == null) {
        failures.add(
            mixinName
                + " invokes missing method "
                + targetClass.name.replace('/', '.')
                + "#"
                + methodName);
      }
    }
  }

  private void validateMethodSelector(
      String mixinName, String selector, ClassNode targetClass, List<String> failures) {
    int descriptorStart = selector.indexOf('(');
    String methodName = descriptorStart >= 0 ? selector.substring(0, descriptorStart) : selector;
    int ownerEnd = methodName.lastIndexOf(';');
    if (ownerEnd >= 0) {
      methodName = methodName.substring(ownerEnd + 1);
    }
    String descriptor = descriptorStart >= 0 ? selector.substring(descriptorStart) : null;
    if (findMethod(targetClass, methodName, descriptor) == null) {
      failures.add(
          mixinName
              + " injects missing method "
              + targetClass.name.replace('/', '.')
              + "#"
              + methodName
              + (descriptor == null ? "" : descriptor));
    }
  }

  private void validateInjectHandler(
      String mixinName,
      MethodNode handler,
      String selector,
      ClassNode targetClass,
      List<String> failures) {
    int descriptorStart = selector.indexOf('(');
    String methodName = descriptorStart >= 0 ? selector.substring(0, descriptorStart) : selector;
    String descriptor = descriptorStart >= 0 ? selector.substring(descriptorStart) : null;
    List<MethodNode> candidates = findMethods(targetClass, methodName, descriptor);
    if (candidates.isEmpty()) {
      return;
    }

    Type[] handlerArguments = Type.getArgumentTypes(handler.desc);
    int callbackIndex = -1;
    for (int i = 0; i < handlerArguments.length; i++) {
      String className = handlerArguments[i].getClassName();
      if (className.equals("org.spongepowered.asm.mixin.injection.callback.CallbackInfo")
          || className.equals(
              "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable")) {
        callbackIndex = i;
        break;
      }
    }
    if (callbackIndex < 0) {
      failures.add(mixinName + " has @Inject handler without CallbackInfo: " + handler.name);
      return;
    }

    for (MethodNode candidate : candidates) {
      Type[] targetArguments = Type.getArgumentTypes(candidate.desc);
      boolean targetArgumentsCompatible =
          callbackIndex == 0
              || (callbackIndex == targetArguments.length
                  && Arrays.equals(
                      Arrays.copyOf(handlerArguments, callbackIndex), targetArguments));
      boolean callbackCompatible =
          Type.getReturnType(candidate.desc).equals(Type.VOID_TYPE)
              || handlerArguments[callbackIndex]
                  .getClassName()
                  .equals("org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable");
      if (targetArgumentsCompatible && callbackCompatible) {
        return;
      }
    }

    failures.add(
        mixinName
            + " has incompatible @Inject handler "
            + handler.name
            + handler.desc
            + " for "
            + targetClass.name.replace('/', '.')
            + "#"
            + selector);
  }

  private void validateAtTargets(
      String mixinName, AnnotationNode injectionAnnotation, List<String> failures) {
    for (AnnotationNode at : annotationValues(injectionAnnotation, "at")) {
      if (!AT_DESC.equals(at.desc)) {
        continue;
      }
      String target = stringValue(at, "target");
      if (target.isEmpty() || target.charAt(0) != 'L') {
        continue;
      }
      int ownerEnd = target.indexOf(';');
      if (ownerEnd < 0) {
        continue;
      }
      String owner = target.substring(1, ownerEnd);
      String member = target.substring(ownerEnd + 1);
      ClassNode ownerClass = readClass(owner);
      if (ownerClass == null) {
        failures.add(mixinName + " has @At target with missing owner " + owner.replace('/', '.'));
        continue;
      }

      int methodDescriptorStart = member.indexOf('(');
      if (methodDescriptorStart >= 0) {
        String methodName = member.substring(0, methodDescriptorStart);
        String descriptor = member.substring(methodDescriptorStart);
        if (findMethod(ownerClass, methodName, descriptor) == null) {
          failures.add(
              mixinName
                  + " has @At target for missing method "
                  + owner.replace('/', '.')
                  + "#"
                  + member);
        }
      } else {
        int fieldDescriptorStart = member.indexOf(':');
        if (fieldDescriptorStart >= 0) {
          String fieldName = member.substring(0, fieldDescriptorStart);
          String descriptor = member.substring(fieldDescriptorStart + 1);
          if (findField(ownerClass, fieldName, descriptor) == null) {
            failures.add(
                mixinName
                    + " has @At target for missing field "
                    + owner.replace('/', '.')
                    + "#"
                    + member);
          }
        }
      }
    }
  }

  private MethodNode findMethod(ClassNode type, String name, String descriptor) {
    List<MethodNode> methods = findMethods(type, name, descriptor);
    return methods.isEmpty() ? null : methods.getFirst();
  }

  private List<MethodNode> findMethods(ClassNode type, String name, String descriptor) {
    List<MethodNode> methods = new ArrayList<>();
    for (MethodNode method : type.methods) {
      if (method.name.equals(name) && (descriptor == null || method.desc.equals(descriptor))) {
        methods.add(method);
      }
    }
    return methods;
  }

  private FieldNode findField(ClassNode type, String name, String descriptor) {
    ClassNode current = type;
    while (current != null) {
      for (FieldNode field : current.fields) {
        if (field.name.equals(name) && (descriptor == null || field.desc.equals(descriptor))) {
          return field;
        }
      }
      current = current.superName == null ? null : readClass(current.superName);
    }
    return null;
  }

  private ClassNode readRequiredClass(String internalName) throws IOException {
    ClassNode result = readClass(internalName);
    assertNotNull(result, "Missing class resource " + internalName);
    return result;
  }

  private ClassNode readClass(String internalName) {
    if (classCache.containsKey(internalName)) {
      return classCache.get(internalName);
    }
    try (InputStream stream = resource(internalName + ".class")) {
      if (stream == null) {
        return null;
      }
      ClassNode node = new ClassNode();
      new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
      classCache.put(internalName, node);
      return node;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read class " + internalName, e);
    }
  }

  private static InputStream resource(String path) {
    return MixinTargetValidationTest.class.getClassLoader().getResourceAsStream(path);
  }

  private static AnnotationNode findAnnotation(ClassNode type, String descriptor) {
    return findAnnotation(type.visibleAnnotations, type.invisibleAnnotations, descriptor);
  }

  private static AnnotationNode findAnnotation(
      List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
    for (AnnotationNode annotation : concatenate(visible, invisible)) {
      if (descriptor.equals(annotation.desc)) {
        return annotation;
      }
    }
    return null;
  }

  private static List<AnnotationNode> annotations(MethodNode method) {
    return concatenate(method.visibleAnnotations, method.invisibleAnnotations);
  }

  private static List<AnnotationNode> concatenate(
      List<AnnotationNode> first, List<AnnotationNode> second) {
    List<AnnotationNode> annotations = new ArrayList<>();
    if (first != null) {
      annotations.addAll(first);
    }
    if (second != null) {
      annotations.addAll(second);
    }
    return annotations;
  }

  private static boolean isInjectionAnnotation(AnnotationNode annotation) {
    return annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
        || annotation.desc.startsWith("Lcom/llamalad7/mixinextras/injector/");
  }

  private static List<String> mixinTargets(AnnotationNode mixinAnnotation) {
    List<String> targets = new ArrayList<>();
    for (Object value : values(mixinAnnotation, "value")) {
      if (value instanceof Type type) {
        targets.add(type.getInternalName());
      }
    }
    for (Object value : values(mixinAnnotation, "targets")) {
      if (value instanceof String target) {
        targets.add(target.replace('.', '/'));
      }
    }
    return targets;
  }

  private static List<String> stringValues(AnnotationNode annotation, String key) {
    List<String> strings = new ArrayList<>();
    for (Object value : values(annotation, key)) {
      if (value instanceof String string) {
        strings.add(string);
      }
    }
    return strings;
  }

  private static List<AnnotationNode> annotationValues(AnnotationNode annotation, String key) {
    List<AnnotationNode> annotations = new ArrayList<>();
    for (Object value : values(annotation, key)) {
      if (value instanceof AnnotationNode node) {
        annotations.add(node);
      }
    }
    return annotations;
  }

  private static List<?> values(AnnotationNode annotation, String key) {
    if (annotation.values == null) {
      return List.of();
    }
    for (int i = 0; i < annotation.values.size(); i += 2) {
      if (key.equals(annotation.values.get(i))) {
        Object value = annotation.values.get(i + 1);
        return value instanceof List<?> list ? list : List.of(value);
      }
    }
    return List.of();
  }

  private static String stringValue(AnnotationNode annotation, String key) {
    for (Object value : values(annotation, key)) {
      if (value instanceof String string) {
        return string;
      }
    }
    return "";
  }

  private static boolean booleanValue(AnnotationNode annotation, String key) {
    for (Object value : values(annotation, key)) {
      if (value instanceof Boolean bool) {
        return bool;
      }
    }
    return false;
  }
}
