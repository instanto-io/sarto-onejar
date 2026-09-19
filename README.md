# Sarto OneJar

One JAR for two runtimes. Sarto OneJar is **opt-in** build tooling for modules
that ship a single artefact serving both the JVM and the browser (TeaVM). You
exercise the option with two pieces:

1. **`sarto-onejar-api`** — annotate: `@RuntimeTarget` marks which packages or
   classes belong to the JVM and which belong to TeaVM (unmarked code is
   portable); `@StaticRuntimeBinding` declares per-target implementations
   behind one portable contract.
2. **`sarto-onejar-plugin`** — build: its annotation processor writes the
   versioned runtime-target index and generates the per-target bindings; its
   TeaVM transformer prunes JVM-owned classes from the browser compile.

Pruning is subtractive only and TeaVM-compile only. It never substitutes:
alternatives come from explicit bindings, never implicitly. See
[`docs/RUNTIME_TARGETS.md`](docs/RUNTIME_TARGETS.md) for the full developer
guide: partitioning portable, `jre`, and `teavm` sources, the two-views mental
model, third-party libraries, troubleshooting, and the deliberate limits
(no method-level pruning).

## Modules

| Module | Purpose |
| --- | --- |
| `sarto-onejar-api` | Annotations plus the index contract. Dependency-free. |
| `sarto-onejar-plugin` | Index generation, static bindings, TeaVM pruning. Processor path and provided TeaVM wiring. |

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

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/instanto-io/sarto-onejar</url>
  </repository>
</repositories>
```

Snapshots publish to GitHub Packages (see above) on every `main` build.
GitHub Packages requires credentials even for reads; add a matching server to
`~/.m2/settings.xml` with your `GITHUB_ACTOR` and a token that can read
packages.

## Build this repository

```shell
./mvnw verify
```

The shared parent (`io.instanto:instanto-org-pom`) is installed from
`instanto-io/instanto-poms` in CI; see `.github/workflows/build.yml`.

## License

Sarto OneJar is available under the [Apache License, Version 2.0](LICENSE).
