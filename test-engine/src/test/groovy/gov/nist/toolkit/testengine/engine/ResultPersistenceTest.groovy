package gov.nist.toolkit.testengine.engine

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * Covers the JSON-first / .ser-fallback-with-opportunistic-upgrade behavior added to
 * ResultPersistence. Each test that exercises the real write()/read()/delete() path points
 * Installation at a throwaway External Cache under a JUnit/Spock temp directory - the same
 * technique used by it-tests/.../InitializeForCat.groovy, the only other direct caller of
 * Installation#externalCache(File) in this repo.
 */
class ResultPersistenceTest extends Specification {

    Path tempCache
    ResultPersistence persistence = new ResultPersistence()

    def setup() {
        tempCache = Files.createTempDirectory("resultPersistenceTest")
        // PropertyServiceManager#getTestLogCache() throws unless ExternalCacheManager#validate()
        // passes, which requires <EC>/environment/default to exist - this is the minimal fixture
        // that satisfies it without pulling in the full war/toolkitx resource tree.
        new File(tempCache.toFile(), "environment/default").mkdirs()
        Installation.instance().externalCache(tempCache.toFile())
    }

    def cleanup() {
        Installation.instance().externalCache(null)
        deleteRecursively(tempCache.toFile())
    }

    // ---- helpers --------------------------------------------------------------------------

    private static Result buildSampleResult(String testId) {
        Result result = ResultBuilder.RESULT(new TestInstance(testId))
        StepResult stepResult = new StepResult()
        stepResult.section = "s1"
        stepResult.stepName = "step1"
        stepResult.status = true
        DocumentEntry de = new DocumentEntry()
        de.id = "urn:uuid:1234"
        de.uniqueId = "1.2.3.4"
        de.title = "A Document"
        stepResult.getMetadata().docEntries.add(de)
        result.addStepResult(stepResult)
        return result
    }

    // Same visibility config as ResultPersistence's private mapper() - duplicated here
    // deliberately (see the plan's rationale for not extracting a shared factory yet) so this
    // test proves the object graph round-trips under that exact policy, independent of
    // ResultPersistence's own internals.
    private static ObjectMapper sameConfigMapper() {
        ObjectMapper mapper = new ObjectMapper()
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
        mapper.setVisibility(PropertyAccessor.GETTER, Visibility.NONE)
        mapper.setVisibility(PropertyAccessor.IS_GETTER, Visibility.NONE)
        mapper.setVisibility(PropertyAccessor.SETTER, Visibility.NONE)
        return mapper
    }

    // Fabricates a pre-migration file: the exact ObjectOutputStream sequence
    // ResultPersistence used before this change, so the fallback test simulates real legacy data.
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

    // ---- tests ------------------------------------------------------------------------------

    def "Result round-trips through the same Jackson configuration ResultPersistence uses"() {
        given:
        Result original = buildSampleResult("ITI-41")
        File tmp = File.createTempFile("result", ".json")
        tmp.deleteOnExit()
        ObjectMapper mapper = sameConfigMapper()

        when:
        mapper.writeValue(tmp, original)
        Result roundTripped = mapper.readValue(tmp, Result.class)

        then:
        roundTripped.testInstance.getId() == original.testInstance.getId()
        roundTripped.pass == original.pass
        roundTripped.stepResults.size() == 1
        roundTripped.stepResults[0].section == "s1"
        roundTripped.stepResults[0].stepName == "step1"
        roundTripped.stepResults[0].getMetadata().docEntries.size() == 1
        roundTripped.stepResults[0].getMetadata().docEntries[0].uniqueId == "1.2.3.4"
        roundTripped.stepResults[0].getMetadata().docEntries[0].title == "A Document"
    }

    def "write() persists as JSON and read() returns it back through the real External Cache path"() {
        given:
        Result original = buildSampleResult("ITI-18")
        TestSession session = new TestSession("default")

        when:
        persistence.write(original, session)

        then:
        File expected = new File(tempCache.toFile(), "TestLogCache/default/Results/ITI-18.json")
        expected.exists()

        when:
        Result reread = persistence.read(original.testInstance, [], session)

        then:
        reread != null
        reread.testInstance.getId() == "ITI-18"
        reread.stepResults[0].getMetadata().docEntries[0].uniqueId == "1.2.3.4"
    }

    def "read() falls back to a legacy .ser file when no .json exists, and upgrades it to .json"() {
        given:
        Result original = buildSampleResult("ITI-42")
        TestSession session = new TestSession("default")
        File serFile = new File(tempCache.toFile(), "TestLogCache/default/Results/ITI-42.ser")
        File jsonFile = new File(tempCache.toFile(), "TestLogCache/default/Results/ITI-42.json")
        writeLegacySer(original, serFile)

        expect:
        !jsonFile.exists()

        when:
        Result reread = persistence.read(original.testInstance, [], session)

        then:
        reread != null
        reread.testInstance.getId() == "ITI-42"
        reread.stepResults[0].getMetadata().docEntries[0].uniqueId == "1.2.3.4"

        and: "the legacy file was opportunistically upgraded"
        jsonFile.exists()

        when: "reading again now hits the JSON path directly"
        Result rereadAgain = persistence.read(original.testInstance, [], session)

        then:
        rereadAgain.testInstance.getId() == "ITI-42"
    }

    def "a corrupt .json falls back to the legacy .ser instead of shadowing it, and self-heals"() {
        given: "a legacy .ser plus a damaged .json for the same test - the state left behind by a torn write"
        Result original = buildSampleResult("ITI-44")
        TestSession session = new TestSession("default")
        File serFile = new File(tempCache.toFile(), "TestLogCache/default/Results/ITI-44.ser")
        File jsonFile = new File(tempCache.toFile(), "TestLogCache/default/Results/ITI-44.json")
        writeLegacySer(original, serFile)
        jsonFile.parentFile.mkdirs()
        jsonFile.text = '{"testInstance":{"id":"ITI-44"},"stepResu'   // truncated mid-token

        when: "the result is read"
        Result reread = persistence.read(original.testInstance, [], session)

        then: "the intact legacy copy is used rather than the damaged JSON"
        reread != null
        reread.testInstance.getId() == "ITI-44"
        reread.stepResults[0].getMetadata().docEntries[0].uniqueId == "1.2.3.4"

        and: "the damaged JSON has been repaired from the recovered copy"
        Result afterHeal = new ResultPersistence().read(original.testInstance, [], session)
        afterHeal != null
        afterHeal.stepResults[0].getMetadata().docEntries[0].uniqueId == "1.2.3.4"

        and: "so a later read no longer needs the legacy file at all"
        serFile.delete()
        new ResultPersistence().read(original.testInstance, [], session) != null
    }

    def "a corrupt .json with no legacy .ser still surfaces as an error rather than pretending success"() {
        given:
        TestSession session = new TestSession("default")
        File jsonFile = new File(tempCache.toFile(), "TestLogCache/default/Results/ITI-45.json")
        jsonFile.parentFile.mkdirs()
        jsonFile.text = '{"testInstance":{"id":"ITI-45"},"stepResu'

        when:
        persistence.read(new TestInstance("ITI-45"), [], session)

        then:
        thrown(Exception)
    }

    def "a result with a string field larger than Jackson's default 5MB read cap survives a round trip"() {
        given: "a rawResults payload past jackson-core 2.15's 5,000,000 char default maxStringLength"
        TestSession session = new TestSession("default")
        Result original = ResultBuilder.RESULT(new TestInstance("BIG-STRING"))
        StepResult stepResult = new StepResult()
        stepResult.section = "s1"
        stepResult.stepName = "step1"
        stepResult.status = true
        // StepResult.rawResults holds a whole logged SOAP body in production - a Provide & Register
        // with an inline base64 document routinely exceeds this.
        stepResult.rawResults = "x" * 6_000_000
        original.addStepResult(stepResult)

        when: "it is written and read back"
        persistence.write(original, session)
        Result reread = persistence.read(original.testInstance, [], session)

        then: "the write is not a one-way door - it reads back intact"
        reread != null
        reread.stepResults[0].rawResults.length() == 6_000_000
    }

    def "write() retires a superseded legacy .ser so a later fallback cannot resurrect a stale run"() {
        given: "a legacy .ser holding an OLD failing run for a test id"
        TestSession session = new TestSession("default")
        Result oldRun = buildSampleResult("ITI-46")
        oldRun.pass = false
        File serFile = new File(tempCache.toFile(), "TestLogCache/default/Results/ITI-46.ser")
        writeLegacySer(oldRun, serFile)

        when: "the test is re-run and now passes"
        Result newRun = buildSampleResult("ITI-46")
        newRun.pass = true
        persistence.write(newRun, session)

        then: "the superseded legacy file is gone, so it can never become a stale fallback"
        !serFile.exists()

        and: "the current result is what is read"
        persistence.read(new TestInstance("ITI-46"), [], session).pass
    }

    def "delete() removes both the JSON and a stray legacy .ser file for the same test id"() {
        given:
        Result original = buildSampleResult("ITI-43")
        TestSession session = new TestSession("default")
        File jsonFile = new File(tempCache.toFile(), "TestLogCache/default/Results/ITI-43.json")
        File serFile = new File(tempCache.toFile(), "TestLogCache/default/Results/ITI-43.ser")
        persistence.write(original, session)
        writeLegacySer(original, serFile)

        expect:
        jsonFile.exists()
        serFile.exists()

        when:
        persistence.delete(original.testInstance, session, [])

        then:
        !jsonFile.exists()
        !serFile.exists()
    }
}
