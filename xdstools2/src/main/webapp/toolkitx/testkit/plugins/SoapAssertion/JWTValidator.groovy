package war.toolkitx.testkit.plugins.SoapAssertion

//#import gov.nist.toolkit.registrymsg.registry.AdhocQueryRequest
//#import gov.nist.toolkit.registrymsg.registry.AdhocQueryRequestParser
import gov.nist.toolkit.registrymsg.common.RequestHeader
import gov.nist.toolkit.registrymsg.common.RequestHeaderParser
//#import gov.nist.toolkit.testengine.engine.Validator
import gov.nist.toolkit.testengine.engine.SimReference
import gov.nist.toolkit.testengine.engine.SoapSimulatorTransaction
import gov.nist.toolkit.testengine.engine.validations.ValidaterResult
import gov.nist.toolkit.testengine.engine.validations.soap.AbstractSoapValidater
//#import gov.nist.toolkit.testengine.engine.Validator

//#import gov.nist.toolkit.registrymetadata.Metadata
//#import gov.nist.toolkit.registrymetadata.MetadataParser
import gov.nist.toolkit.utilities.xml.Util
import gov.nist.toolkit.utilities.xml.XmlUtil
//#import gov.nist.toolkit.valregmsg.registry.storedquery.support.ParamParser
//#import gov.nist.toolkit.valregmsg.registry.storedquery.support.SqParams
import org.apache.axiom.om.OMElement
import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.algorithms.Algorithm
/**
 * Runs an MetadataContent validator through this plugin. @see Validator#run_test_assertions.
 */
class JWTValidator extends AbstractSoapValidater {
    /**
     * Required parameter
     */
    String requestMsgExpectedContent
    /**
     * Optional parameter
     */
    String requestMsgECCount
    /**
     * Required parameter
     */
    String responseMsgExpectedContent
    /**
     * Optional parameter
     */
    String responseMsgECCount

    String method
    String key
    String value

    String count
    String XPath
    String section
    String comment
    String attribute

//    String method
//    String key
//    String value
//    String codeValue
//    String codingScheme
//    String codeDisplayName
//    String attribute
//    String section
//    String comment
    String reference

    /**
     * Optional parameter
     */
    String metadataValidationFile;

    StringBuffer errs = new StringBuffer()
    boolean error_flag = false

    JWTValidator() {
        filterDescription = 'Runs a JWT validator (Validator#run_test_assertions) through this plugin.'
    }

    @Override
    ValidaterResult validate(SoapSimulatorTransaction sst) {
        reset() // Clear log
        boolean requestMatch = false
         if (!requestMsgExpectedContent && !responseMsgExpectedContent) {
            String illegalArg = "Either requestMsgExpectedContent attribute or responseMsgExpectedContent attribute must be specified. See Validator#run_test_assertion for a list of codes."
            error(illegalArg)
            throw new IllegalArgumentException(illegalArg)
        }
        if (requestMsgExpectedContent && sst) {
            //          For Debugging only -- this log creates too many messages
//            log("Processing request from eventId: ${transactionInstance?.simDbEvent?.eventId} simLogUrl: ${transactionInstance?.simDbEvent?.simLogUrl}")
            if (sst.requestBody) {
                RequestHeaderParser headerParser = new RequestHeaderParser(Util.parse_xml(sst.requestHeader))
                RequestHeader requestHeader = headerParser.getRequestHeader()
                String local_errors = ""
                if (requestMsgExpectedContent.equals("SoapHeader")) {
                    OMElement sectionElement = requestHeader.getOmElement()
                    String valueToTest = XmlUtil.getStringFromXPath(sectionElement, XPath)
                    if (valueToTest == null) {
                        error("XPath resulted in a null string " + XPath)
                        return new ValidaterResult(sst, this.copy(), false)
                    }

                    switch (method) {
                        case "hasNComponents":
                            System.out.println("hasNComponents")
                            testNComponents(valueToTest, count)
                            break
                        case "single":
                            System.out.println("single")
                            testSingleValue(valueToTest, key, value)
                            break
                        case "startsWith":
                            System.out.println("startsWith")
                            testStartsWith(valueToTest, key, value)
                            break
                        case "isNotEmpty":
                            System.out.println("isNotEmpty")
                            testNotEmpty(valueToTest, key)
                            break

                        default:
                            err("Unrecognized JWTValidator validation method:" + method + ".")
                            break
                    }
                } else {
                    System.out.println("In the else clause")
                    err("Must be an error in the testplan.xml file for JWT Validation. The value for the attribute requestMsgExpectedContent should be SoapHeader")
                }

                if (local_errors.length() > 0) {
                    err("Request " + errors)
                }
                //error_flag = sst.request instanceof String && !isErrors()
            } else {
                error("Request","Null transactionInstance or its request body is null")
            }
        }

        boolean match = ! error_flag
        if (error_flag) {
            error(errs.toString())
        }

        new ValidaterResult(sst, this.copy(), match)
    }

    AbstractSoapValidater copy() {
        JWTValidator mcv = new JWTValidator()
        mcv.responseMsgExpectedContent = responseMsgExpectedContent
        mcv.requestMsgExpectedContent = requestMsgExpectedContent
        mcv.responseMsgECCount = responseMsgECCount
        mcv.requestMsgECCount = requestMsgECCount
        mcv.metadataValidationFile = metadataValidationFile
        mcv.simReference = new SimReference(simReference?.simId, simReference?.transactionType, simReference?.actorType)
        mcv.errors = this.errors
        mcv.setLog(new StringBuilder(this.log))
        mcv
    }

    private void testNComponents(String valueToTest, String count) {
        int expectedCount = Integer.valueOf(count).intValue()
        String[] tokens = valueToTest.split("\\.")
        if (expectedCount != tokens.length) {
            err("JWT test requires " + count + "tokens, but we only parsed " + tokens.length + " tokens with delimiter '.'")
        }
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].length() == 0) {
                err("JWT component at position " + i + " is an empty string")
            }
        }
    }

    private void testSingleValue(String valueToTest, String key, String value) {
        String extractedValue = extractJWTField(valueToTest, key)
        if (extractedValue == null) {
            err("No value submitted in JWT for key " + key)
            return
        }
        if (!value.equals(extractedValue)) {
            err("Value (" + extractedValue + ") submitted in JWT for key " + key + " does not match expected value " + value)
        }
    }

    private void testStartsWith(String valueToTest, String key, String value) {
        String extractedValue = extractJWTField(valueToTest, key)
        if (extractedValue == null) {
            err("No value submitted in JWT for key " + key)
            return
        }
        if (!extractedValue.startsWith(value)) {
            err("Value (" + extractedValue + ") submitted in JWT for key " + key + " does not start with expected value " + value)
        }
    }

    private void testNotEmpty(String valueToTest, String key) {
        String extractedValue = extractJWTField(valueToTest, key)
        if (extractedValue == null) {
            err("No value submitted in JWT for key " + key)
            return
        }
        if (extractedValue.length() == 0) {
            err("Zero length value submitted in JWT for key " + key)
        }
    }

    private String extractJWTField(String valueToTest, String key) {
//        DecodedJWT jwt = JWT.require().build().verify(valueToTest)

        String[] tokens = key.split("\\.")
        String rtnValue = ""
        DecodedJWT jwt = JWT.decode(valueToTest)

        if (tokens[0].equals("header")) {
            rtnValue = jwt.getHeaderClaim(tokens[1]).asString()
        } else if (tokens[0].equals("payload")) {
            if (tokens.length == 2) {
                rtnValue = jwt.getClaim(tokens[1]).asString()
            } else {
                Map<String, Object> claimMap = jwt.getClaim(tokens[1]).asMap()
                rtnValue = claimMap.get(tokens[2]).toString()
            }
        } else {
            rtnValue = "Unrecognized key value: " + key
        }
//        if (rtnValue.startsWith("\"")) {
//            rtnValue = rtnValue.substring(1)
//        }
//        if (rtnValue.endsWith("\"")) {
//            rtnValue = rtnValue.substring(0, rtnValue.length()-1)
//        }
        return rtnValue
    }
    private String extractJWTHeaderField(String base64Value, String key) {
        Base64.Decoder decoder = Base64.getUrlDecoder()
        String header = new String(decoder.decode(base64Value))
        return "JWT"

    }

    void err(String msg) {
        errs.append("\nValidator: ");
        errs.append(msg);
        errs.append('\n');
        error_flag = true;
    }
}
