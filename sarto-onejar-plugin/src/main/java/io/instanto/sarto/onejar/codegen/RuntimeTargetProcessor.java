/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.sarto.onejar.codegen;

import io.instanto.sarto.onejar.RuntimeTarget;
import io.instanto.sarto.onejar.StaticRuntimeBinding;
import io.instanto.sarto.onejar.StaticRuntimeBindings;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/** Writes explicit runtime metadata into a versioned, CDI-independent classpath index. */
@SupportedAnnotationTypes({
  "io.instanto.sarto.onejar.RuntimeTarget",
  "io.instanto.sarto.onejar.StaticRuntimeBinding",
  "io.instanto.sarto.onejar.StaticRuntimeBindings"
})
@SupportedOptions(RuntimeTargetProcessor.ORIGIN_OPTION)
public final class RuntimeTargetProcessor extends AbstractProcessor {
  public static final String INDEX_RESOURCE = "META-INF/sarto/runtime-targets.properties";
  static final String ORIGIN_OPTION = "sarto.target.origin";

  private final Map<EntryKey, RuntimeTarget.Kind> entries = new LinkedHashMap<>();
  private final Set<String> generatedStaticBindings = new java.util.LinkedHashSet<>();
  private boolean written;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnvironment) {
    super.init(processingEnvironment);
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    for (Element element : roundEnv.getElementsAnnotatedWith(RuntimeTarget.class)) {
      RuntimeTarget target = element.getAnnotation(RuntimeTarget.class);
      if (target != null) {
        collect(element, target.value());
      }
    }
    Set<Element> staticOwners = new java.util.LinkedHashSet<>();
    staticOwners.addAll(roundEnv.getElementsAnnotatedWith(StaticRuntimeBinding.class));
    staticOwners.addAll(roundEnv.getElementsAnnotatedWith(StaticRuntimeBindings.class));
    for (Element element : staticOwners) {
      if (element instanceof TypeElement owner) {
        generateStaticBindings(owner);
      }
    }
    if (roundEnv.processingOver() && !written && !entries.isEmpty()) {
      writeIndex();
    }
    return false;
  }

  private void generateStaticBindings(TypeElement owner) {
    String binaryName = processingEnv.getElementUtils().getBinaryName(owner).toString();
    if (!generatedStaticBindings.add(binaryName)) {
      return;
    }
    String ownerPackage =
        processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
    Map<RuntimeTarget.Kind, List<StaticBindingModel>> bindings = new LinkedHashMap<>();
    for (StaticRuntimeBinding binding : owner.getAnnotationsByType(StaticRuntimeBinding.class)) {
      TypeMirror contract = typeMirror(binding::contract);
      TypeMirror implementation = typeMirror(binding::implementation);
      if (!processingEnv.getTypeUtils().isAssignable(implementation, contract)) {
        error(owner, implementation + " is not assignable to static binding contract " + contract);
        continue;
      }
      if (!binding.name().matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
        error(owner, "invalid static binding method name " + binding.name());
        continue;
      }
      String instantiation = instantiationSpelling(owner, ownerPackage, implementation);
      if (instantiation == null) {
        continue;
      }
      bindings
          .computeIfAbsent(binding.target(), ignored -> new ArrayList<>())
          .add(new StaticBindingModel(binding.name(), contract.toString(), instantiation));
    }
    for (Map.Entry<RuntimeTarget.Kind, List<StaticBindingModel>> target : bindings.entrySet()) {
      writeStaticBindings(owner, target.getKey(), target.getValue());
    }
  }

  /**
   * Returns the source spelling the generated {@code new} expression can use for the
   * implementation, or {@code null} after reporting why it can never be instantiated there.
   *
   * <p>The generated bindings class lives in the annotated owner's package and constructs the
   * implementation with a no-argument call, so the implementation must be a non-abstract class (or
   * record) that is itself accessible there with an accessible no-argument constructor. Reporting
   * this here keeps the failure on the annotation instead of surfacing later as an uncompilable
   * generated file.
   */
  private String instantiationSpelling(
      TypeElement owner, String ownerPackage, TypeMirror implementation) {
    Element element = processingEnv.getTypeUtils().asElement(implementation);
    if (!(element instanceof TypeElement type)
        || (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD)) {
      error(
          owner,
          "static binding implementation " + implementation + " must be a non-abstract class");
      return null;
    }
    if (type.getModifiers().contains(Modifier.ABSTRACT)) {
      error(
          owner,
          "static binding implementation " + implementation + " must be a non-abstract class");
      return null;
    }
    if (type.getNestingKind().isNested() && !type.getModifiers().contains(Modifier.STATIC)) {
      error(
          owner,
          "static binding implementation "
              + implementation
              + " must be a static nested class to be constructed");
      return null;
    }
    if (!isAccessibleFrom(type, ownerPackage)) {
      error(
          owner,
          "static binding implementation "
              + implementation
              + " is not accessible from "
              + (ownerPackage.isEmpty() ? "the default package" : ownerPackage));
      return null;
    }
    List<ExecutableElement> constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
    if (!constructors.isEmpty()
        && constructors.stream()
            .noneMatch(
                constructor ->
                    constructor.getParameters().isEmpty()
                        && isAccessibleFrom(constructor, ownerPackage))) {
      error(
          owner,
          "static binding implementation "
              + implementation
              + " must declare an accessible no-argument constructor");
      return null;
    }
    return processingEnv.getTypeUtils().erasure(implementation).toString();
  }

  /**
   * Whether generated code in {@code packageName} can name and use the element: public, or
   * package-private in the same package. Protected counts as package access because the generated
   * class is not a subclass. Every enclosing type must satisfy the same rule.
   */
  private boolean isAccessibleFrom(Element element, String packageName) {
    for (Element current = element;
        current != null && !(current instanceof PackageElement);
        current = current.getEnclosingElement()) {
      Set<Modifier> modifiers = current.getModifiers();
      if (modifiers.contains(Modifier.PRIVATE)) {
        return false;
      }
      if (!modifiers.contains(Modifier.PUBLIC)
          && !processingEnv
              .getElementUtils()
              .getPackageOf(current)
              .getQualifiedName()
              .contentEquals(packageName)) {
        return false;
      }
    }
    return true;
  }

  private void writeStaticBindings(
      TypeElement owner, RuntimeTarget.Kind target, List<StaticBindingModel> bindings) {
    String packageName =
        processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
    String targetName = target == RuntimeTarget.Kind.JVM ? "Jvm" : "TeaVm";
    String simpleName = owner.getSimpleName() + targetName + "Bindings";
    String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    bindings.sort(Comparator.comparing(StaticBindingModel::name));
    Set<String> names = new java.util.HashSet<>();
    try (Writer writer =
        processingEnv.getFiler().createSourceFile(qualifiedName, owner).openWriter()) {
      if (!packageName.isEmpty()) {
        writer.write("package " + packageName + ";\n\n");
      }
      writer.write("/** Generated CDI-independent bindings for " + target + ". */\n");
      writer.write("public final class " + simpleName + " {\n");
      writer.write("  private " + simpleName + "() {}\n\n");
      for (StaticBindingModel binding : bindings) {
        if (!names.add(binding.name())) {
          error(owner, "duplicate " + target + " static binding method " + binding.name());
          continue;
        }
        writer.write(
            "  public static "
                + binding.contract()
                + " "
                + binding.name()
                + "() {\n"
                + "    return new "
                + binding.instantiation()
                + "();\n"
                + "  }\n");
      }
      writer.write("}\n");
    } catch (IOException | RuntimeException failure) {
      error(owner, "could not generate " + qualifiedName + ": " + failure.getMessage());
    }
  }

  private TypeMirror typeMirror(TypeSupplier supplier) {
    try {
      supplier.get();
      throw new IllegalStateException("Static binding type did not provide a mirror");
    } catch (MirroredTypeException mirrored) {
      return mirrored.getTypeMirror();
    }
  }

  private void error(Element element, String message) {
    processingEnv
        .getMessager()
        .printMessage(Diagnostic.Kind.ERROR, "SARTO-STATIC-BINDING: " + message, element);
  }

  private void collect(Element element, RuntimeTarget.Kind target) {
    EntryKey key;
    if (element instanceof PackageElement packageElement) {
      key = new EntryKey(EntryKind.PACKAGE, packageElement.getQualifiedName().toString());
    } else if (element instanceof TypeElement typeElement) {
      key =
          new EntryKey(
              EntryKind.CLASS,
              processingEnv.getElementUtils().getBinaryName(typeElement).toString());
    } else if (element instanceof ExecutableElement method
        && element.getKind() == ElementKind.METHOD) {
      TypeElement owner = (TypeElement) method.getEnclosingElement();
      key =
          new EntryKey(
              EntryKind.METHOD,
              processingEnv.getElementUtils().getBinaryName(owner)
                  + "#"
                  + method.getSimpleName()
                  + method.asType());
    } else if (element instanceof VariableElement field && element.getKind() == ElementKind.FIELD) {
      TypeElement owner = (TypeElement) field.getEnclosingElement();
      key =
          new EntryKey(
              EntryKind.MEMBER,
              processingEnv.getElementUtils().getBinaryName(owner) + "#" + field.getSimpleName());
    } else {
      processingEnv
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "SARTO-TARGET-INDEX: unsupported @RuntimeTarget element " + element.getKind(),
              element);
      return;
    }
    RuntimeTarget.Kind previous = entries.putIfAbsent(key, target);
    if (key.kind() == EntryKind.METHOD || key.kind() == EntryKind.MEMBER) {
      processingEnv
          .getMessager()
          .printMessage(
              Diagnostic.Kind.NOTE,
              "SARTO-TARGET-INDEX: @RuntimeTarget on methods and fields is not supported and"
                  + " has no effect; move the member to a target-owned class",
              element);
    }
    if (previous != null && previous != target) {
      processingEnv
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "SARTO-TARGET-INDEX: conflicting targets for " + key.name(),
              element);
    }
  }

  private void writeIndex() {
    written = true;
    String origin = processingEnv.getOptions().getOrDefault(ORIGIN_OPTION, "current-compilation");
    List<Map.Entry<EntryKey, RuntimeTarget.Kind>> sorted = new ArrayList<>(entries.entrySet());
    sorted.sort(
        Comparator.comparing(
                (Map.Entry<EntryKey, RuntimeTarget.Kind> entry) -> entry.getKey().kind())
            .thenComparing(entry -> entry.getKey().name()));
    try (Writer writer =
        processingEnv
            .getFiler()
            .createResource(StandardLocation.CLASS_OUTPUT, "", INDEX_RESOURCE)
            .openWriter()) {
      writer.write("format=1\n");
      writer.write("origin=" + encoded(origin) + "\n");
      writer.write("entries=" + sorted.size() + "\n");
      for (int i = 0; i < sorted.size(); i++) {
        Map.Entry<EntryKey, RuntimeTarget.Kind> entry = sorted.get(i);
        String prefix = "entry." + i + ".";
        writer.write(prefix + "kind=" + entry.getKey().kind() + "\n");
        writer.write(prefix + "name=" + encoded(entry.getKey().name()) + "\n");
        writer.write(prefix + "target=" + entry.getValue() + "\n");
      }
    } catch (IOException | RuntimeException failure) {
      processingEnv
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              "SARTO-TARGET-INDEX: could not write "
                  + INDEX_RESOURCE
                  + ": "
                  + failure.getMessage());
    }
  }

  private static String encoded(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private enum EntryKind {
    CLASS,
    METHOD,
    MEMBER,
    PACKAGE
  }

  private record EntryKey(EntryKind kind, String name) {}

  private record StaticBindingModel(String name, String contract, String instantiation) {}

  @FunctionalInterface
  private interface TypeSupplier {
    Class<?> get();
  }
}
