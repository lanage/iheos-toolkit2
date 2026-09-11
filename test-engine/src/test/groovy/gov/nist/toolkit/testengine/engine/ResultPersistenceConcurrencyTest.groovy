package gov.nist.toolkit.testengine.engine

import gov.nist.toolkit.installation.server.Installation
import gov.nist.toolkit.installation.shared.TestSession
import gov.nist.toolkit.registrymetadata.client.DocumentEntry
import gov.nist.toolkit.results.ResultBuilder
import gov.nist.toolkit.results.client.Result
import gov.nist.toolkit.results.client.StepResult
import gov.nist.toolkit.results.client.TestInstance
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Concurrency coverage for ResultPersistence.
 *
 * Two distinct questions:
 *  1. Is the shared static ObjectMapper safe under concurrent use? (Jackson documents yes once
 *     configured - this proves it for our configuration and object graph rather than trusting it.)
 *  2. Is the opportunistic upgrade-on-read safe when several threads read the SAME legacy .ser at
 *     once? Each of them will try to write the same .json path. If that write is not atomic, a
 *     concurrent reader can observe a half-written file - which would surface as a swallowed
 *     exception and a silently-missing test in the GUI, i.e. exactly the failure mode this whole
 *     change exists to remove.
 */
class ResultPersistenceConcurrencyTest extends Specification {

    static final int THREADS = 16
    static final int ROUNDS = 12

    Path tempCache

    def setup() {
        tempCache = Files.createTempDirectory("resultPersistenceConcurrency")
        new File(tempCache.toFile(), "environment/default").mkdirs()
        Installation.instance().externalCache(tempCache.toFile())
    }

    def cleanup() {
        Installation.instance().externalCache(null)
        deleteRecursively(tempCache.toFile())
    }

    // A deliberately chunky Result - a bigger document widens the write window, so a
    // non-atomic write is far more likely to actually be observed torn rather than passing by luck.
    private static Result buildBigResult(String testId) {
        Result result = ResultBuilder.RESULT(new TestInstance(testId))
        (1..40).each { s ->
            StepResult stepResult = new StepResult()
            stepResult.section = "section${s}"
            stepResult.stepName = "step${s}"
            stepResult.status = true
            (1..25).each { d ->
                DocumentEntry de = new DocumentEntry()
                de.id = "urn:uuid:${s}-${d}"
                de.uniqueId = "1.2.3.${s}.${d}"
                de.title = "Document ${s}/${d} with some padding text to make the payload larger"
                de.comments = "lorem ipsum dolor sit amet " * 6
                stepResult.getMetadata().docEntries.add(de)
            }
            result.addStepResult(stepResult)
        }
        return result
    }

    private static void writeLegacySer(Result result, File file) {
        file.parentFile.mkdirs()
        FileOutputStream fos = new FileOutputStream(file)
        ObjectOutputStream out = new ObjectOutputStream(fos)
        out.writeObject(result)
        out.close()
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return
        if (file.isDirectory())
            file.listFiles()?.each { deleteRecursively(it) }
        file.delete()
    }

    private File resultsFile(String testId, String ext) {
        return new File(tempCache.toFile(), "TestLogCache/default/Results/${testId}.${ext}")
    }

    def "concurrent writes of DIFFERENT results through the shared ObjectMapper all succeed"() {
        given:
        TestSession session = new TestSession("default")
        ExecutorService pool = Executors.newFixedThreadPool(THREADS)
        CountDownLatch gate = new CountDownLatch(1)

        when:
        List<Future<Boolean>> futures = (1..THREADS).collect { int i ->
            pool.submit({
                gate.await()
                new ResultPersistence().write(buildBigResult("CONC-W-${i}"), session)
                return true
            } as Callable<Boolean>)
        }
        gate.countDown()
        List<Boolean> outcomes = futures.collect { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        then: "no thread threw"
        outcomes.every { it }

        and: "every file is present and independently re-readable"
        (1..THREADS).every { int i ->
            Result r = new ResultPersistence().read(new TestInstance("CONC-W-${i}"), [], session)
            r != null && r.stepResults.size() == 40
        }
    }

    def "concurrent reads of the same legacy .ser never observe a torn upgraded .json"() {
        given: "a legacy .ser and no .json, repeated over several rounds to expose a race"
        TestSession session = new TestSession("default")
        List<String> failures = Collections.synchronizedList(new ArrayList<String>())

        when:
        (1..ROUNDS).each { int round ->
            String testId = "CONC-R-${round}"
            writeLegacySer(buildBigResult(testId), resultsFile(testId, "ser"))
            assert !resultsFile(testId, "json").exists()

            ExecutorService pool = Executors.newFixedThreadPool(THREADS)
            CountDownLatch gate = new CountDownLatch(1)
            // STAGGERED arrival is the whole point. If every thread is released at the same
            // instant they all clear the jsonFile.exists() check before anyone starts writing,
            // and the dangerous interleaving - a reader arriving DURING an in-flight upgrade
            // write - never happens. Staggering makes later threads land mid-write.
            List<Future<Result>> futures = (0..<THREADS).collect { int t ->
                pool.submit({
                    gate.await()
                    if (t > 0) Thread.sleep(t as long)
                    return new ResultPersistence().read(new TestInstance(testId), [], session)
                } as Callable<Result>)
            }
            gate.countDown()
            futures.each { Future<Result> f ->
                try {
                    Result r = f.get(60, TimeUnit.SECONDS)
                    if (r == null)
                        failures.add("round ${round}: read() returned null")
                    else if (r.stepResults.size() != 40)
                        failures.add("round ${round}: truncated Result - ${r.stepResults.size()} of 40 stepResults")
                } catch (Exception e) {
                    Throwable root = e
                    while (root.cause != null) root = root.cause
                    failures.add("round ${round}: ${root.class.name}: ${root.message}")
                }
            }
            pool.shutdown()
        }

        then: "not one of the concurrent readers failed or saw partial data"
        failures.isEmpty()

        and: "the upgraded json survives a final clean read"
        (1..ROUNDS).every { int round ->
            Result r = new ResultPersistence().read(new TestInstance("CONC-R-${round}"), [], session)
            r != null && r.stepResults.size() == 40
        }
    }

    def "a reader never sees a partially written file while another thread rewrites the same result"() {
        given: "an existing result that one thread rewrites repeatedly while others read it"
        TestSession session = new TestSession("default")
        String testId = "CONC-RW"
        Result big = buildBigResult(testId)
        new ResultPersistence().write(big, session)
        List<String> failures = Collections.synchronizedList(new ArrayList<String>())
        ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1)
        CountDownLatch gate = new CountDownLatch(1)
        long deadline = System.currentTimeMillis() + 6000

        when: "one writer loops over the same path while readers hammer it"
        Future<?> writer = pool.submit({
            gate.await()
            while (System.currentTimeMillis() < deadline) {
                try {
                    new ResultPersistence().write(big, session)
                } catch (Exception e) {
                    // a transient lock collision on the final rename is acceptable; a torn file is not
                    failures.add("writer: ${e.class.name}: ${e.message}")
                }
            }
        } as Callable<Object>)

        List<Future<?>> readers = (1..THREADS).collect {
            pool.submit({
                gate.await()
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Result r = new ResultPersistence().read(new TestInstance(testId), [], session)
                        if (r == null)
                            failures.add("reader: read() returned null")
                        else if (r.stepResults.size() != 40)
                            failures.add("reader: torn Result - ${r.stepResults.size()} of 40 stepResults")
                    } catch (Exception e) {
                        Throwable root = e
                        while (root.cause != null) root = root.cause
                        failures.add("reader: ${root.class.name}: ${(root.message ?: '').take(120)}")
                    }
                }
            } as Callable<Object>)
        }
        gate.countDown()
        writer.get(60, TimeUnit.SECONDS)
        readers.each { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        then: "no reader ever observed a torn or missing document"
        failures.findAll { it.startsWith("reader:") }.isEmpty()
    }

    def "a truncated json on disk is a read failure, not a silent partial Result"() {
        given: "a complete result, then a file truncated to simulate a torn write"
        TestSession session = new TestSession("default")
        Result big = buildBigResult("TRUNCATED")
        new ResultPersistence().write(big, session)
        File json = resultsFile("TRUNCATED", "json")
        byte[] full = json.bytes
        json.bytes = Arrays.copyOf(full, (int) (full.length * 0.6))

        when:
        new ResultPersistence().read(new TestInstance("TRUNCATED"), [], session)

        then: "it throws rather than silently returning an incomplete Result"
        thrown(Exception)
    }
}
