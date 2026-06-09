package gov.nist.toolkit.utilities.io;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Dual-mode support for the XDS DocumentEntry "hash" metadata slot.
 *
 * <p>IHE ITI TF-3 (Table 4.1-3) defines the hash slot as the SHA-1 of the document
 * (40 hex chars). This toolkit additionally <b>accepts</b> SHA-256 (64 hex chars) as a
 * forward-compatible leniency: a claimed hash is validated/compared using the algorithm
 * implied by its hex-string length. The toolkit still <b>generates</b> SHA-1 by default
 * (strict in what it emits, liberal in what it accepts) so emitted metadata stays
 * conformant to the base profile.
 */
public enum HashType {
    SHA1("SHA-1", 40),
    SHA256("SHA-256", 64);

    public final String algorithm;
    public final int hexLength;

    HashType(String algorithm, int hexLength) {
        this.algorithm = algorithm;
        this.hexLength = hexLength;
    }

    /** Map a hex hash string to its algorithm by length: 40 -&gt; SHA-1, 64 -&gt; SHA-256, else null. */
    public static HashType fromHex(String hash) {
        if (hash == null) return null;
        switch (hash.trim().length()) {
            case 40: return SHA1;
            case 64: return SHA256;
            default: return null;
        }
    }

    /** Lowercase hex digest of {@code data} using this algorithm (matches Sha1Bean's format). */
    public String compute(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                String h = Integer.toHexString(b & 0xff);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 and SHA-256 are guaranteed present on every conformant JVM.
            throw new IllegalStateException("Required digest algorithm missing: " + algorithm, e);
        }
    }

    /**
     * True if {@code claimedHash} equals the digest of {@code data} computed with the algorithm
     * implied by the claimed hash's length (40 -&gt; SHA-1, 64 -&gt; SHA-256). Returns false if
     * {@code claimedHash} is null/empty, an unrecognized length, or {@code data} is null.
     */
    public static boolean matches(byte[] data, String claimedHash) {
        HashType type = fromHex(claimedHash);
        if (type == null || data == null) return false;
        return type.compute(data).equalsIgnoreCase(claimedHash.trim());
    }
}
