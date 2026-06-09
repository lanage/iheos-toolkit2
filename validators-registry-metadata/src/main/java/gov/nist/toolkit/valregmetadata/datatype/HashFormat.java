package gov.nist.toolkit.valregmetadata.datatype;

import gov.nist.toolkit.errorrecording.ErrorRecorder;
import gov.nist.toolkit.errorrecording.client.XdsErrorCode;

public class HashFormat extends FormatValidator {

	public HashFormat(ErrorRecorder er, String context, String resource) {
		super(er, context, resource);
	}

	public void validate(String input) {
		if (!UuidFormat.isHexString(input)) {
			er.err(XdsErrorCode.Code.XDSRegistryMetadataError, context + ": " + input + " is not in hex format", this, getResource("ITI TF-3: Table 4.1-3 (SHA1)"));
			return;
		}
		// Dual-mode: ITI TF-3 Table 4.1-3 defines the hash as SHA-1 (40 hex chars). This toolkit
		// also accepts SHA-256 (64 hex chars) as a forward-compatible leniency. Other lengths are
		// rejected so a truncated or foreign-algorithm digest is not silently accepted.
		int len = (input == null) ? 0 : input.trim().length();
		if (len != 40 && len != 64) {
			er.err(XdsErrorCode.Code.XDSRegistryMetadataError, context + ": " + input + " must be 40 hex chars (SHA-1) or 64 hex chars (SHA-256)", this, getResource("ITI TF-3: Table 4.1-3 (SHA-1; SHA-256 accepted by toolkit)"));
		}
	}

}
