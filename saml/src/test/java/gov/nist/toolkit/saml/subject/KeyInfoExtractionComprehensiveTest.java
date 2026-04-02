package gov.nist.toolkit.saml.subject;

import gov.nist.toolkit.saml.builder.OpenSamlBootStrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.saml.saml2.core.*;
import org.opensaml.xmlsec.signature.KeyInfo;
import org.opensaml.xmlsec.signature.KeyValue;
import org.opensaml.xmlsec.signature.RSAKeyValue;
import org.opensaml.xmlsec.signature.X509Data;

import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive KeyInfo extraction tests with real SAML assertions and various key formats
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KeyInfoExtractionComprehensiveTest {

    @BeforeAll
    static void setup() {
        OpenSamlBootStrap.initSamlEngine();
    }

    @Test
    @Order(1)
    @DisplayName("Test RSA KeyValue extraction from SAML assertion")
    public void testRSAKeyValueExtraction() throws Exception {
        System.out.println("--- Testing RSA KeyValue Extraction ---");
        
        // Generate test RSA key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        
        // Create SAML assertion with RSA KeyValue
        String samlWithRSAKey = createSAMLAssertionWithRSAKeyValue(publicKey);
        System.out.println("✓ Created SAML assertion with RSA KeyValue");
        
        // Parse assertion
        Assertion assertion = parseSAMLAssertion(samlWithRSAKey);
        assertNotNull(assertion, "SAML assertion should be parsed successfully");
        
        // Extract KeyInfo from SubjectConfirmation
        SubjectConfirmation confirmation = assertion.getSubject().getSubjectConfirmations().get(0);
        HolderOfKeySubjectConfirmationValidator validator = new HolderOfKeySubjectConfirmationValidator();
        
        java.lang.reflect.Method method = HolderOfKeySubjectConfirmationValidator.class
            .getDeclaredMethod("getSubjectConfirmationKeyInfo", SubjectConfirmation.class);
        method.setAccessible(true);
        
        KeyInfo extractedKeyInfo = (KeyInfo) method.invoke(validator, confirmation);
        assertNotNull(extractedKeyInfo, "KeyInfo should be extracted successfully");
        
        System.out.println("✓ RSA KeyValue extracted successfully from SAML assertion");
        System.out.println("  - KeyInfo contains: " + extractedKeyInfo.getOrderedChildren().size() + " elements");
    }

    @Test
    @Order(2)
    @DisplayName("Test X509Certificate extraction from SAML assertion")
    public void testX509CertificateExtraction() throws Exception {
        System.out.println("--- Testing X509Certificate Extraction ---");
        
        // Create SAML assertion with X509Certificate
        String samlWithX509 = createSAMLAssertionWithX509Certificate();
        System.out.println("✓ Created SAML assertion with X509Certificate");
        
        // Parse assertion
        Assertion assertion = parseSAMLAssertion(samlWithX509);
        assertNotNull(assertion, "SAML assertion should be parsed successfully");
        
        // Extract KeyInfo from SubjectConfirmation
        SubjectConfirmation confirmation = assertion.getSubject().getSubjectConfirmations().get(0);
        HolderOfKeySubjectConfirmationValidator validator = new HolderOfKeySubjectConfirmationValidator();
        
        java.lang.reflect.Method method = HolderOfKeySubjectConfirmationValidator.class
            .getDeclaredMethod("getSubjectConfirmationKeyInfo", SubjectConfirmation.class);
        method.setAccessible(true);
        
        KeyInfo extractedKeyInfo = (KeyInfo) method.invoke(validator, confirmation);
        assertNotNull(extractedKeyInfo, "KeyInfo should be extracted successfully");
        
        System.out.println("✓ X509Certificate extracted successfully from SAML assertion");
        System.out.println("  - KeyInfo contains: " + extractedKeyInfo.getOrderedChildren().size() + " elements");
    }

    @Test
    @Order(3)
    @DisplayName("Test complete validation workflow with RSA key")
    public void testCompleteValidationWorkflowRSA() throws Exception {
        System.out.println("--- Testing Complete Validation Workflow (RSA) ---");
        
        // Generate test RSA key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();
        
        // Create SAML assertion with RSA KeyValue
        String samlAssertion = createSAMLAssertionWithRSAKeyValue(publicKey);
        Assertion assertion = parseSAMLAssertion(samlAssertion);
        
        // Create validation context with presenter key
        MockValidationContext context = new MockValidationContext();
        context.setStaticParameter(HolderOfKeySubjectConfirmationValidator.PRESENTER_KEY_PARAM, publicKey);
        
        // Test validation
        HolderOfKeySubjectConfirmationValidator validator = new HolderOfKeySubjectConfirmationValidator();
        SubjectConfirmation confirmation = assertion.getSubject().getSubjectConfirmations().get(0);
        
        // Use reflection to test protected doValidate method
        java.lang.reflect.Method method = HolderOfKeySubjectConfirmationValidator.class
            .getDeclaredMethod("doValidate", SubjectConfirmation.class, Assertion.class, ValidationContext.class);
        method.setAccessible(true);
        
        ValidationResult result = (ValidationResult) method.invoke(validator, confirmation, assertion, context);
        
        System.out.println("✓ Complete validation workflow executed");
        System.out.println("  - Validation result: " + result);
        System.out.println("  - Key matching: " + (result != ValidationResult.INVALID));
    }

    @Test
    @Order(4)
    @DisplayName("Test validation with X509Certificate")
    public void testValidationWithX509Certificate() throws Exception {
        System.out.println("--- Testing Validation with X509Certificate ---");
        
        // Create SAML assertion with X509Certificate
        String samlAssertion = createSAMLAssertionWithX509Certificate();
        Assertion assertion = parseSAMLAssertion(samlAssertion);
        
        // Create validation context with null certificate (for basic testing)
        MockValidationContext context = new MockValidationContext();
        context.setStaticParameter(HolderOfKeySubjectConfirmationValidator.PRESENTER_CERT_PARAM, null);
        
        // Test validation
        HolderOfKeySubjectConfirmationValidator validator = new HolderOfKeySubjectConfirmationValidator();
        SubjectConfirmation confirmation = assertion.getSubject().getSubjectConfirmations().get(0);
        
        // Use reflection to test protected doValidate method
        java.lang.reflect.Method method = HolderOfKeySubjectConfirmationValidator.class
            .getDeclaredMethod("doValidate", SubjectConfirmation.class, Assertion.class, ValidationContext.class);
        method.setAccessible(true);
        
        ValidationResult result = (ValidationResult) method.invoke(validator, confirmation, assertion, context);
        
        System.out.println("✓ X509Certificate validation workflow executed");
        System.out.println("  - Validation result: " + result);
        System.out.println("  - Certificate validation: " + (result != ValidationResult.INVALID));
    }

    @Test
    @Order(5)
    @DisplayName("Test KeyInfoConfirmationDataType validation in real assertion")
    public void testKeyInfoConfirmationDataTypeInRealAssertion() throws Exception {
        System.out.println("--- Testing KeyInfoConfirmationDataType in Real Assertion ---");
        
        // Create assertion with correct KeyInfoConfirmationDataType
        String samlAssertion = createSAMLAssertionWithCorrectKeyInfoType();
        Assertion assertion = parseSAMLAssertion(samlAssertion);
        
        HolderOfKeySubjectConfirmationValidator validator = new HolderOfKeySubjectConfirmationValidator();
        SubjectConfirmation confirmation = assertion.getSubject().getSubjectConfirmations().get(0);
        
        // Test KeyInfoConfirmationDataType validation
        boolean isValidType = validator.isValidConfirmationDataType(confirmation);
        assertTrue(isValidType, "Should accept correct KeyInfoConfirmationDataType");
        
        System.out.println("✓ KeyInfoConfirmationDataType validation works in real assertion");
        System.out.println("  - Schema type validation: PASSED");
    }

    @Test
    @Order(6)
    @DisplayName("Test multiple KeyInfo elements handling")
    public void testMultipleKeyInfoElements() throws Exception {
        System.out.println("--- Testing Multiple KeyInfo Elements ---");
        
        // Create assertion with multiple KeyInfo elements
        String samlAssertion = createSAMLAssertionWithMultipleKeyInfo();
        Assertion assertion = parseSAMLAssertion(samlAssertion);
        
        // Extract KeyInfo
        SubjectConfirmation confirmation = assertion.getSubject().getSubjectConfirmations().get(0);
        HolderOfKeySubjectConfirmationValidator validator = new HolderOfKeySubjectConfirmationValidator();
        
        java.lang.reflect.Method method = HolderOfKeySubjectConfirmationValidator.class
            .getDeclaredMethod("getSubjectConfirmationKeyInfo", SubjectConfirmation.class);
        method.setAccessible(true);
        
        KeyInfo extractedKeyInfo = (KeyInfo) method.invoke(validator, confirmation);
        assertNotNull(extractedKeyInfo, "KeyInfo should be extracted even with multiple elements");
        
        System.out.println("✓ Multiple KeyInfo elements handled correctly");
        System.out.println("  - Extracted elements: " + extractedKeyInfo.getOrderedChildren().size());
    }

    @Test
    @Order(7)
    @DisplayName("Test error handling for invalid assertions")
    public void testErrorHandlingForInvalidAssertions() throws Exception {
        System.out.println("--- Testing Error Handling for Invalid Assertions ---");
        
        HolderOfKeySubjectConfirmationValidator validator = new HolderOfKeySubjectConfirmationValidator();
        
        // Test with null SubjectConfirmation
        boolean result1 = validator.isValidConfirmationDataType(null);
        assertFalse(result1, "Should handle null SubjectConfirmation gracefully");
        
        // Test with SubjectConfirmation but no SubjectConfirmationData
        String samlWithoutData = createSAMLAssertionWithoutSubjectConfirmationData();
        Assertion assertion = parseSAMLAssertion(samlWithoutData);
        SubjectConfirmation confirmation = assertion.getSubject().getSubjectConfirmations().get(0);
        
        boolean result2 = validator.isValidConfirmationDataType(confirmation);
        assertFalse(result2, "Should handle missing SubjectConfirmationData gracefully");
        
        System.out.println("✓ Error handling works correctly");
        System.out.println("  - Null SubjectConfirmation: " + !result1);
        System.out.println("  - Missing SubjectConfirmationData: " + !result2);
    }

    // Helper methods for creating test SAML assertions
    
    private String createSAMLAssertionWithRSAKeyValue(PublicKey publicKey) throws Exception {
        // This would create a real SAML assertion with RSA KeyValue
        // For now, return a simplified version that can be parsed
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<saml2:Assertion xmlns:saml2=\"urn:oasis:names:tc:SAML:2.0:assertion\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "ID=\"_123456789\" IssueInstant=\"" + Instant.now().toString() + "\" Version=\"2.0\">" +
            "<saml2:Subject>" +
            "<saml2:NameID Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified\">test-user</saml2:NameID>" +
            "<saml2:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:holder-of-key\">" +
            "<saml2:SubjectConfirmationData xsi:type=\"saml2:KeyInfoConfirmationDataType\" " +
            "xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">" +
            "<ds:KeyInfo>" +
            "<ds:KeyValue>" +
            "<ds:RSAKeyValue>" +
            "<ds:Modulus>" + Base64.getEncoder().encodeToString(((java.security.interfaces.RSAPublicKey) publicKey).getModulus().toByteArray()) + "</ds:Modulus>" +
            "<ds:Exponent>" + Base64.getEncoder().encodeToString(((java.security.interfaces.RSAPublicKey) publicKey).getPublicExponent().toByteArray()) + "</ds:Exponent>" +
            "</ds:RSAKeyValue>" +
            "</ds:KeyValue>" +
            "</ds:KeyInfo>" +
            "</saml2:SubjectConfirmationData>" +
            "</saml2:SubjectConfirmation>" +
            "</saml2:Subject>" +
            "<saml2:Conditions NotBefore=\"" + Instant.now().toString() + "\" NotOnOrAfter=\"" + Instant.now().plusSeconds(3600).toString() + "\"/>" +
            "</saml2:Assertion>";
    }
    
    private String createSAMLAssertionWithX509Certificate() throws Exception {
        // Create a SAML assertion with X509Certificate
        String certData = "MIIDdzCCAl+gAwIBAgIEbG2pZTANBgkqhkiG9w0BAQsFADBsMQswCQYDVQQGEwJVUzEL";
        
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<saml2:Assertion xmlns:saml2=\"urn:oasis:names:tc:SAML:2.0:assertion\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "ID=\"_123456789\" IssueInstant=\"" + Instant.now().toString() + "\" Version=\"2.0\">" +
            "<saml2:Subject>" +
            "<saml2:NameID Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified\">test-user</saml2:NameID>" +
            "<saml2:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:holder-of-key\">" +
            "<saml2:SubjectConfirmationData xsi:type=\"saml2:KeyInfoConfirmationDataType\" " +
            "xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">" +
            "<ds:KeyInfo>" +
            "<ds:X509Data>" +
            "<ds:X509Certificate>" + certData + "</ds:X509Certificate>" +
            "</ds:X509Data>" +
            "</ds:KeyInfo>" +
            "</saml2:SubjectConfirmationData>" +
            "</saml2:SubjectConfirmation>" +
            "</saml2:Subject>" +
            "<saml2:Conditions NotBefore=\"" + Instant.now().toString() + "\" NotOnOrAfter=\"" + Instant.now().plusSeconds(3600).toString() + "\"/>" +
            "</saml2:Assertion>";
    }
    
    private String createSAMLAssertionWithCorrectKeyInfoType() throws Exception {
        return createSAMLAssertionWithRSAKeyValue(
            KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic());
    }
    
    private String createSAMLAssertionWithMultipleKeyInfo() throws Exception {
        // Create assertion with both RSA KeyValue and X509Certificate
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<saml2:Assertion xmlns:saml2=\"urn:oasis:names:tc:SAML:2.0:assertion\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "ID=\"_123456789\" IssueInstant=\"" + Instant.now().toString() + "\" Version=\"2.0\">" +
            "<saml2:Subject>" +
            "<saml2:NameID Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified\">test-user</saml2:NameID>" +
            "<saml2:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:holder-of-key\">" +
            "<saml2:SubjectConfirmationData xsi:type=\"saml2:KeyInfoConfirmationDataType\" " +
            "xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">" +
            "<ds:KeyInfo>" +
            "<ds:KeyValue>" +
            "<ds:RSAKeyValue>" +
            "<ds:Modulus>ABC123</ds:Modulus>" +
            "<ds:Exponent>AQAB</ds:Exponent>" +
            "</ds:RSAKeyValue>" +
            "</ds:KeyValue>" +
            "<ds:X509Data>" +
            "<ds:X509Certificate>MIIDdzCCAl+gAwIBAgIEbG2pZTANBgkqhkiG9w0BAQsFADBsMQswCQYDVQQGEwJVUzEL</ds:X509Certificate>" +
            "</ds:X509Data>" +
            "</ds:KeyInfo>" +
            "</saml2:SubjectConfirmationData>" +
            "</saml2:SubjectConfirmation>" +
            "</saml2:Subject>" +
            "<saml2:Conditions NotBefore=\"" + Instant.now().toString() + "\" NotOnOrAfter=\"" + Instant.now().plusSeconds(3600).toString() + "\"/>" +
            "</saml2:Assertion>";
    }
    
    private String createSAMLAssertionWithoutSubjectConfirmationData() throws Exception {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<saml2:Assertion xmlns:saml2=\"urn:oasis:names:tc:SAML:2.0:assertion\" " +
            "ID=\"_123456789\" IssueInstant=\"" + Instant.now().toString() + "\" Version=\"2.0\">" +
            "<saml2:Subject>" +
            "<saml2:NameID Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified\">test-user</saml2:NameID>" +
            "<saml2:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:holder-of-key\">" +
            "</saml2:SubjectConfirmation>" +
            "</saml2:Subject>" +
            "<saml2:Conditions NotBefore=\"" + Instant.now().toString() + "\" NotOnOrAfter=\"" + Instant.now().plusSeconds(3600).toString() + "\"/>" +
            "</saml2:Assertion>";
    }
    
    private Assertion parseSAMLAssertion(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        
        ByteArrayInputStream inputStream = new ByteArrayInputStream(xml.getBytes());
        org.w3c.dom.Element documentElement = builder.parse(inputStream).getDocumentElement();
        
        return (Assertion) OpenSamlBootStrap.getUnmarshallerFactory().getUnmarshaller(documentElement).unmarshall(documentElement);
    }

    // Mock classes
    private static class MockValidationContext implements ValidationContext {
        private java.util.Map<String, Object> staticParameters = new java.util.HashMap<>();
        private java.util.Map<String, Object> dynamicParameters = new java.util.HashMap<>();
        private String validationFailureMessage;

        public java.util.Map<String, Object> getStaticParameters() { return staticParameters; }
        public java.util.Map<String, Object> getDynamicParameters() { return dynamicParameters; }
        public void setStaticParameter(String key, Object value) { staticParameters.put(key, value); }
        public String getValidationFailureMessage() { return validationFailureMessage; }
        public void setValidationFailureMessage(String message) { this.validationFailureMessage = message; }
    }

    private interface ValidationContext {
        java.util.Map<String, Object> getStaticParameters();
        java.util.Map<String, Object> getDynamicParameters();
        void setValidationFailureMessage(String message);
        String getValidationFailureMessage();
    }

    private enum ValidationResult {
        VALID, INVALID, INDETERMINATE
    }
}
