package gov.nist.toolkit.saml.attributes;
import gov.nist.toolkit.saml.bean.SamlUtil;
import gov.nist.toolkit.saml.util.SamlConstants;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.saml.saml2.core.impl.AttributeBuilder;
import org.opensaml.core.xml.Namespace;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.schema.XSAny;
import org.opensaml.core.xml.schema.impl.XSAnyBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class AttributeUtil extends SamlConstants {
	public static final String VERSION = "$Id: AttributeUtil.java 2950 2008-05-28 08:22:34Z jre $";


	// Note: BasicParserPool needs to be updated for OpenSAML 4.0.1
	// For now, using standard DocumentBuilderFactory
	protected static DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();

	/** Default atrributes for AttributeValue */
	public static final QName XSI_TYPE_ATTRIBUTE_NAME = new QName(javax.xml.XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type", "xsi");

	public static final String XS_STRING = "xsd:" + XSString.TYPE_LOCAL_NAME;

	/** QName for the attribute resource */
	public static Attribute createAttribute(String name, String friendlyName,String nameFormat) {
		Attribute attribute = new AttributeBuilder().buildObject();
		attribute.setName(name);
		attribute.setFriendlyName(friendlyName);
		attribute.setNameFormat(nameFormat);
		return attribute;
	}

	private static XSAny createAttributeValue() {
		XSAnyBuilder builder = new XSAnyBuilder();
		XSAny ep = builder.buildObject("urn:oasis:names:tc:SAML:2.0:assertion",
										"AttributeValue",
										"saml");
		return ep;
	}

	public static XSAny createAttributeValue(String value, String type) {
		XSAny ep = createAttributeValue();
		ep.setTextContent(String.valueOf(value));
		ep.getUnknownAttributes().put(XSI_TYPE_ATTRIBUTE_NAME, type);
		
		return ep;
	}

	public static XSAny createAttributeValue(String value) {
		return createAttributeValue(value, XS_STRING);
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given
	 * surname
	 * 
	 * @param value
	 *            The surname
	 * @return The attribute
	 */
	public static Attribute createSurname(String value) {
		Attribute attribute = createAttribute(ATTRIBUTE_SURNAME_NAME,
				ATTRIBUTE_SURNAME_FRIENDLY_NAME, URI_ATTRIBUTE_NAME_FORMAT);
		if (value != null) {
			attribute.getAttributeValues().add(createAttributeValue(value));
		}
		return attribute;
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given
	 * commonName
	 * 
	 * @param value
	 *            The commonName
	 * @return The attribute
	 */
	public static Attribute createCommonName(String value) {
		Attribute attribute = createAttribute(ATTRIBUTE_COMMON_NAME_NAME,
				ATTRIBUTE_COMMON_NAME_FRIENDLY_NAME, URI_ATTRIBUTE_NAME_FORMAT);
		if (value != null) {
			attribute.getAttributeValues().add(createAttributeValue(value));
		}
		return attribute;
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given uid
	 * (userId)
	 * 
	 * @param value
	 *            The uid
	 * @return The attribute
	 */
	public static Attribute createUid(String value) {
		Attribute attribute = createAttribute(ATTRIBUTE_UID_NAME,
				ATTRIBUTE_UID_FRIENDLY_NAME, URI_ATTRIBUTE_NAME_FORMAT);
		if (value != null) {
			attribute.getAttributeValues().add(createAttributeValue(value));
		}
		return attribute;
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given
	 * mailAddress
	 * 
	 * @param value
	 *            The mail address
	 * @return The attribute
	 */
	public static Attribute createMail(String value) {
		Attribute attribute = createAttribute(ATTRIBUTE_MAIL_NAME,
				ATTRIBUTE_MAIL_FRIENDLY_NAME, URI_ATTRIBUTE_NAME_FORMAT);
		if (value != null) {
			attribute.getAttributeValues().add(createAttributeValue(value));
		}
		return attribute;
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given
	 * cvrNumber
	 * 
	 * @param value
	 *            The cvrNumber
	 * @return The attribute
	 */
	public static Attribute createCVRNumberIdentifier(String value) {
		Attribute attribute = createAttribute(
				ATTRIBUTE_CVR_NUMBER_IDENTIFIER_NAME,
				ATTRIBUTE_CVR_NUMBER_IDENTIFIER_FRIENDLY_NAME,
				URI_ATTRIBUTE_NAME_FORMAT);
		if (value != null) {
			attribute.getAttributeValues().add(createAttributeValue(value));
		}
		return attribute;
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given
	 * serialNumber
	 * 
	 * @param value
	 *            The serialNumber of the certificate
	 * @return The attribute
	 */
	public static Attribute createSerialNumber(String value) {
		Attribute attribute = createAttribute(ATTRIBUTE_SERIAL_NUMBER_NAME,
				ATTRIBUTE_SERIAL_NUMBER_FRIENDLY_NAME,
				URI_ATTRIBUTE_NAME_FORMAT);
		if (value != null) {
			attribute.getAttributeValues().add(createAttributeValue(value));
		}
		return attribute;
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given
	 * pidNumberIdentifier
	 * 
	 * @param value
	 *            The pidNumberIdentier of the certificate
	 * @return The attribute
	 */
	public static Attribute createPidNumberIdentifier(String value) {
		Attribute attribute = createAttribute(
				ATTRIBUTE_PID_NUMBER_IDENTIFIER_NAME,
				ATTRIBUTE_PID_NUMBER_IDENTIFIER_FRIENDLY_NAME,
				URI_ATTRIBUTE_NAME_FORMAT);
		if (value != null) {
			attribute.getAttributeValues().add(createAttributeValue(value));
		}
		return attribute;
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given
	 * ridNumberIdentifier
	 * 
	 * @param value
	 *            The pidNumberIdentier of the certificate
	 * @return The attribute
	 */
	public static Attribute createRidNumberIdentifier(String value) {
		Attribute attribute = createAttribute(
				ATTRIBUTE_RID_NUMBER_IDENTIFIER_NAME,
				ATTRIBUTE_RID_NUMBER_IDENTIFIER_FRIENDLY_NAME,
				URI_ATTRIBUTE_NAME_FORMAT);
		if (value != null) {
			attribute.getAttributeValues().add(createAttributeValue(value));
		}
		return attribute;
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given
	 * userCertificate
	 * 
	 * @param value
	 *            The user certificate
	 * @return The attribute
	 */
	public static Attribute createUserCertificate(String value) {
		Attribute attribute = createAttribute(ATTRIBUTE_USER_CERTIFICATE_NAME,
				ATTRIBUTE_USER_CERTIFICATE_FRIENDLY_NAME,
				URI_ATTRIBUTE_NAME_FORMAT);
		if (value != null) {
			attribute.getAttributeValues().add(createAttributeValue(value));
		}
		return attribute;
	}

	/**
	 * Create a SAML20 attribute containing one attribute value with a given
	 * assuranceLevel
	 * 
	 * @param value
	 *            The assuranceLevel
	 * @return The attribute
	 */
	public static Attribute createAssuranceLevel(int value) {
		Attribute attribute = createAttribute(ATTRIBUTE_ASSURANCE_LEVEL_NAME,
				ATTRIBUTE_ASSURANCE_LEVEL_FRIENDLY_NAME,
				URI_ATTRIBUTE_NAME_FORMAT);
		if (value != 0) {
			attribute.getAttributeValues().add(
					createAttributeValue(String.valueOf(value)));
		}
		return attribute;
	}

	/**
	 * Extract the value of the first attributeValue within an SAML20 attribute
	 * 
	 * @param attribute
	 *            The attribute
	 * @return The text value of the attributeValue
	 * @throws Exception 
	 */
	public static String extractAttributeValueValue(Attribute attribute) throws Exception {
		for (int i = 0; i < attribute.getAttributeValues().size(); i++) {
			if (attribute.getAttributeValues().get(i) instanceof XSString) {
				XSString str = (XSString) attribute.getAttributeValues().get(i);
				if ("AttributeValue".equals(str.getElementQName().getLocalPart())
						&& "urn:oasis:names:tc:SAML:2.0:assertion".equals(str.getElementQName().getNamespaceURI())) {
					return str.getValue();
				}
			} else {
				XSAny ep = (XSAny) attribute.getAttributeValues().get(i);
				if ("AttributeValue".equals(ep.getElementQName().getLocalPart())
						&& "urn:oasis:names:tc:SAML:2.0:assertion".equals(ep.getElementQName().getNamespaceURI())) {
					if (ep.getUnknownXMLObjects().size() > 0) {
						StringBuilder res = new StringBuilder();
						for (XMLObject obj : ep.getUnknownXMLObjects()) {
							res.append(SamlUtil.marshallObject(obj).getTextContent());
						}
						return res.toString();
					}
					return ep.getTextContent();
				}
			}
		}
		return null;
	}

	/**
	 * Extract all attribute values within an SAML20 attribute
	 * 
	 * @param attribute The attribute
	 * @return A list containing the text value of each attributeValue
	 * @throws Exception 
	 */
	public static List<String> extractAttributeValueValues(Attribute attribute) throws Exception {
		List<String> values = new ArrayList<String>();
		for (int i = 0; i < attribute.getAttributeValues().size(); i++) {
			if (attribute.getAttributeValues().get(i) instanceof XSString) {
				XSString str = (XSString) attribute.getAttributeValues().get(i);
				if ("AttributeValue".equals(str.getElementQName().getLocalPart())
						&& "urn:oasis:names:tc:SAML:2.0:assertion".equals(str.getElementQName().getNamespaceURI())) {
					values.add(str.getValue());
				}
			} else {
				XSAny ep = (XSAny) attribute.getAttributeValues().get(i);
				if ("AttributeValue".equals(ep.getElementQName().getLocalPart())
						&& "urn:oasis:names:tc:SAML:2.0:assertion".equals(ep.getElementQName().getNamespaceURI())) {
					if (ep.getUnknownXMLObjects().size() > 0) {
						StringBuilder res = new StringBuilder();
						for (XMLObject obj : ep.getUnknownXMLObjects()) {
							res.append(SamlUtil.marshallObject(obj).getTextContent());
						}
						values.add(res.toString());
					}
					values.add(ep.getTextContent());
				}
			}
		}
		return values;
	}
	
	
	
	public static Hashtable<String, String> getCodeAttributes(String xmlstring) {
		Hashtable<String, String> codeAttributes = new Hashtable<String, String>();
		try {
			
			byte[] bytes = xmlstring.getBytes();
			ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
	    	Document document = null;
	    	dbFactory.setNamespaceAware(true);
	    	DocumentBuilder builder = dbFactory.newDocumentBuilder();
	    	document = builder.parse(bais);
			
	    	// Document d = XML.parse(xmlstring);
			NodeList nl = document.getChildNodes();
			
			for ( int i = 0 ; i < nl.getLength() ; i++) {
				Node node = nl.item(i);
				NamedNodeMap nnm = node.getAttributes();
				for (int j = 0 ; j < nnm.getLength(); j++ ) {
					Node n = nnm.item(j);
					// System.out.println("Node Name: " + n.getNodeName());
					// System.out.println("Node Value: " + n.getNodeValue());
					codeAttributes.put(n.getNodeName(), n.getNodeValue());
					// System.out.println("attribute name["+ j +"]:  " + n.getNodeName());
					// System.out.println("value["+ j +"]: " + n.getNodeValue());
				}
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
		
		return codeAttributes;
}
}

