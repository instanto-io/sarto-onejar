# Sarto OneJar

One JAR for two runtimes. Sarto OneJar is **opt-in** build machinery for
library builders who publish a single artefact that executes on both the JVM
and the browser (TeaVM). You exercise the option with two pieces:

1. **`sarto-onejar-api`** — annotate: `@RuntimeTarget` marks which packages
   or classes belong to the JVM and which belong to TeaVM (unmarked code is
   portable); `@StaticRuntimeBinding` declares per-target implementations
   behind one portable contract.
2. **`sarto-onejar-plugin`** — build: its annotation processor writes the
   versioned runtime-target index and generates the per-target bindings; its
   TeaVM transformer removes the JVM-designated links from the browser
   compile.

Pruning is subtractive only and TeaVM-compile only. It never substitutes:
alternatives come from explicit bindings, never implicitly.

## Modules

| Module | Purpose |
| --- | --- |
| `sarto-onejar-api` | Annotations plus the index contract. Dependency-free. |
| `sarto-onejar-plugin` | Index generation, static bindings, TeaVM pruning. Processor path and provided TeaVM wiring. Jar packaging until the first mojo lands. |

## The problem, in one paragraph

Your library ships as one JAR. On the JVM, that JAR runs normally:
everything in it works. In the browser, TeaVM compiles that JAR ahead of
time into JavaScript. It needs a closed world, it chokes on JVM-only APIs
such as file access or thread pools, and everything it can reach ends up in
the download. The machinery here lets one artefact serve both: each runtime
sees only its side.

## The core idea: mark, select, prune

Three tools, three jobs. Do not mix them up:

1. **Mark ownership** with `@RuntimeTarget`. You label which packages or
   classes belong to the JVM and which belong to TeaVM. Unmarked code is
   portable and goes everywhere.
2. **Select alternatives** with `@StaticRuntimeBinding`. When the *same
   role* needs to exist on both runtimes (say, a transport), you write two
   implementations and declare which serves which target. This is the *only*
   substitution mechanism. Nothing substitutes automatically.
3. **Prune the rest** with the TeaVM transformer. When TeaVM compiles, the
   links the library builder designated JVM-only are removed from its world:
   their methods, fields, and place in the type hierarchy are neutralised so
   they cannot leak into dispatch or reflection.

Notice what pruning does *not* do: it never replaces anything. Marking a
class JVM-only deletes it from the browser build. If browser-reachable code
still references it, the TeaVM build fails. That failure is a guardrail, but
it arrives late and speaks TeaVM, so the real skill is structuring your code
so it never happens (see "The rules" below).

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
entry exactly once. So yes: *everything* ends up in the jar. The JVM runs
all of it. The TeaVM compile sees all of it and then deletes the JVM side.

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
expected target, entries must be unique, and compiler tooling, mock engines,
and application-startup services must *not* be in a library JAR at all.

Mark **every** package, including nested ones: package lookup is exact-match,
so a marker on `com.example.jvm` does not cover
`com.example.jvm.internal`, and an unmarked package defaults to portable,
which fails silently toward inclusion. A `TYPE`-level mark prunes correctly
but fights the packaging cross-check, which looks classes up by package, so
prefer moving the class. `src/main` packages are never marked: unmarked
means portable, and there is no portable kind.

## What the compiler does not do for you

Because all three roots compile together, **the compiler will happily let
portable code import a JVM-only class**. Nothing turns red in the IDE. The
partition is enforced one step later, on the packaged JAR, by
dependency-direction rules (run them as an integration test during `verify`,
until the plugin's `check` goal lands):

- portable code (the `main` root) may depend on **neither** JVM-only **nor**
  TeaVM-only packages;
- `teavm`-root code may not touch JVM-only packages (`..jvm..`,
  `java.awt`, `javax.swing`, `java.net.http`, Mockito, and friends);
- `jre`-root code may not touch TeaVM-only packages (`..teavm..`,
  `org.teavm..`, and friends).

Each rule wants a negative self-check proving it fires. So the feedback loop
is: write the import, run `verify`, get a named violation pointing at the
exact reference, long before any TeaVM run. Package naming matters here, not
just the directory: the boundary is tracked per class by the source root
that declared it, with package patterns covering well-known third-party APIs
on each side. Put a class in the wrong root and the test tells you; put it
in the right root but outside a marked package and the marker may not cover
it.

## The mental model: two views of one artefact

- **The JVM view**: everything is present. Thread pools, file access, host
  adapters, all there, all working. Nothing is ever pruned on this side.
- **The TeaVM view**: the JVM side has been deleted. Only portable code plus
  TeaVM-marked code exists.

Write every class so it makes sense in *both* views. The question to ask
about each class is not "what does it do?" but "which views may see it?" If
the answer is "both, except this one method", restructure until the answer
is per class, because method-level marks are recorded but have no effect
(see below).

## The rules

1. **One class, one runtime** (plus portable, which is the default and where
   most code lives).
2. **Never reference JVM-only code from TeaVM-reachable code.** The boundary
   test catches it, or failing that, the TeaVM build does.
3. **If a role must exist on both runtimes, provide both implementations
   explicitly**, through a `@StaticRuntimeBinding`. There is no implicit
   fallback.
4. **Tests are a third audience.** The same setup covers test applications
   using platform-specific mocks.

## Deciding where a new class goes

1. **Could it run in a browser with no changes?** Put it in `src/main/java`,
   no marker.
2. **Does it touch threads, sockets, files, or other JVM-only APIs?** Put it
   in `src/jre/java`, under a JVM-marked package. If portable code needs the
   *capability*, expose a portable façade in `main` and select the
   implementation with a binding. Do not import the `jre` class directly;
   the boundary test forbids it.
3. **Does it touch the DOM, JSO, or browser APIs?** Put it in
   `src/teavm/java`, mirror image of the above.
4. **Does a role need to exist on both?** Write a portable contract in `main`
   plus one implementation per side, selected explicitly. Never one class
   with per-runtime methods.

## Use cases the library builder meets

- **Pure portable library.** No markers, no bindings, no plugin beyond the
  ordinary build. OneJar stays out of the way; adopt nothing.
- **Library with runtime-specific internals.** Mark the sides, partition the
  roots, wire the processor path so the index is written, register the
  transformer in the TeaVM build, and keep the boundary test green. This is
  the standard adoption, and the checklist in the previous section is the
  whole of it.
- **Library offering one role on both runtimes.** Add a
  `@StaticRuntimeBinding` pair per role (one contract, one implementation
  per target, each reached through its generated per-target accessor) so
  callers on each side resolve without naming a runtime.
- **Application consuming unified libraries.** Nothing extra: depend on the
  libraries, build the TeaVM app with the transformer registered, and start
  through the matching adapter. The libraries' indexes travel inside their
  JARs.

## Dealing with third-party libraries

Three situations, three responses:

1. **Portable library (pure Java).** Do nothing. TeaVM compiles whatever of
   it is reachable; the JVM runs all of it. The only duty is transitive:
   make sure nothing it pulls into the reachable closure is JVM-only.
2. **JVM-only library used only from your JVM side.** Keep it out of the
   TeaVM-reachable closure. The boundary test enforces the package lists;
   hand-written references outside any generated selection are caught by the
   TeaVM build. No declaration needed as long as nothing reachable names it.
3. **JVM-only library leaking into TeaVM's world without a direct
   reference.** The sharp case is a third-party class implementing a
   *portable* interface: TeaVM considers all classpath implementors as
   dispatch candidates, so the JVM class gets pulled in with its internals
   and the compile fails far from the cause. Since the code cannot be
   annotated or renamed, declare its packages for their owning target. The
   supported mechanism today is a shadow source tree carrying only
   `package-info.java` markers that mirror the foreign packages; a
   first-class declared-packages option is planned and will accept the same
   package patterns the boundary test already uses.

## When things go wrong

| Symptom | Likely cause | Fix |
|---|---|---|
| TeaVM build fails on a missing type you marked JVM-only | Portable or TeaVM-marked code still references it | Remove the reference, or move the caller behind a binding |
| Info note: method or field target not selectable | `@RuntimeTarget` on a method or field | Move the member to a target-owned class (see below) |
| TeaVM fails inside a library you do not own | Undeclared foreign packages pulled in as dispatch candidates | Declare the foreign packages for their owning target |

## Why there is no method-level pruning

This is deliberate, not a gap. Subtracting individual members could be
flagged at compile time: a build check can find every reference to a removed
member. But the outcome is difficult to reason about. The same class would
exist in both views with different shapes, forcing every call site, override,
and test to be read twice, once per runtime, with overrides making the
behaviour non-local. Class-level pruning keeps the rule total and checkable:
a class is either present in a view or absent. If a class feels like it needs
per-member targets, that feeling is the design telling you to split it.

Patching compiled third-party archives (classpath shadowing with explicit
opt-in) is a separate build-internal concern, not a developer authoring
construct, and it does not change this rule.

## Consuming

```xml
<properties>
  <sarto-onejar.version>0.1.0-SNAPSHOT</sarto-onejar.version>
</properties>

<dependencies>
  <dependency>
    <groupId>io.instanto</groupId>
    <artifactId>sarto-onejar-api</artifactId>
    <version>${sarto-onejar.version}</version>
  </dependency>
</dependencies>
```

Put the processor on the annotation-processor path and point the TeaVM build
at the transformer:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>io.instanto</groupId>
        <artifactId>sarto-onejar-plugin</artifactId>
        <version>${sarto-onejar.version}</version>
      </path>
    </annotationProcessorPaths>
    <compilerArgs>
      <arg>-Asarto.target.origin=${project.groupId}:${project.artifactId}:${project.version}</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

Snapshots publish to GitHub Packages:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/instanto-io/sarto-onejar</url>
  </repository>
</repositories>
```

GitHub Packages requires credentials even for reads; add a matching server
to `~/.m2/settings.xml` with your `GITHUB_ACTOR` and a token that can read
packages.

## Build this repository

```shell
./mvnw verify
```

The shared parent (`io.instanto:instanto-org-pom`) is installed from
`instanto-io/instanto-poms` in CI; see `.github/workflows/build.yml`.

## License

Sarto OneJar is available under the [Apache License, Version 2.0](LICENSE).
