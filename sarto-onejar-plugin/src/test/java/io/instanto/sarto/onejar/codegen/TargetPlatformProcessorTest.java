/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.sarto.onejar.codegen;

import static com.google.common.truth.Truth.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class TargetPlatformProcessorTest {
  @Test
  void writesDeterministicPackageClassAndMethodTargets() throws IOException {
    Compilation compilation =
        Compiler.javac()
            .withOptions("-Asarto.target.origin=io.instanto:fixture:1")
            .withProcessors(new TargetPlatformProcessor())
            .compile(
                JavaFileObjects.forSourceString(
                    "example.jvm.package-info",
                    """
                    @io.instanto.sarto.onejar.TargetPlatform(
                        io.instanto.sarto.onejar.TargetPlatform.Kind.JVM)
                    package example.jvm;
                    """),
                JavaFileObjects.forSourceString(
                    "example.Targets",
                    """
                    package example;
                    import io.instanto.sarto.onejar.TargetPlatform;
                    @TargetPlatform(TargetPlatform.Kind.TEAVM)
                    class Targets {
                      @TargetPlatform(TargetPlatform.Kind.JVM)
                      String produce() { return "value"; }
                    }
                    """));

    assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    String index =
        compilation
            .generatedFile(
                StandardLocation.CLASS_OUTPUT, "", TargetPlatformProcessor.INDEX_RESOURCE)
            .orElseThrow()
            .getCharContent(false)
            .toString();
    assertThat(index).contains("format=1");
    assertThat(index).contains("origin=" + encoded("io.instanto:fixture:1"));
    assertThat(index).contains("name=" + encoded("example.Targets"));
    assertThat(index).contains("name=" + encoded("example.Targets#produce()java.lang.String"));
    assertThat(index).contains("name=" + encoded("example.jvm"));
    assertThat(index).contains("target=JVM");
    assertThat(index).contains("target=TEAVM");
    CompilationSubject.assertThat(compilation)
        .hadNoteContaining("methods and fields is not supported and has no effect");
  }

  @Test
  void generatesStructurallyTargetSpecificStaticBindings() throws IOException {
    Compilation compilation =
        Compiler.javac()
            .withProcessors(new TargetPlatformProcessor())
            .compile(
                JavaFileObjects.forSourceString(
                    "example.Transport",
                    """
                    package example;
                    interface Transport {}
                    """),
                JavaFileObjects.forSourceString(
                    "example.JvmTransport",
                    """
                    package example;
                    public final class JvmTransport implements Transport {}
                    """),
                JavaFileObjects.forSourceString(
                    "example.TeaVmTransport",
                    """
                    package example;
                    public final class TeaVmTransport implements Transport {}
                    """),
                JavaFileObjects.forSourceString(
                    "example.ApplicationBindings",
                    """
                    package example;
                    import io.instanto.sarto.onejar.TargetPlatform;
                    import io.instanto.sarto.onejar.PlatformBinding;
                    @PlatformBinding(
                        name = "transport",
                        contract = Transport.class,
                        implementation = JvmTransport.class,
                        target = TargetPlatform.Kind.JVM)
                    @PlatformBinding(
                        name = "transport",
                        contract = Transport.class,
                        implementation = TeaVmTransport.class,
                        target = TargetPlatform.Kind.TEAVM)
                    final class ApplicationBindings {}
                    """));

    assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    String jvm =
        compilation
            .generatedSourceFile("example.ApplicationBindingsJvmBindings")
            .orElseThrow()
            .getCharContent(false)
            .toString();
    String teaVm =
        compilation
            .generatedSourceFile("example.ApplicationBindingsTeaVmBindings")
            .orElseThrow()
            .getCharContent(false)
            .toString();
    assertThat(jvm).contains("return new example.JvmTransport()");
    assertThat(jvm).doesNotContain("TeaVmTransport");
    assertThat(teaVm).contains("return new example.TeaVmTransport()");
    assertThat(teaVm).doesNotContain("JvmTransport");
  }

  @Test
  void rejectsNonInstantiableStaticBindingImplementations() {
    Compilation abstractImplementation =
        compileBindings(
            """
            package example;
            import io.instanto.sarto.onejar.TargetPlatform;
            import io.instanto.sarto.onejar.PlatformBinding;
            abstract class AbstractPort implements Port {}
            @PlatformBinding(
                name = "port",
                contract = Port.class,
                implementation = AbstractPort.class,
                target = TargetPlatform.Kind.JVM)
            final class AbstractBindings {}
            """);
    assertThat(abstractImplementation.status()).isEqualTo(Compilation.Status.FAILURE);
    CompilationSubject.assertThat(abstractImplementation)
        .hadErrorContaining("must be a non-abstract class");

    Compilation interfaceImplementation =
        compileBindings(
            """
            package example;
            import io.instanto.sarto.onejar.TargetPlatform;
            import io.instanto.sarto.onejar.PlatformBinding;
            @PlatformBinding(
                name = "port",
                contract = Port.class,
                implementation = Port.class,
                target = TargetPlatform.Kind.JVM)
            final class InterfaceBindings {}
            """);
    assertThat(interfaceImplementation.status()).isEqualTo(Compilation.Status.FAILURE);
    CompilationSubject.assertThat(interfaceImplementation)
        .hadErrorContaining("must be a non-abstract class");
  }

  @Test
  void rejectsStaticBindingImplementationsWithoutAccessibleConstructors() {
    Compilation privateConstructor =
        compileBindings(
            """
            package example;
            import io.instanto.sarto.onejar.TargetPlatform;
            import io.instanto.sarto.onejar.PlatformBinding;
            final class PrivatePort implements Port {
              private PrivatePort() {}
            }
            @PlatformBinding(
                name = "port",
                contract = Port.class,
                implementation = PrivatePort.class,
                target = TargetPlatform.Kind.JVM)
            final class PrivateBindings {}
            """);
    assertThat(privateConstructor.status()).isEqualTo(Compilation.Status.FAILURE);
    CompilationSubject.assertThat(privateConstructor)
        .hadErrorContaining("accessible no-argument constructor");

    Compilation argumentsOnly =
        compileBindings(
            """
            package example;
            import io.instanto.sarto.onejar.TargetPlatform;
            import io.instanto.sarto.onejar.PlatformBinding;
            final class ConfiguredPort implements Port {
              ConfiguredPort(String name) {}
            }
            @PlatformBinding(
                name = "port",
                contract = Port.class,
                implementation = ConfiguredPort.class,
                target = TargetPlatform.Kind.JVM)
            final class ConfiguredBindings {}
            """);
    assertThat(argumentsOnly.status()).isEqualTo(Compilation.Status.FAILURE);
    CompilationSubject.assertThat(argumentsOnly)
        .hadErrorContaining("accessible no-argument constructor");

    Compilation innerImplementation =
        compileBindings(
            """
            package example;
            import io.instanto.sarto.onejar.TargetPlatform;
            import io.instanto.sarto.onejar.PlatformBinding;
            final class Outer {
              public final class InnerPort implements Port {}
            }
            @PlatformBinding(
                name = "port",
                contract = Port.class,
                implementation = Outer.InnerPort.class,
                target = TargetPlatform.Kind.JVM)
            final class InnerBindings {}
            """);
    assertThat(innerImplementation.status()).isEqualTo(Compilation.Status.FAILURE);
    CompilationSubject.assertThat(innerImplementation)
        .hadErrorContaining("must be a static nested class");
  }

  private static Compilation compileBindings(String ownerSource) {
    return Compiler.javac()
        .withProcessors(new TargetPlatformProcessor())
        .compile(
            JavaFileObjects.forSourceString(
                "example.Port", "package example;\ninterface Port {}\n"),
            JavaFileObjects.forSourceString("example.Bindings", ownerSource));
  }

  private static String encoded(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
