package gov.nist.toolkit.saml.builder;

import gov.nist.toolkit.saml.util.SAMLCallback;
import gov.nist.toolkit.saml.util.SamlTokenExtractor;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.io.MarshallerFactory;
import org.opensaml.core.xml.io.Unmarshaller;
import org.opensaml.core.xml.io.UnmarshallerFactory;
import org.opensaml.core.config.Configuration;
import org.opensaml.saml.saml2.core.Assertion;


import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Srinivasarao.Eadara
 *
 */
public class OpenSamlBootStrap {
	private static XMLObjectBuilderFactory builderFactory;
    public static MarshallerFactory marshallerFactory;
    public static UnmarshallerFactory unmarshallerFactory;
    public static boolean samlEngineInitialized = false;
   
    public static SAMLCallback samlCallBack = null ;
    private static Logger log = Logger.getLogger(SamlTokenExtractor.class.getName());
    
    
    /**
	 * @return the unmarshallerFactory
	 */
	public static UnmarshallerFactory getUnmarshallerFactory() {
		return unmarshallerFactory;
	}



	/**
	 * @param unmarshallerFactory the unmarshallerFactory to set
	 */
	public static void setUnmarshallerFactory(
			UnmarshallerFactory unmarshallerFactory) {
		OpenSamlBootStrap.unmarshallerFactory = unmarshallerFactory;
	}



	/**
	 * @return the builderFactory
	 */
	public static XMLObjectBuilderFactory getBuilderFactory() {
		return builderFactory;
	}
	
    /**
     * Initialise the SAML library
     */
    public synchronized static void initSamlEngine() {
        if (!samlEngineInitialized) {
            log.fine("Initilizing the opensaml4.0.1 library...");
            try {
                // OpenSAML 4.0.1 - no explicit initialization needed
                builderFactory = Configuration.getBuilderFactory();
                marshallerFactory = Configuration.getMarshallerFactory();
                unmarshallerFactory = Configuration.getUnmarshallerFactory();
                samlEngineInitialized = true;
                log.fine("opensaml4.0.1 library bootstrap complete");
            } catch (Exception e) {
                log.log(Level.SEVERE,
                    "Unable to bootstrap the opensaml4.0.1 library - all SAML operations will fail", 
                    e
                );
            }
        }
    }

}
