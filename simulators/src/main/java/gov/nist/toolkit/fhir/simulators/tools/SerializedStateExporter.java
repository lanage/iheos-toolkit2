package gov.nist.toolkit.fhir.simulators.tools;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One-shot export of the toolkit's Java-serialized (.ser) persistent state to JSON, using
 * whichever build of the model classes this tool is run with. Run it against an External Cache
 * BEFORE a model class change ships - the resulting JSON survives even after the old .ser files
 * become unreadable by the new code.
 *
 * <p>What gets exported, found by walking {@code <externalCache>}:
 * <ul>
 *   <li>{@code simdb/**&#47;reg_db.ser} - root gov.nist.toolkit.fhir.simulators.sim.reg.store.MetadataCollection
 *       (the registry simulator's document/submission-set/folder/association index)</li>
 *   <li>{@code simdb/**&#47;rep_db.ser} - root gov.nist.toolkit.fhir.simulators.sim.rep.DocumentCollection
 *       (the repository simulator's stored-document index)</li>
 *   <li>{@code TestLogCache/**&#47;Results/*.ser} - root gov.nist.toolkit.results.client.Result
 *       (persisted test run results)</li>
 *   <li>{@code TestLogCache/**&#47;<testId>/<testId>} (no file extension) - root
 *       gov.nist.toolkit.testenginelogging.client.LogMapDTO (persisted test/utility run logs)</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> exported:
 * <ul>
 *   <li>per-event {@code date.ser} files - the serialized root is a bare {@code java.util.Date},
 *       a JDK class untouched by any model change in this codebase.</li>
 *   <li>Dashboard {@code <site>.ser} status files - written by DashboardDaemon to a standalone,
 *       CLI-supplied directory outside the External Cache. They are disposable polling snapshots
 *       regenerated on every sweep, not accumulated results.</li>
 * </ul>
 *
 * <p>This tool intentionally does <b>not</b> go through RegIndex, RepIndex, ResultPersistence, or
 * JavaSerializationIO - every one of those classes catches a failed restore and silently substitutes
 * an empty/default object, which is exactly the data loss this tool exists to prevent. Every file is
 * opened directly with a plain {@link ObjectInputStream} so a corrupt or version-mismatched file is
 * reported as a failure in the manifest, never silently dropped.
 *
 * <p>Usage - see the README.md next to this class for the full walkthrough (including how to build
 * a classpath from an exploded WAR):
 * <pre>
 *   java -cp "&lt;classpath&gt;" gov.nist.toolkit.fhir.simulators.tools.SerializedStateExporter \
 *        &lt;externalCacheDir&gt; &lt;outputDir&gt; [--dry-run]
 * </pre>
 */
public class SerializedStateExporter {

    public static void main(String[] args) {
        List<String> positional = new ArrayList<>();
        boolean dryRun = false;
        for (String arg : args) {
            if ("--dry-run".equals(arg))
                dryRun = true;
            else
                positional.add(arg);
        }
        if (positional.size() != 2) {
            System.err.println("Usage: SerializedStateExporter <externalCacheDir> <outputDir> [--dry-run]");
            System.err.println("  externalCacheDir  the toolkit's External_Cache directory (contains simdb/, TestLogCache/, ...)");
            System.err.println("  outputDir         directory to write the exported JSON tree into (created if it doesn't exist)");
            System.err.println("  --dry-run         read and validate every .ser file but write nothing");
            System.exit(1);
        }

        File ecDir = new File(positional.get(0));
        File outDir = new File(positional.get(1));

        if (!ecDir.isDirectory()) {
            System.err.println("External Cache directory does not exist or is not a directory: " + ecDir);
            System.exit(1);
        }
        if (!dryRun) {
            if (!outDir.exists() && !outDir.mkdirs()) {
                System.err.println("Cannot create output directory: " + outDir);
                System.exit(1);
            }
            if (!outDir.isDirectory()) {
                System.err.println("Output path exists and is not a directory: " + outDir);
                System.exit(1);
            }
        }

        int exitCode = new SerializedStateExporter(ecDir, outDir, dryRun).run();
        System.exit(exitCode);
    }

    private final File ecDir;
    private final File outDir;
    private final boolean dryRun;
    private final ObjectMapper mapper;
    private final List<Map<String, Object>> manifestEntries = new ArrayList<>();
    private int failedCount = 0;

    SerializedStateExporter(File ecDir, File outDir, boolean dryRun) {
        this.ecDir = ecDir;
        this.outDir = outDir;
        this.dryRun = dryRun;
        this.mapper = buildMapper();
    }

    /**
     * Dump every field regardless of access modifier and ignore getters/setters entirely, so that
     * a business-logic getter (e.g. DocEntryCollection#getAll(), which walks parent/delta chains
     * and filters deleted entries) never runs - and never shapes the export - as a side effect of
     * serialization. This mirrors the convention the repo already uses for its one prior
     * Java-serialization-to-JSON migration (see sim-common's SimulatorConfigIoJackson.groovy /
     * the @JsonAutoDetect annotation on SimulatorConfig and friends).
     */
    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.IS_GETTER, Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.SETTER, Visibility.NONE);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        return mapper;
    }

    int run() {
        System.out.println("External Cache : " + ecDir.getAbsolutePath());
        System.out.println("Output         : " + (dryRun ? "(dry run - nothing will be written)" : outDir.getAbsolutePath()));
        System.out.println();

        scanRegistryAndRepositoryIndexes();
        scanTestLogCache();

        int exported = manifestEntries.size() - failedCount;
        System.out.println();
        System.out.println("Scanned : " + manifestEntries.size());
        System.out.println("Exported: " + exported);
        System.out.println("Failed  : " + failedCount);

        if (!dryRun)
            writeManifest();

        if (manifestEntries.isEmpty())
            return 0;
        return failedCount > 0 ? 2 : 0;
    }

    // ---- simdb/**/reg_db.ser, simdb/**/rep_db.ser ------------------------------------------

    private void scanRegistryAndRepositoryIndexes() {
        File simDb = new File(ecDir, "simdb");
        if (!simDb.isDirectory()) {
            System.out.println("(no simdb/ directory under the External Cache - skipping registry/repository indexes)");
            return;
        }
        try (Stream<Path> walk = Files.walk(simDb.toPath())) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.equals("reg_db.ser") || name.equals("rep_db.ser");
                    })
                    .sorted()
                    .forEach(p -> {
                        String category = p.getFileName().toString().equals("reg_db.ser")
                                ? "REGISTRY_INDEX" : "REPOSITORY_INDEX";
                        exportOne(p.toFile(), category, replaceExtension(p, ".json"));
                    });
        } catch (IOException e) {
            System.err.println("Error walking " + simDb + ": " + e.getMessage());
        }
    }

    // ---- TestLogCache/**/Results/*.ser (Result) and TestLogCache/**/<testId>/<testId> (LogMapDTO) --

    private void scanTestLogCache() {
        File testLogCache = new File(ecDir, "TestLogCache");
        if (!testLogCache.isDirectory()) {
            System.out.println("(no TestLogCache/ directory under the External Cache - skipping test results/logs)");
            return;
        }
        try (Stream<Path> walk = Files.walk(testLogCache.toPath())) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        Path parent = p.getParent();
                        if (name.endsWith(".ser")) {
                            // ResultPersistence.getFilePath(): <ts>/Results/<testId>[<section>].ser
                            exportOne(p.toFile(), "TEST_RESULT", replaceExtension(p, ".json"));
                        } else if (parent != null && name.equals(parent.getFileName().toString())) {
                            // JavaSerializationIO.logFile(): <ts>/<testId>/<testId> (no extension -
                            // the file's own name equals its immediate parent directory's name)
                            exportOne(p.toFile(), "TEST_LOG", Paths.get(p.toString() + ".json"));
                        }
                        // everything else under TestLogCache (log.xml, orchestration.properties,
                        // ip.txt, request/response bodies, ...) is already plain text/XML - leave it alone.
                    });
        } catch (IOException e) {
            System.err.println("Error walking " + testLogCache + ": " + e.getMessage());
        }
    }

    private static Path replaceExtension(Path serFile, String newExtension) {
        String name = serFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot == -1) ? name : name.substring(0, dot);
        return serFile.resolveSibling(base + newExtension);
    }

    // ---- read one .ser file, write one .json file, record one manifest entry --------------

    private void exportOne(File sourceFile, String category, Path jsonPath) {
        String relSource = ecDir.toPath().relativize(sourceFile.toPath()).toString().replace(File.separatorChar, '/');
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sourcePath", relSource);
        entry.put("category", category);
        entry.put("sizeBytes", sourceFile.length());

        System.out.println("[" + category + "] " + relSource);

        if (sourceFile.length() == 0) {
            fail(entry, "file is empty (0 bytes)");
            return;
        }

        Object root;
        try (FileInputStream fis = new FileInputStream(sourceFile);
             ObjectInputStream in = new ObjectInputStream(fis)) {
            root = in.readObject();
        } catch (Throwable t) {
            // Deliberately broad: this tool's entire job is to survive whatever a legacy or
            // corrupt .ser file throws - InvalidClassException, ClassNotFoundException,
            // StreamCorruptedException, even a StackOverflowError from a pathological object
            // graph - and keep going with the rest of the External Cache instead of losing the
            // whole export run to one bad file.
            fail(entry, describe(t));
            return;
        }

        entry.put("javaClass", root.getClass().getName());

        String json;
        try {
            json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Throwable t) {
            fail(entry, "deserialized OK but failed to render as JSON: " + describe(t));
            return;
        }

        Path relOut = ecDir.toPath().relativize(jsonPath);
        entry.put("outputPath", relOut.toString().replace(File.separatorChar, '/'));
        entry.put("status", dryRun ? "OK (dry run)" : "OK");

        if (!dryRun) {
            File outFile = new File(outDir, relOut.toString());
            try {
                Files.createDirectories(outFile.getParentFile().toPath());
                Files.write(outFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                fail(entry, "deserialized OK but failed to write JSON file: " + describe(e));
                return;
            }
        }

        manifestEntries.add(entry);
    }

    private void fail(Map<String, Object> entry, String reason) {
        entry.put("status", "FAILED");
        entry.put("error", reason);
        System.out.println("    FAILED: " + reason);
        failedCount++;
        manifestEntries.add(entry);
    }

    private static String describe(Throwable t) {
        return t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }

    private void writeManifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("externalCache", ecDir.getAbsolutePath());
        manifest.put("generatedAt", new Date().toString());
        manifest.put("scanned", manifestEntries.size());
        manifest.put("failed", failedCount);
        manifest.put("entries", manifestEntries);
        File manifestFile = new File(outDir, "export-manifest.json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile, manifest);
            System.out.println("Manifest written to " + manifestFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Could not write manifest to " + manifestFile + ": " + e.getMessage());
        }
    }
}
