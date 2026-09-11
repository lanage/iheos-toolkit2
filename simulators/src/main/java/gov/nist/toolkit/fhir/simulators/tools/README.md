# SerializedStateExporter

A stand-alone, command-line export of the toolkit's Java-serialized (`.ser`) persistent state to
JSON. Run it **once, against production's External Cache, before** deploying a build that changes
the shape of any model class listed below — after that build ships, the old `.ser` files may fail
to deserialize, and today that failure is silent (see [Why this exists](#why-this-exists)).

Source: [SerializedStateExporter.java](SerializedStateExporter.java).

## Contents

- [What it exports](#what-it-exports)
- [Why this exists](#why-this-exists)
- [How it works](#how-it-works)
- [User guide](#user-guide)
- [Reading the output](#reading-the-output)
- [Tips and tricks](#tips-and-tricks)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [Extending the tool](#extending-the-tool)

## What it exports

Walking `<externalCache>`:

| Source | Root class | Written by |
|---|---|---|
| `simdb/**/reg_db.ser` | `gov.nist.toolkit.fhir.simulators.sim.reg.store.MetadataCollection` | `RegIndex` |
| `simdb/**/rep_db.ser` | `gov.nist.toolkit.fhir.simulators.sim.rep.DocumentCollection` | `RepIndex` |
| `TestLogCache/**/Results/*.ser` | `gov.nist.toolkit.results.client.Result` | `ResultPersistence` — **legacy files only, see below** |
| `TestLogCache/**/<testId>/<testId>` (no file extension) | `gov.nist.toolkit.testenginelogging.client.LogMapDTO` | `JavaSerializationIO` |

> **Test results no longer need this tool.** `ResultPersistence` now writes `Results/<testId>.json`
> directly and reads JSON-first with a legacy `.ser` fallback that self-heals each old file the
> first time it is read. This scan matches `*.ser` only, so on an installation running that build
> it will report **zero** test results — not because they are missing, but because they are already
> in the durable format and there is nothing left to convert. The rows above still matter for the
> simulator indexes (`reg_db.ser`/`rep_db.ser`), which are still Java-serialized, and for test
> results on installations that have not yet been read since upgrading.

**Not exported, on purpose:**

| Excluded | Reason |
|---|---|
| `date.ser` (per simulator-transaction event) | Root is a bare `java.util.Date` — a JDK class, immune to any model change in this codebase. |
| Dashboard `<site>.ser` status files | Written by `DashboardDaemon` to a separate, CLI-supplied directory outside the External Cache. Regenerated on every polling sweep — a disposable snapshot, not accumulated results. |
| Utility-run logs under `<warHome>/SessionCache` (`LogIdType.TIME_ID`) | Lives inside the deployed WAR and is already wiped on every redeploy, so it was never durable data. It also uses a different on-disk naming convention than `TestLogCache`, so it isn't picked up by accident either. |
| FHIR `ResourceIndex` | Its intended root, `ResourceCollection`, isn't even `Serializable`, and nothing in the codebase constructs a `ResourceIndex` — it's dead code with no on-disk file to export. |

## Why this exists

None of the code that normally reads these files is safe to use as a backup mechanism, because
every one of them turns a failed restore into silent data loss instead of an error:

- **`RegIndex`**'s constructor catches *any* restore failure and silently builds an **empty**
  `MetadataCollection`, logged only at `FINE` — the exception object itself is never logged, so
  this is indistinguishable from the normal "brand-new simulator" path. `RegIndex.save()` has no
  dirty guard, so the very next successful Register transaction overwrites `reg_db.ser` with that
  empty index. **Permanently.** The original `<uuid>.xml` metadata files on disk are orphaned —
  nothing re-indexes them.
- **`RepIndex`** does the same thing, though it logs one `SEVERE` line first, and `save()` does at
  least guard on a dirty flag, so the stale file survives a little longer.
- **`ResultPersistence`** failures are wrapped into an exception that every production caller
  swallows with an empty `catch` block — a test's results just vanish from the results map with
  zero logging.
- **`JavaSerializationIO`** (test logs) is the one exception that fails loudly (it rethrows), but
  the failure surfaces to the UI as `"Internal Server Error: Cannot find logs for Test %s"` —
  which reads like the file is missing, when the real cause is a class-shape mismatch.

None of these classes have a `readObject`/`resolveClass`/version-migration hook, and every
persisted root pins `serialVersionUID = 1L` (or a fixed constant) with no compatibility strategy
behind it — so *any* field or modifier change to a persisted class, or to one of its superclasses
(`PatientObject`, the superclass of `DocEntry`/`SubSet`/`Fol`, doesn't even declare a
`serialVersionUID`, so its *computed* UID is baked into every `reg_db.ser`), is a potential
break. `SerializedStateExporter` reads every file with a plain `ObjectInputStream` directly — it
never goes near `RegIndex`, `RepIndex`, `ResultPersistence`, or `JavaSerializationIO` — so a
corrupt or version-mismatched file shows up as a `FAILED` entry in its manifest, with the real
exception message, instead of quietly disappearing.

## How it works

### Discovery, not configuration

The tool takes no list of simulators, test sessions, or test IDs — it just walks the External
Cache's `simdb/` and `TestLogCache/` subtrees and pattern-matches file names:

- `simdb/**` — an exact filename match on `reg_db.ser` or `rep_db.ser`.
- `TestLogCache/**` — any file whose name ends in `.ser` (→ `Result`), plus any file whose name is
  **identical to its immediate parent directory's name** (→ `LogMapDTO`). That second rule mirrors
  `JavaSerializationIO.logFile()` exactly: `<TestLogCache>/<testSession>/<testId>/<testId>` — the
  file is named after the test ID, and so is the directory holding it. Everything else under
  `TestLogCache/` (`log.xml`, `orchestration.properties`, `ip.txt`, request/response bodies, …) is
  already plain text or XML and is left alone.

This means the tool needs zero knowledge of `SimId`, `TestSession`, or any of the toolkit's own
bootstrap machinery (`Installation`, `ExternalCacheManager`, `SimDb`) — it never calls any of
them. You point it at a directory and it finds everything itself, including sims and test
sessions that no longer show up correctly through the UI.

### Deserializing without the toolkit's own I/O classes

Each candidate file is opened directly:

```java
try (FileInputStream fis = new FileInputStream(sourceFile);
     ObjectInputStream in = new ObjectInputStream(fis)) {
    root = in.readObject();
}
```

Whatever comes back — `MetadataCollection`, `DocumentCollection`, `Result`, or `LogMapDTO` — is
recorded as-is; the tool doesn't validate that the class matches the category it expected (see
[Reading the output](#reading-the-output) for why that's a *feature*, not a gap).

### Turning it into JSON without calling business logic

The `ObjectMapper` is configured to serialize **fields, not getters**:

```java
mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
mapper.setVisibility(PropertyAccessor.GETTER, Visibility.NONE);
mapper.setVisibility(PropertyAccessor.IS_GETTER, Visibility.NONE);
mapper.setVisibility(PropertyAccessor.SETTER, Visibility.NONE);
```

This matters because these model classes have getters with real business logic behind them —
`DocEntryCollection.getAll()`, for instance, walks a `parent` delta chain and filters out deleted
entries. Left to its defaults, Jackson would call every public getter it can find and serialize
*derived, computed* data instead of a faithful field-for-field dump — and could throw if a getter
assumes runtime state (like a live `RegIndex` reference) that a bare deserialized object doesn't
have. `FIELD`-only visibility sidesteps all of that. This is the same convention the repo already
uses for its one prior Java-serialization-to-JSON migration
(`simctl.ser` → `simctl.json`, see `SimulatorConfigIoJackson.groovy` and the `@JsonAutoDetect`
annotation on `SimulatorConfig`).

One consequence worth knowing: every field on the class is dumped, including ones that were
`transient` on the *original* runtime object but got serialized anyway because a fresh
`ObjectInputStream` read produced a plain Java object with normal fields — for example
`RegObCollection.deleting` (not persisted, but present and empty after restore) will show up in
the JSON as `"deleting": []`. That's expected and harmless; it reflects the real state of the
object exactly as `RegIndex`/`RepIndex` would see it too.

### Fail loud, per file, and keep going

```java
} catch (Throwable t) {
    fail(entry, describe(t));
    return;
}
```

Catching `Throwable` (not just `Exception`) is deliberate: a pathological or truncated object
graph can throw a `StackOverflowError`, which is an `Error`, not an `Exception`. The tool's entire
purpose is to survive whatever one bad file throws — `InvalidClassException`,
`ClassNotFoundException`, `StreamCorruptedException`, an `OutOfMemoryError` on one outsized index —
and keep exporting the rest of the External Cache instead of losing the whole run to a single file.

### Non-destructive by construction

The tool only ever **reads** from `<externalCache>` and **writes** into a separate `<outputDir>`
that mirrors the source layout with `.json` extensions. It never writes into the External Cache,
never deletes anything, and never touches a `.ser` file. Nothing about running this tool — even
repeatedly, even mid-incident — can make your existing data worse.

## User guide

### Prerequisites

1. **The class must be built from code that matches what's on disk.** Either build the standalone
   jar (`mvn -pl simulators -am package -P standalone-tools`, see below — no WAR redeploy needed),
   or, if you'd rather run it from an already-deployed app, redeploy `xdstools2.war` through the
   normal `build-release.sh` / `package-release.sh` flow first so this tool's code is actually
   present in `WEB-INF/classes`. Either way, do this before *every* run — a `.ser` file can only be
   read by the exact class shape that wrote it, so "built once, run forever" doesn't apply here.
2. **Quiesce the app server first**, or stop it outright. Two reasons:
   - `rep_db.ser` is rewritten at the end of *every* simulator transaction (`SimServlet`'s
     `doPost`), and both `reg_db.ser`/`rep_db.ser` are cached in memory for up to 15 minutes — a
     live server's on-disk files can be stale relative to what's cached, or mid-write.
   - Deserializing large indexes holds the full object graph in memory; running this alongside a
     busy production JVM adds memory pressure you don't need during an incident window.
3. **Know your External Cache path** — the `External_Cache` key in `toolkit.properties` (or the
   legacy `Simulator_database_directory` for very old installs).

### Running it as a standalone jar (recommended for a Linux app server)

`SerializedStateExporter` uses the toolkit's own model classes to deserialize `.ser` files —
that's inherent to how Java serialization works and can't be designed around — but nothing says
those classes have to come from a running webapp. `simulators/pom.xml` has an opt-in Maven
profile, `standalone-tools`, that shades everything this tool needs (the `simulators` module's
classes, every dependency it transitively needs, `jackson-databind`) into one self-contained jar
with a `Main-Class` manifest entry. Build it once, from the reactor:

```bash
mvn -pl simulators -am package -P standalone-tools -DskipTests
```

That produces `simulators/target/simulators-<version>-standalone-tools.jar` *in addition to* the
module's normal thin jar (the profile is opt-in specifically so it never affects the default
build or CI — the shade step only runs when `-P standalone-tools` is passed explicitly).

Copy that one file to the Linux server. Nothing else — no `WEB-INF/lib`, no reactor checkout, no
classpath assembly:

```bash
java -jar simulators-<version>-standalone-tools.jar /path/to/external_cache /path/to/export-output --dry-run
```

This is the closest this tool gets to "standalone": one file, `java -jar`, done. It still needs a
JVM on the server (same requirement the app itself already has), and it still needs to be rebuilt
whenever the model classes change — a `.ser` file can only be read by the exact code shape that
matches it, so there's no such thing as a build-once, run-forever version of this tool. Rebuild
and redeploy it as part of your normal release process, the same way you'd rebuild `xdstools2.war`
itself.

### Running it against an exploded WAR instead

If you'd rather not build a separate shaded jar, the exploded WAR already has everything this
tool needs: `WEB-INF/classes` (once the WAR has been rebuilt with this tool's code) and
`WEB-INF/lib/*.jar` (its dependencies, including `jackson-databind`). Point plain `java` at both.

**Linux / macOS (bash):**

```bash
WEBAPP=/path/to/tomcat/webapps/xdstools2
EC=/path/to/external_cache
OUT=/path/to/export-output

java -cp "$WEBAPP/WEB-INF/classes:$WEBAPP/WEB-INF/lib/*" \
     gov.nist.toolkit.fhir.simulators.tools.SerializedStateExporter \
     "$EC" "$OUT" --dry-run
```

**Windows (PowerShell):**

```powershell
$webapp = "C:\path\to\tomcat\webapps\xdstools2"
$ec     = "C:\path\to\external_cache"
$out    = "C:\path\to\export-output"

java -cp "$webapp\WEB-INF\classes;$webapp\WEB-INF\lib\*" `
     gov.nist.toolkit.fhir.simulators.tools.SerializedStateExporter `
     $ec $out --dry-run
```

**Always dry-run first.** `--dry-run` reads and JSON-renders every file it finds and prints the
same summary, but writes nothing under `$OUT`. Once the failure count looks right — ideally 0, or
only files you already know are stale — drop `--dry-run` and run it for real.

### Running it straight from source (no WAR needed)

If you have the Maven reactor checked out on the machine you're exporting from:

```bash
mvn -pl simulators -am dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp "simulators/target/classes:$(cat /tmp/cp.txt)" \
     gov.nist.toolkit.fhir.simulators.tools.SerializedStateExporter \
     /path/to/external_cache /path/to/export-output --dry-run
```

(`mvn -pl simulators -am install -DskipTests` first if `simulators/target/classes` and its
dependencies aren't already built. The repo has no `exec-maven-plugin` wired up, so
`dependency:build-classpath` + a plain `java -cp` is the path of least resistance rather than
`mvn exec:java`.)

### A full run, step by step

```bash
# 1. Dry run - validate everything is readable, write nothing
java -cp "$CP" gov.nist.toolkit.fhir.simulators.tools.SerializedStateExporter "$EC" "$OUT" --dry-run

# 2. Look at "Failed : N" in the summary. If N > 0, see Troubleshooting below.

# 3. Real run
java -cp "$CP" gov.nist.toolkit.fhir.simulators.tools.SerializedStateExporter "$EC" "$OUT"

# 4. Keep the manifest and the JSON tree somewhere durable - off the app server,
#    ideally versioned or timestamped (e.g. export-output-2026-08-29/)
```

## Reading the output

```
<outputDir>/
  export-manifest.json
  simdb/<testSession>/<sim>/<actor>/reg_db.json
  simdb/<testSession>/<sim>/<actor>/rep_db.json
  TestLogCache/<testSession>/Results/<testId>.json
  TestLogCache/<testSession>/<testId>/<testId>.json
```

### The manifest

One entry per file scanned:

```json
{
  "sourcePath": "simdb/default/default__mysim/reg/reg_db.ser",
  "category": "REGISTRY_INDEX",
  "sizeBytes": 1026,
  "javaClass": "gov.nist.toolkit.fhir.simulators.sim.reg.store.MetadataCollection",
  "outputPath": "simdb/default/default__mysim/reg/reg_db.json",
  "status": "OK"
}
```

A failure looks like this — note there's no `javaClass` or `outputPath`, because it never got that
far:

```json
{
  "sourcePath": "TestLogCache/default/Results/ITI-41.ser",
  "category": "TEST_RESULT",
  "sizeBytes": 33,
  "status": "FAILED",
  "error": "java.io.StreamCorruptedException: invalid stream header: 6E6F7420"
}
```

| Field | Meaning |
|---|---|
| `sourcePath` | Path relative to `<externalCache>`, forward-slash normalized regardless of OS. |
| `category` | One of `REGISTRY_INDEX`, `REPOSITORY_INDEX`, `TEST_RESULT`, `TEST_LOG` — which pattern matched the file, not a guarantee of its actual content. |
| `sizeBytes` | Source file size, useful for spotting a suspicious 0-byte or truncated file before you even look at `status`. |
| `javaClass` | The *actual* runtime class Java handed back from `readObject()`. **Compare this against `category`** — see below. |
| `outputPath` | Path relative to `<outputDir>`, present only on success. |
| `status` | `OK`, `OK (dry run)`, or `FAILED`. |
| `error` | Present only on `FAILED` — the exception class and message. |

**Why `javaClass` is worth checking, not just `status`:** the tool classifies files purely by
*name pattern*, not by content — a `category` of `TEST_RESULT` only means "this file ends in
`.ser` under `TestLogCache/`", not "this file definitely contains a `Result`". If `javaClass`
doesn't match what you'd expect for that `category` (a `TEST_RESULT` entry whose `javaClass` isn't
`gov.nist.toolkit.results.client.Result`, say), that's a signal something unexpected landed in
that location — worth a look before you rely on the export.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Completed. Either every file exported cleanly, or nothing matched the scan patterns at all. |
| `1` | Never started — bad arguments, a missing/non-directory External Cache path, or an output path that couldn't be created. |
| `2` | Completed, but at least one file is `FAILED` in the manifest. **Not necessarily a tool bug** — see Troubleshooting. |

Script against these: `0` → proceed with the deploy; `1` → fix your invocation; `2` → open the
manifest and look at every `FAILED` entry before deciding whether it's safe to proceed.

## Tips and tricks

- **Dry-run every time before a real run**, even the tenth time you've done this. It's free (no
  writes) and it's the cheapest possible check that nothing changed under you (someone extended
  the External Cache with a new sim, a disk is slow, etc.) since the last time you looked.

- **Keep every manifest, timestamped, off the app server.** A manifest from before a release is
  your evidence of what existed and was readable at that point in time — useful both as a
  restore point and, after the release, as a diff target (`jq` recipe below) to confirm nothing
  new broke.

- **Query the manifest with `jq`** instead of scrolling it by eye:

  ```bash
  # just the failures
  jq '.entries[] | select(.status=="FAILED")' export-manifest.json

  # counts by category
  jq '.entries | group_by(.category) | map({category: .[0].category, count: length})' export-manifest.json

  # anything whose actual class looks wrong for its category
  jq '.entries[] | select(.category=="TEST_RESULT" and (.javaClass|test("Result$")|not))' export-manifest.json
  ```

- **Diff two manifests across a release** to prove the migration didn't regress anything that used
  to be readable:

  ```bash
  jq -S '[.entries[] | {sourcePath, status}]' before/export-manifest.json > before.json
  jq -S '[.entries[] | {sourcePath, status}]' after/export-manifest.json  > after.json
  diff before.json after.json
  ```

- **A `FAILED` entry doesn't mean this tool is broken — it usually means the file was already
  broken.** This tool reads with the exact same `ObjectInputStream` mechanics as the production
  code; it just doesn't hide the exception. If a file fails here, it would have failed (silently)
  in production too. Treat every `FAILED` entry as a pre-existing data-integrity finding worth
  investigating on its own, not as a reason to distrust the export of everything else.

- **Large External Caches need JVM heap.** Nothing here streams — `readObject()` materializes the
  whole object graph, and `writeValueAsString()` builds the whole JSON document as one `String`
  before it's written to disk. For an installation with a very large `reg_db.ser` (thousands of
  document entries), bump the heap: `java -Xmx2g -cp ...`. If you hit an `OutOfMemoryError`
  mid-run, it's still recorded as a normal `FAILED` entry for that one file (per the "fail loud,
  per file" design) and the run continues — but a heap bump is the real fix.

- **Run order is deterministic.** Every directory walk is `.sorted()` before processing, so two
  runs against the same unchanged External Cache produce byte-identical manifests (modulo the
  `generatedAt` timestamp) — safe to diff, safe to check into a ticket.

- **This is a snapshot, not a live sync.** If simulators keep running between your export and your
  deploy, re-run it right before cutover, not hours earlier.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Error: Could not find or load main class ...` | Classpath doesn't include `WEB-INF/classes`, or you built with `javac` output going somewhere else. | Double-check the `-cp` value actually points at the exploded WAR's `WEB-INF/classes` directory, not its parent. |
| `NoClassDefFoundError: com/fasterxml/jackson/...` | `WEB-INF/lib` is missing `jackson-databind`, or you pointed at a stale/half-exploded WAR. | Confirm `jackson-databind-*.jar` exists under `WEB-INF/lib`. Redeploy if the WAR predates this tool or its pom change. |
| `External Cache directory does not exist or is not a directory` (exit 1) | Wrong path, or a typo'd trailing separator issue. | Check the `External_Cache` value in `toolkit.properties`, not a guess. |
| `(no simdb/ directory under the External Cache - skipping ...)` | You pointed at the wrong directory (e.g. one level too high or too low), or this install genuinely has no simulator data yet. | Confirm `<path>/simdb` and `<path>/TestLogCache` exist as siblings. |
| `status: FAILED`, `error` starts with `java.io.InvalidClassException` | The exact failure mode this tool exists to catch — a class's shape (fields, `serialVersionUID`) no longer matches what wrote the file. If you're running this *before* a model change ships, this means the file was **already** incompatible with the code you're running — investigate separately; it isn't something this migration caused. | Not fixable by this tool. Note it, move on, export what's left. |
| `status: FAILED`, `error` starts with `java.io.StreamCorruptedException` or `java.io.EOFException` | The file isn't a valid (or complete) Java serialization stream — truncated write, disk issue, or it's not actually a `.ser` file despite the name. | Inspect the file directly (`file <path>`, or a hex dump of the first few bytes — a real stream starts with `AC ED`). |
| `status: FAILED`, `error` is `OutOfMemoryError` | One object graph is too large for the current heap. | Re-run with a larger `-Xmx`. |
| Run finishes with `Scanned : 0` | Either you're pointed at the wrong directory, **or** this install has no Java-serialized state left to convert — test results written by a current build are already `.json`, and this scan matches `.ser` only. | Confirm `<path>/simdb` and `<path>/TestLogCache` exist. If they do and are populated, check whether `Results/` already contains `.json` files — in that case zero is the correct answer, not a failure. |
| `Manifest written to ...` doesn't appear | You ran with `--dry-run` (manifest is intentionally not written) — this is expected, not an error. | Drop `--dry-run` for the real run. |

## FAQ

**Does it modify my data?**
No. It only reads from `<externalCache>` and writes into a separate `<outputDir>`. See
[Non-destructive by construction](#non-destructive-by-construction).

**Can I run it while the app server is live?**
You *can*, but it's discouraged — see [Quiesce the app server first](#prerequisites) for the two
specific reasons (in-memory cache staleness, extra memory pressure).

**What happens to files it doesn't recognize?**
Nothing — they're never touched. Only files matching the four patterns in
[What it exports](#what-it-exports) are opened at all.

**Can I import this JSON back into the toolkit later?**
For **test results**, the toolkit reads JSON natively now — `ResultPersistence.read()` tries
`Results/<testId>.json` first, using the same field-visibility contract this tool writes with, so a
result exported here is in the format the running toolkit already consumes.

For the **simulator indexes** (`reg_db.ser`/`rep_db.ser`) and **test logs** (`LogMapDTO`), no
importer exists — nothing reads that JSON back in. Those remain export-only: keep the JSON and the
manifest as a durable record, and build an importer against the new model classes when the model
change is actually designed.

**Does it cover FHIR simulator state?**
Not `reg_db.ser`/`rep_db.ser`-style state — the FHIR side stores each resource as its own
`<id>.json` file already (see `SimDb.storeNewResource`), so there's no `.ser` blob to export there.
The one FHIR class that *does* use Java serialization, `ResourceIndex`, is dead code — nothing
constructs it, and its intended root isn't even `Serializable` — so there's nothing on disk to
find.

**Why doesn't it validate that a `TEST_RESULT`-categorized file is actually a `Result`?**
By design — see [Reading the output](#reading-the-output). The tool records the real
`javaClass` it got back and lets you audit the manifest for mismatches, rather than silently
rejecting (or worse, silently accepting) anything that doesn't match its own naming heuristic.

## Extending the tool

If a future release adds a new persistent `.ser`-backed store, the shape to follow is already in
`SerializedStateExporter`:

1. Add a `scanXxx()` method that walks the relevant subtree and matches on filename (see
   `scanRegistryAndRepositoryIndexes()` for an exact-name match, `scanTestLogCache()` for a
   suffix + parent-directory-name match).
2. Call `exportOne(sourceFile, category, jsonOutputPath)` for each match — it handles
   deserialization, JSON rendering, writing, and manifest bookkeeping uniformly regardless of the
   root class, so no per-type code is needed there.
3. Update the tables in [What it exports](#what-it-exports) and this file's class-level Javadoc.

If you ever *do* need to write a reader for one of these JSON exports (the importer mentioned in
the FAQ), reuse the same `ObjectMapper` visibility configuration
(`buildMapper()`) for `readValue(...)` as well as `writeValueAsString(...)` — Jackson needs the
same field-visibility settings on the way in as on the way out.
