package gov.nist.toolkit.saml.builder;

import gov.nist.toolkit.saml.util.SAMLCallback;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.io.MarshallerFactory;
import org.opensaml.core.xml.io.Unmarshaller;
import org.opensaml.core.xml.io.UnmarshallerFactory;
import org.opensaml.core.config.Configuration;
import org.opensaml.core.xml.config.XMLObjectProviderRegistry;
import org.opensaml.core.impl.config.XMLObjectProviderRegistrySupport;
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
    private static Logger log = Logger.getLogger(OpenSamlBootStrap.class.getName());
    
    
    /**
	 * @return the unmarshallerFactory
	 */
	public static UnmarshallerFactory getUnmarshallerFactory() {
		return unmarshallerFactory;
	}

	/**
	 * @return the marshallerFactory
	 */
	public static MarshallerFactory getMarshallerFactory() {
		return marshallerFactory;
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
		// In OpenSAML 4.0.1, use the service loader approach
		return builderFactory;
	}
	
    /**
     * Initialise the SAML library
     */
    public synchronized static void initSamlEngine() {
        if (!samlEngineInitialized) {
            log.fine("Initializing the opensaml5.1.4 library...");
            try {
                // OpenSAML 5.1.4 - use XMLObjectProviderRegistrySupport
                builderFactory = XMLObjectProviderRegistrySupport.getBuilderFactory();
                marshallerFactory = XMLObjectProviderRegistrySupport.getMarshallerFactory();
                unmarshallerFactory = XMLObjectProviderRegistrySupport.getUnmarshallerFactory();
                
                if (builderFactory != null && marshallerFactory != null && unmarshallerFactory != null) {
                    samlEngineInitialized = true;
                    log.fine("opensaml5.1.4 library bootstrap complete");
                } else {
                    log.warning("opensaml5.1.4 library bootstrap incomplete - some factories are null");
                }
            } catch (Exception e) {
                log.log(Level.SEVERE,
                    "Unable to bootstrap the opensaml5.1.4 library - all SAML operations will fail", 
                    e
                );
            }
        }
    }

}
