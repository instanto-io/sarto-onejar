# Sarto OneJar

Sarto OneJar lets one Java library serve both the JVM and TeaVM. Mark
runtime-specific packages with `@RuntimeTarget`, keep shared code unmarked,
and use `@StaticRuntimeBinding` when one portable contract needs a different
implementation in each runtime. Its processor records those choices and its
TeaVM transformer removes the JVM-only links from browser output.

## Modules

| Module | Purpose |
| --- | --- |
| `sarto-onejar-api` | Annotations plus the index contract. Dependency-free. |
| `sarto-onejar-plugin` | Index generation, static bindings, TeaVM pruning. Use on javac's processor path and in the TeaVM build. |

## One JAR, three source roots

A unified module keeps three source trees side by side:

| Root | Holds | Example |
|---|---|---|
| `src/main/java` | Portable code: runs on both runtimes, references neither side | Containers, shared SPIs |
| `src/jre/java` | JVM-only code: host adapters, thread pools, blocking I/O | A JVM host adapter |
| `src/teavm/java` | TeaVM-only code: browser adapters, JSO interop | A browser entry helper |

Join them with `build-helper-maven-plugin` (`add-source` for the two extra
roots), then **one `javac` invocation compiles all three roots into a single
JAR**, with matching `-sources` and `-javadoc` JARs that must contain every
entry exactly once. Everything ends up in the JAR: the JVM runs all of it,
and the TeaVM compile sees all of it and then removes the JVM side.

## Mark every package

Each side gets its marker in a `package-info.java` at the appropriate
package, for example:

```java
@io.instanto.sarto.onejar.RuntimeTarget(
    io.instanto.sarto.onejar.RuntimeTarget.Kind.JVM)
package com.example.app.jvm;
```

Those markers feed the runtime-target index
(`META-INF/sarto/runtime-targets.properties`), stamped with the module's own
`groupId:artifactId:version` as its origin, so conflicting indexes from two
JARs can name their owners. A packaging test then cross-checks the artefact
itself: every packaged class must have a matching index entry with the
expected target, and entries must be unique.

Mark **every** package, including nested ones: package lookup is exact-match,
so a marker on `com.example.jvm` does not cover
`com.example.jvm.internal`, and an unmarked package defaults to portable,
which fails silently toward inclusion. A `TYPE`-level mark prunes correctly
but fights the packaging cross-check, which looks classes up by package, so
prefer moving the class. `src/main` packages are never marked: unmarked
means portable, and there is no portable kind.

## Deciding where a new class goes

Each class belongs to one runtime, or is portable, which is where most code
lives.

1. **Could it run in a browser with no changes?** Put it in `src/main/java`,
   no marker.
2. **Does it touch threads, sockets, files, or other JVM-only APIs?** Put it
   in `src/jre/java`, under a JVM-marked package. If portable code needs the
   *capability*, expose a portable façade in `main` and select the
   implementation with a binding. Never reference the `jre` class from
   TeaVM-reachable code: the boundary test catches it, or failing that, the
   TeaVM build does.
3. **Does it touch the DOM, JSO, or browser APIs?** Put it in
   `src/teavm/java`, mirror image of the above.
4. **Does a role need to exist on both?** Write a portable contract in `main`
   plus one implementation per side, selected by a binding. Never one class
   with per-runtime methods.

Tests follow the same rules: test applications can use platform-specific
mocks in the same way.

## Static bindings

```java
@StaticRuntimeBinding(name = "transport", contract = Transport.class,
    implementation = JvmTransport.class, target = RuntimeTarget.Kind.JVM)
@StaticRuntimeBinding(name = "transport", contract = Transport.class,
    implementation = TeaVmTransport.class, target = RuntimeTarget.Kind.TEAVM)
final class AppBindings {}
```

The processor generates `AppBindingsJvmBindings.transport()` and
`AppBindingsTeaVmBindings.transport()` in the same package. Each returns a
`Transport` and constructs only its own target's implementation. The
implementation must be a concrete class assignable to the contract, with a
constructor the generated class can reach; anything else fails compilation.

## When things go wrong

| Symptom | Likely cause | Fix |
|---|---|---|
| TeaVM build fails on a missing type you marked JVM-only | Portable or TeaVM-marked code still references it | Remove the reference, or move the caller behind a binding |
| Info note: method or field target not selectable | `@RuntimeTarget` on a method or field | Move the member to a target-owned class |
| TeaVM fails inside a library you do not own | Undeclared foreign packages pulled in as dispatch candidates | Declare the foreign packages for their owning target |

## Connect the tools

Add `sarto-onejar-api` to the library and place `sarto-onejar-plugin` on
javac's annotation-processor path. Register the plugin's TeaVM transformer,
`io.instanto.sarto.onejar.teavm.JvmTargetPruningTransformer`, in the browser
build, with `sarto-onejar-plugin` on the TeaVM classpath. The generated runtime-target index travels in the
library JAR, so applications using that JAR do not need to list its packages
again. Use the same OneJar version for the API and plugin.

The [module POMs](pom.xml) show the wiring.

## License

Sarto OneJar is available under the [Apache License, Version 2.0](LICENSE).
