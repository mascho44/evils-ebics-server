package de.ostms.evils.ebics.protocol;
import javax.xml.XMLConstants;
import javax.xml.validation.SchemaFactory;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
public final class SchemaAssertions {
    public static void response(String xml)throws Exception{
        validate(xml,"/schemas/h005/ebics_response_H005.xsd");
    }
    public static void responseH004(String xml)throws Exception{
        validate(xml,"/schemas/h004/ebics_response_H004.xsd");
    }
    public static void camt(String xml)throws Exception{validate(xml,"/schemas/camt.053.001.08.xsd");}
    public static void veu(String xml)throws Exception{validate(xml,"/schemas/h005/ebics_orders_H005.xsd");}
    // The official xmldsig-core-schema.xsd starts with a DOCTYPE that points to an external
    // "XMLSchema.dtd". Newer JDKs ignore that reference, JDK 21 (what CI runs) tries to load it, which
    // our deliberately closed ACCESS_EXTERNAL_DTD forbids. The bundled official files stay untouched;
    // the reference (not the internal subset with the entities) is dropped while the file is loaded.
    private static final Pattern EXTERNAL_DTD=Pattern.compile("PUBLIC\\s+\"[^\"]*\"\\s+\"XMLSchema\\.dtd\"");
    private static LSInput withoutExternalDtd(String systemId,String baseUri)throws Exception{
        if(systemId==null||!systemId.endsWith("xmldsig-core-schema.xsd"))return null;
        URI resolved=baseUri==null?URI.create(systemId):URI.create(baseUri).resolve(systemId);
        String text=new String(resolved.toURL().openStream().readAllBytes(),StandardCharsets.UTF_8);
        var ls=(DOMImplementationLS)DOMImplementationRegistry.newInstance().getDOMImplementation("LS");
        LSInput input=ls.createLSInput();
        input.setSystemId(resolved.toString());
        input.setByteStream(new java.io.ByteArrayInputStream(EXTERNAL_DTD.matcher(text).replaceFirst("").getBytes(StandardCharsets.UTF_8)));
        return input;
    }
    private static void validate(String xml,String path)throws Exception{
        var factory=SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"file");
        factory.setResourceResolver((type,namespace,publicId,systemId,baseUri)->{
            try{return withoutExternalDtd(systemId,baseUri);}catch(Exception e){throw new IllegalStateException(e);}
        });
        var schema=factory.newSchema(SchemaAssertions.class.getResource(path));
        schema.newValidator().validate(new StreamSource(new StringReader(xml)));
    }
}
