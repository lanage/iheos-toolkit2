package gov.nist.toolkit.utilities.io;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HashType} — the dual-mode (SHA-1 / SHA-256) document-hash helper.
 * Uses published NIST test vectors so the digests are independently verifiable.
 */
public class HashTypeTest {

    // Well-known vectors for the ASCII string "abc"
    private static final byte[] ABC = "abc".getBytes(StandardCharsets.UTF_8);
    private static final String ABC_SHA1   = "a9993e364706816aba3e25717850c26c9cd0d89d";
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    // ---- fromHex: length-based algorithm dispatch ----

    @Test void fromHex_40chars_isSha1()   { assertEquals(HashType.SHA1,   HashType.fromHex(ABC_SHA1)); }
    @Test void fromHex_64chars_isSha256() { assertEquals(HashType.SHA256, HashType.fromHex(ABC_SHA256)); }
    @Test void fromHex_trimsWhitespace()  { assertEquals(HashType.SHA1,   HashType.fromHex("  " + ABC_SHA1 + "  ")); }
    @Test void fromHex_null_isNull()      { assertNull(HashType.fromHex(null)); }
    @Test void fromHex_empty_isNull()     { assertNull(HashType.fromHex("")); }
    @Test void fromHex_wrongLength_isNull() {
        assertNull(HashType.fromHex("abcdef"));                 // 6
        assertNull(HashType.fromHex(ABC_SHA1 + "00"));          // 42
        assertNull(HashType.fromHex(ABC_SHA256.substring(1)));  // 63
    }

    // ---- compute: known vectors, lowercase hex ----

    @Test void compute_sha1_abc()   { assertEquals(ABC_SHA1,   HashType.SHA1.compute(ABC)); }
    @Test void compute_sha256_abc() { assertEquals(ABC_SHA256, HashType.SHA256.compute(ABC)); }

    @Test void compute_isLowercaseHexOfExpectedLength() {
        assertEquals(40, HashType.SHA1.compute(ABC).length());
        assertEquals(64, HashType.SHA256.compute(ABC).length());
        assertEquals(HashType.SHA1.compute(ABC).toLowerCase(), HashType.SHA1.compute(ABC));
    }

    // ---- matches: dispatch by claimed-hash length, case-insensitive ----

    @Test void matches_sha1()   { assertTrue(HashType.matches(ABC, ABC_SHA1)); }
    @Test void matches_sha256() { assertTrue(HashType.matches(ABC, ABC_SHA256)); }

    @Test void matches_caseInsensitive() {
        assertTrue(HashType.matches(ABC, ABC_SHA1.toUpperCase()));
        assertTrue(HashType.matches(ABC, ABC_SHA256.toUpperCase()));
    }

    @Test void matches_wrongContent_false() {
        assertFalse(HashType.matches("abd".getBytes(StandardCharsets.UTF_8), ABC_SHA1));
    }

    @Test void matches_validLengthWrongDigest_false() {
        assertFalse(HashType.matches(ABC, ABC_SHA1.replace('a', 'b'))); // 40 chars, wrong digest
    }

    @Test void matches_null_orBadLength_false() {
        assertFalse(HashType.matches(ABC, null));
        assertFalse(HashType.matches(ABC, "deadbeef"));   // bad length
        assertFalse(HashType.matches(null, ABC_SHA1));    // null data
    }
}
