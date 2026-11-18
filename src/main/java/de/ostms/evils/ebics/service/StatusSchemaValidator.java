package de.ostms.evils.ebics.service;
import org.springframework.stereotype.Component;
import javax.xml.XMLConstants;
import javax.xml.validation.*;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
@Component
public class StatusSchemaValidator {
    private final Schema schema;
    public StatusSchemaValidator(){
        try{var factory=SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            schema=factory.newSchema(getClass().getResource("/schemas/pain.002.001.03.xsd"));
        }catch(Exception e){throw new IllegalStateException("Could not load status schema",e);}
    }
    public void validate(String xml){try{var validator=schema.newValidator();validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");validator.validate(new StreamSource(new StringReader(xml)));}
        catch(Exception e){throw new IllegalArgumentException("Invalid pain.002 status document",e);}}
}
