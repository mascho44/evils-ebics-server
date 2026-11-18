package de.ostms.evils.ebics.validation;

import de.ostms.evils.ebics.protocol.EbicsProtocolException;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.StringReader;
import java.io.InputStream;

public class EbicsSchemaValidator {

    private final javax.xml.validation.Schema schema;

    public EbicsSchemaValidator() {
        try {
            var xsd = getClass().getResourceAsStream("/ebics/H005/ebics_H005.xsd");
            if (xsd == null) {
                throw new IllegalStateException(
                    "Official H005 schema not installed. " +
                    "Place ebics_H005.xsd and its referenced XSD files under " +
                    "src/main/resources/ebics/H005/");
            }

            SchemaFactory factory = SchemaFactory.newInstance(
                    XMLConstants.W3C_XML_SCHEMA_NS_URI);

            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            this.schema = factory.newSchema(
                    new StreamSource(xsd));

        } catch (SAXException e) {
            throw new IllegalStateException("Could not load H005 schema", e);
        }
    }

    public void validate(String xml) {
        try {
            var validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new StreamSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new EbicsProtocolException(
                    "H005 schema validation failed: " + e.getMessage(), e);
        }
    }
}
