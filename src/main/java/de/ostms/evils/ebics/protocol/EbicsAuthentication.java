package de.ostms.evils.ebics.protocol;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.crypto.*;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.*;

/** X002 signature over the EBICS authenticate=true node set. Namespace-wildcarded (H004 or
 * H005 - see EbicsResponseBuilder) since the signature only ever targets the authenticate="true"
 * attribute, never the element's specific EBICS protocol namespace. */
public final class EbicsAuthentication {
    private static final String URI="#xpointer(//*[@authenticate='true'])";
    private static final String DS=XMLSignature.XMLNS;
    private EbicsAuthentication() {}
    private static URIDereferencer dereferencer(Document doc){return(ref,ctx)->{if(!URI.equals(ref.getURI()))throw new URIReferenceException("Unsupported signature reference");var nodes=new LinkedHashSet<Node>();var all=doc.getElementsByTagNameNS("*","*");for(int i=0;i<all.getLength();i++){var e=(Element)all.item(i);if("true".equals(e.getAttribute("authenticate")))collect(e,nodes);}return(NodeSetData<Node>)nodes::iterator;};}
    private static void collect(Node node,Set<Node> nodes){nodes.add(node);if(node.hasAttributes())for(int i=0;i<node.getAttributes().getLength();i++)nodes.add(node.getAttributes().item(i));for(Node c=node.getFirstChild();c!=null;c=c.getNextSibling())collect(c,nodes);}
    public static String sign(String xml,PrivateKey key)throws Exception{
        var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        var doc=f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));var root=doc.getDocumentElement();root.setAttributeNS("http://www.w3.org/2000/xmlns/","xmlns:ds",DS);
        var target=(Element)root.getElementsByTagNameNS("*","AuthSignature").item(0);if(target==null)throw new IllegalArgumentException("Response has no AuthSignature placeholder");
        var factory=XMLSignatureFactory.getInstance("DOM");var ref=factory.newReference(URI,factory.newDigestMethod(DigestMethod.SHA256,null),List.of(factory.newTransform(CanonicalizationMethod.INCLUSIVE,(TransformParameterSpec)null)),null,null);
        var info=factory.newSignedInfo(factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE,(C14NMethodParameterSpec)null),factory.newSignatureMethod(SignatureMethod.RSA_SHA256,null),List.of(ref));var ctx=new DOMSignContext(key,target);ctx.setDefaultNamespacePrefix("ds");ctx.setURIDereferencer(dereferencer(doc));factory.newXMLSignature(info,null).sign(ctx);
        var wrapper=target.getFirstChild();while(wrapper.hasChildNodes())target.appendChild(wrapper.getFirstChild());target.removeChild(wrapper);
        var transformer=TransformerFactory.newInstance().newTransformer();transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");var out=new StringWriter();transformer.transform(new DOMSource(doc),new StreamResult(out));return out.toString();
    }
    public static void verify(Document original,java.security.PublicKey key)throws Exception{
        var doc=(Document)original.cloneNode(true);
        var list=doc.getElementsByTagNameNS("*","AuthSignature");
        if(list.getLength()!=1) throw new GeneralSecurityException("Missing authentication signature");
        var auth=(Element)list.item(0);
        var wrapper=doc.createElementNS(DS,"ds:Signature");
        auth.getParentNode().replaceChild(wrapper,auth);
        while(auth.hasChildNodes()) wrapper.appendChild(auth.getFirstChild());
        var ctx=new javax.xml.crypto.dsig.dom.DOMValidateContext(key,wrapper);ctx.setURIDereferencer(dereferencer(doc));
        var sig=XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx);
        if(!SignatureMethod.RSA_SHA256.equals(sig.getSignedInfo().getSignatureMethod().getAlgorithm()) || sig.getSignedInfo().getReferences().size()!=1)
            throw new GeneralSecurityException("Invalid signature algorithm");
        var ref=(Reference)sig.getSignedInfo().getReferences().get(0);
        if(!URI.equals(ref.getURI()) || !DigestMethod.SHA256.equals(ref.getDigestMethod().getAlgorithm()) || ref.getTransforms().size()!=1
                || !CanonicalizationMethod.INCLUSIVE.equals(((Transform)ref.getTransforms().get(0)).getAlgorithm()))
            throw new GeneralSecurityException("Invalid signature coverage");
        if(!sig.validate(ctx)) throw new GeneralSecurityException("Invalid request signature");
    }
}
