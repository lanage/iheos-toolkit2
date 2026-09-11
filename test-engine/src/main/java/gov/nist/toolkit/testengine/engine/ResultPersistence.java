package gov.nist.toolkit.testengine.engine;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.nist.toolkit.installation.server.Installation;
import gov.nist.toolkit.installation.shared.TestSession;
import gov.nist.toolkit.results.client.Result;
import gov.nist.toolkit.results.client.TestInstance;
import gov.nist.toolkit.utilities.io.Io;
import gov.nist.toolkit.xdsexception.client.XdsException;
import java.util.logging.Logger;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Persists test {@link Result}s under
 * {@code <TestLogCache>/<testSession>/Results/<testId>[<section>].json}.
 * <p>
 * New results are always written as JSON. Reads try the JSON file first and fall back to the
 * legacy Java-serialized {@code .ser} form for results written before this class switched formats
 * - a successful legacy read is opportunistically upgraded to JSON on disk (best-effort; a failed
 * upgrade never fails the read that already succeeded). This is deliberately more forgiving than
 * the {@code simctl.ser -> simctl.json} migration elsewhere in this codebase, which had no fallback
 * and silently orphaned old files.
 */
public class ResultPersistence {
	static Logger logger = Logger.getLogger(ResultPersistence.class.getName());

	// FIELD-only visibility (no getters/setters consulted) mirrors the convention already used for
	// SimulatorConfig's .ser -> .json migration (SimulatorConfigIoJackson, via @JsonAutoDetect) and
	// gov.nist.toolkit.fhir.simulators.tools.SerializedStateExporter#buildMapper(). This avoids
	// running business-logic getters (e.g. a would-be getObjectRefCount()) as a side effect of
	// serialization, and requires no annotations on any class in Result's object graph.
	private static final ObjectMapper MAPPER = buildMapper();

	private static ObjectMapper buildMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
		mapper.setVisibility(PropertyAccessor.GETTER, Visibility.NONE);
		mapper.setVisibility(PropertyAccessor.IS_GETTER, Visibility.NONE);
		mapper.setVisibility(PropertyAccessor.SETTER, Visibility.NONE);
		// Honor Java's `transient` keyword (Result.includesMetadata) - Jackson ignores it by
		// default, which would otherwise persist a field the original author explicitly excluded.
		mapper.configure(MapperFeature.PROPAGATE_TRANSIENT_MARKER, true);
		// A future field removal from Result/StepResult/etc. must not break reading JSON written
		// by an older build - this is the one piece of forward-compatibility JSON doesn't give for
		// free.
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		// jackson-core 2.15 caps a single PARSED string at 5,000,000 chars by default, while having
		// no write-side limit at all. Left alone that is a one-way door: StepResult.rawResults holds
		// a whole logged SOAP body (XdsTestServiceManager.buildResult calls setRawResults with the
		// step's input metadata), and a Provide & Register carrying an inline base64 document or a
		// Retrieve Document Set response routinely runs past 5 MB - such a result would write
		// successfully and then throw StreamConstraintsException on every subsequent read, forever.
		// ObjectOutputStream had no equivalent limit, so this would be a regression against the
		// format we are migrating away from.
		mapper.getFactory().setStreamReadConstraints(
				StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build());
		return mapper;
	}

	public void write(Result result, TestSession testSession) throws IOException, XdsException {

		if (result.testInstance == null || result.testInstance.isEmpty())
			throw new XdsException("No test name specified in Result - cannot persist", null);

		File outFile = getFilePath(result.testInstance, testSession, null, true, "json");
		writeJsonAtomically(result, outFile);

		// This run supersedes any legacy .ser for the same test id, so retire it. Leaving it would
		// make a STALE copy the fallback in readWholeTest(): if the JSON later failed to parse we
		// would recover an older run and then rewriteJson() would persist that stale result as the
		// current one - presenting a previous (possibly failed) run as if it were this one. Only
		// write() does this; upgradeToJson() deliberately keeps the .ser it just copied, because
		// there the two files hold identical content and the .ser is a genuine safety net.
		// Safe to run here: writeJsonAtomically throws on failure, so reaching this line means the
		// JSON is durably in place.
		deleteIfExists(getFilePath(result.testInstance, testSession, null, false, "ser"));
	}

	/**
	 * Writes the JSON so that a concurrent reader can never observe a half-written file.
	 * <p>
	 * {@code ObjectMapper.writeValue(File, ...)} truncates the target and then streams into it, so
	 * for a result of any size there is a window in which the file exists but is incomplete. A
	 * reader landing in that window gets a JsonEOFException, which {@link #read} wraps into an
	 * XdsException, which XdsTestServiceManager#getTestResults swallows - the test would silently
	 * disappear from the GUI, which is the exact failure this class was changed to eliminate.
	 * Writing to a sibling temp file and moving it into place makes the swap atomic instead.
	 */
	private static void writeJsonAtomically(Result result, File jsonFile) throws IOException {
		File dir = jsonFile.getParentFile();
		if (dir != null)
			dir.mkdirs();
		File tmp = File.createTempFile("result-", ".tmp", dir);
		try {
			MAPPER.writeValue(tmp, result);
			moveIntoPlace(tmp, jsonFile);
		} finally {
			if (tmp.exists())
				tmp.delete();
		}
	}

	/**
	 * Moves the finished temp file over the destination, retrying briefly.
	 * <p>
	 * On Windows the move competes with readers: {@code MoveFileEx} cannot replace a target another
	 * handle has open, and Jackson's parser opens the file with a plain {@code FileInputStream},
	 * which does not pass FILE_SHARE_DELETE. So a GUI read of a result that overlaps a re-run of the
	 * same test makes the move throw {@link java.nio.file.AccessDeniedException} - which is NOT an
	 * {@link AtomicMoveNotSupportedException}. Letting that escape would propagate out of
	 * {@code write()} into TestRunner, which records it as a failed assertion: a test that genuinely
	 * passed would be reported to the user as FAILED simply because someone had its results open.
	 * The old in-place FileOutputStream never hit this, so it has to be handled here.
	 */
	private static void moveIntoPlace(File tmp, File jsonFile) throws IOException {
		IOException last = null;
		for (int attempt = 0; attempt < 10; attempt++) {
			try {
				Files.move(tmp.toPath(), jsonFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
				return;
			} catch (AtomicMoveNotSupportedException e) {
				// Filesystem cannot do it atomically at all - a plain replace is the best available.
				Files.move(tmp.toPath(), jsonFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (IOException e) {
				// Transient sharing violation: a reader has the destination open. Back off briefly.
				last = e;
				try {
					Thread.sleep(20L * (attempt + 1));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw last;
				}
			}
		}
		throw last;
	}

    public void delete(TestInstance testInstance, TestSession testSession, List<String> sectionNames) throws IOException, XdsException {
        // if test run as single entity - remove both formats so a delete never orphans whichever
        // one the result happened to be stored in
        deleteIfExists(getFilePath(testInstance, testSession, null, true, "json"));
        deleteIfExists(getFilePath(testInstance, testSession, null, true, "ser"));
		// if test run as individual sections
		for (String sectionName : sectionNames) {
			deleteIfExists(getFilePath(testInstance, testSession, sectionName, true, "json"));
			deleteIfExists(getFilePath(testInstance, testSession, sectionName, true, "ser"));
		}
		File file = getFilePath2(testInstance, testSession);
		if (file.exists())
			Io.delete(file);
    }

	private static void deleteIfExists(File file) {
		if (file.exists())
			file.delete();
	}

	public Result read(TestInstance testInstance, List<String> sectionNames, TestSession testSession) throws XdsException  {
		try {
			Result result = readWholeTest(testInstance, testSession);
			if (result != null)
				return result;

			// This form will only exist if test was run as a whole.  If sections were run
			// individually then there will be a file per section - pick them up from their
			// individual file names.
			result = null;
			for (String sectionName : sectionNames) {
				Result sectionResult = readSection(testInstance, testSession, sectionName);
				if (sectionResult != null) {
					if (result == null) {
						result = sectionResult;
					} else {
						logger.info("ResultPersistence#read/append: " + sectionName);
						result.append(sectionResult);
					}
				}
			}
			return result;
		}
		catch (IOException e) {
			throw new XdsException(e.getMessage(), null, e);
		} catch (ClassNotFoundException e) {
			throw new XdsException(e.getMessage(), null, e);
		}
	}

	// Whole-test file: JSON first, then legacy .ser with an opportunistic upgrade to JSON.
	private Result readWholeTest(TestInstance testInstance, TestSession testSession) throws IOException, ClassNotFoundException {
		File jsonFile = getFilePath(testInstance, testSession, null, false, "json");
		File serFile = getFilePath(testInstance, testSession, null, false, "ser");

		if (jsonFile.exists()) {
			logger.info("ResultPersistence#read: " + jsonFile);
			try {
				return MAPPER.readValue(jsonFile, Result.class);
			} catch (IOException e) {
				// Never let an unreadable JSON file shadow a perfectly good legacy .ser. Dispatching
				// on exists() alone would make a single damaged/truncated file (one written by a
				// build that predates the atomic write below, a partial restore, a bad disk) a
				// permanent loss, because the caller swallows the exception and the test just
				// disappears. Fall back, and re-write the JSON from the recovered copy to self-heal.
				if (!serFile.exists())
					throw e;
				logger.warning("ResultPersistence#read: " + jsonFile + " could not be parsed (" +
						e.getMessage() + ") - falling back to legacy " + serFile);
				Result recovered = readSer(serFile);
				rewriteJson(recovered, jsonFile);
				return recovered;
			}
		}

		if (serFile.exists()) {
			logger.info("ResultPersistence#read (legacy .ser): " + serFile);
			Result result = readSer(serFile);
			upgradeToJson(result, jsonFile);
			return result;
		}

		return null;
	}

	// One section file: JSON first, then legacy .ser. No upgrade is attempted here - write()
	// never produces section files (sectionName is always null on write), so there is no future
	// section-scoped write that a JSON copy would need to stay ahead of; this branch only ever
	// matches files left by installations that pre-date that behavior.
	private Result readSection(TestInstance testInstance, TestSession testSession, String sectionName) throws IOException, ClassNotFoundException {
		File jsonFile = getFilePath(testInstance, testSession, sectionName, false, "json");
		if (jsonFile.exists()) {
			logger.info("ResultPersistence#read: " + jsonFile);
			return MAPPER.readValue(jsonFile, Result.class);
		}

		File serFile = getFilePath(testInstance, testSession, sectionName, false, "ser");
		if (serFile.exists()) {
			logger.info("ResultPersistence#read (legacy .ser): " + serFile);
			return readSer(serFile);
		}

		return null;
	}

	// try-with-resources matters here: readObject() throwing is the EXPECTED case for a legacy
	// file after class evolution - the whole reason this migration exists - and a leaked handle
	// blocks the later delete() of that same .ser on Windows.
	private static Result readSer(File serFile) throws IOException, ClassNotFoundException {
		try (FileInputStream fis = new FileInputStream(serFile);
			 ObjectInputStream in = new ObjectInputStream(fis)) {
			return (Result) in.readObject();
		}
	}

	// Best-effort: a successfully-read legacy .ser is upgraded to .json so future reads skip the
	// legacy path entirely. A failure here (read-only mount, disk full, ...) must never turn a
	// read that already succeeded into a failure - so it is caught and logged, not propagated.
	private void upgradeToJson(Result result, File jsonFile) {
		// Another reader racing us may already have produced it - the write is atomic either way,
		// this just avoids redundantly rewriting the same content.
		if (jsonFile.exists())
			return;
		rewriteJson(result, jsonFile);
	}

	// Best-effort JSON (re)write. A failure here must never turn a read that already succeeded
	// into a failure - the caller has a valid Result in hand either way.
	private void rewriteJson(Result result, File jsonFile) {
		try {
			writeJsonAtomically(result, jsonFile);
			logger.info("ResultPersistence#read: wrote " + jsonFile + " from the legacy .ser");
		} catch (IOException e) {
			logger.warning("ResultPersistence#read: could not write JSON at " + jsonFile + ": " + e.getMessage());
		}
	}

	private File getFilePath(TestInstance testInstance, TestSession testSession, String sectionName, boolean write, String extension) throws IOException {
		File dir = new File(
				Installation.instance().propertyServiceManager().getTestLogCache().toString() + File.separator +
				testSession + File.separator +
				"Results");
		if (write)
			dir.mkdirs();

		String base = testInstance.getId().replace(":","") + (sectionName == null ? "" : sectionName);
		return new File(dir.toString() + File.separator + base + "." + extension);
	}

	private File getFilePath2(TestInstance testInstance,TestSession testSession) throws IOException {
		File dir = new File(
				Installation.instance().propertyServiceManager().getTestLogCache().toString() + File.separator +
						testSession
//						+ File.separator + "Results"
		);
			return new File(dir.toString() + File.separator + testInstance.getId().replace(":",""));
	}
}
