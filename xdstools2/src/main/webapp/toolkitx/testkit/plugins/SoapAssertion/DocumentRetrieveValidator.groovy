package war.toolkitx.testkit.plugins.SoapAssertion

import gov.nist.toolkit.registrymsg.repository.RetrieveRequestParser
import gov.nist.toolkit.registrymsg.repository.RetrieveRequestModel
//import gov.nist.toolkit.testengine.engine.Validator
import gov.nist.toolkit.testengine.engine.SimReference
import gov.nist.toolkit.testengine.engine.SoapSimulatorTransaction
import gov.nist.toolkit.testengine.engine.validations.ValidaterResult
import gov.nist.toolkit.testengine.engine.validations.soap.AbstractSoapValidater
import gov.nist.toolkit.testengine.engine.Validator

import gov.nist.toolkit.registrymetadata.Metadata
import gov.nist.toolkit.utilities.xml.Util
import org.apache.axiom.om.OMElement
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.charset.Charset

/**
 * Runs an MetadataContent validator through this plugin. @see Validator#run_test_assertions.
 */
class DocumentRetrieveValidator extends AbstractSoapValidater {
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
    String codeValue
    String codingScheme
    String codeDisplayName
    String transactionBase

    /**
     * Optional parameter
     */
    String metadataValidationFile

    DocumentRetrieveValidator() {
        filterDescription = 'Runs a Document Retrieve validator (Validator#run_test_assertions) through this plugin.'
    }

    @Override
    ValidaterResult validate(SoapSimulatorTransaction sst) {
        reset() // Clear log
        boolean requestMatch = false
        boolean responseMatch = false
        if (!requestMsgExpectedContent && !responseMsgExpectedContent) {
            String illegalArg = "Either requestMsgExpectedContent attribute or responseMsgExpectedContent attribute must be specified. See Validator#run_test_assertion for a list of codes."
            error(illegalArg)
            throw new IllegalArgumentException(illegalArg)
        }
        if (requestMsgExpectedContent && sst) {
            //          For Debugging only -- this log creates too many messages
//            log("Processing request from eventId: ${transactionInstance?.simDbEvent?.eventId} simLogUrl: ${transactionInstance?.simDbEvent?.simLogUrl}")
            if (sst.requestBody) {

                RetrieveRequestParser parser = new RetrieveRequestParser(Util.parse_xml(sst.requestBody))
                RetrieveRequestModel retrieveRequestModel = parser.getRequest()
                String errors = ""
                value = determineValue(value, sst.simReference.simId.testSession.value)
                if (requestMsgExpectedContent.equals("Retrieve")) {
                    Validator v = new Validator().setRetrieveRequestModel(retrieveRequestModel)
                    switch (method) {
                        case "single":
                            if (!v.namedFieldCompare(key, value)) {
                                errors = v.getErrors()
                            }
                            break
                        case "singleCode":
                            if (!v.namedMetadataCompareCode(key, codeValue, codingScheme, codeDisplayName)) {
                                errors = v.getErrors()
                            }
                            break
                        case "containsCode":
                            if (!v.namedMetadataContainsCode(key, codeValue, codingScheme, codeDisplayName)) {
                                errors = v.getErrors()
                            }
                            break
                        case "contains":
                            if (!v.namedFieldContains(key, value)) {
                                errors = v.getErrors()
                            }
                            break
                        case "atOrAfter":
                            String referenceTimestamp = value
                            String transactionTimeStamp = sst.simDbEvent.eventId.replace('_', '')
                            int lexicalCompare = transactionTimeStamp.compareTo(referenceTimestamp)
                            if (lexicalCompare < 0) {
                                errors="The transaction time stamp " + transactionTimeStamp + " is not atOrAfter the reference time stamp " + referenceTimestamp
                            }
                            break
                        default:
                            errors="Unrecognized Document Retrieve validation method:" + method + ". Expecting one of single, singleCode, containsCode, contains."
                            break
                    }
                } else {
                    error("NA", "This validator expects requestMsgExpectedContent=Retrieve. Testplan provide: " + requestMsgExpectedContent)
                }

                if (errors.length() > 0) {
                    error("Request", errors)
                }
                requestMatch = sst.request instanceof String && !isErrors()
            } else {
                error("Request","Null transactionInstance or its request body is null")
            }
        }

        if (responseMsgExpectedContent && sst) {
//          For Debugging only -- this log creates too many messages
//  log("Processing response from eventId: ${transactionInstance?.simDbEvent?.eventId} simLogUrl: ${transactionInstance?.simDbEvent?.simLogUrl}")
            if (sst.responseBody) {
                OMElement regresp = Util.parse_xml(sst.responseBody)

                Metadata m = new Metadata(regresp, false, false)
                Validator v = new Validator().setM(m)
                v.run_test_assertion(responseMsgExpectedContent, Integer.parseInt(responseMsgECCount?:"-1"))
                String errors = v.getErrors()

                if (errors.length() > 0) {
                    error("Response", errors)
                }
                responseMatch = sst.response instanceof String && !isErrors()
            } else {
                error("Response", "Null transactionInstance or its response body is null")
            }
        }

        boolean match = false
        if (requestMsgExpectedContent && responseMsgExpectedContent) {
            match = requestMatch && responseMatch
        } else if (requestMsgExpectedContent) {
            match = requestMatch
        } else if (responseMsgExpectedContent) {
            match = responseMatch
        }

        new ValidaterResult(sst, this.copy(), match)
    }

    private String determineValue(String valueFromStepAttribute, String simId) {
        String rtn = valueFromStepAttribute
        if (transactionBase != null) {
            String inputPathTxt = "/tmp/" + simId + "/" + transactionBase + ".txt"
            try {
                List<String> lines = Files.readAllLines(Paths.get(inputPathTxt), Charset.defaultCharset())
                if (lines.size() > 0) {
                    rtn = lines.get(0)
                }
            } catch (Exception e) {
                System.out.println("Unable to read from " + inputPathTxt)
                e.printStackTrace()
            }
        }
        rtn
    }

    AbstractSoapValidater copy() {
        DocumentRetrieveValidator mcv = new DocumentRetrieveValidator()
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
}
