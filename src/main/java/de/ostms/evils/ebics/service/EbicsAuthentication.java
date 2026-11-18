package de.ostms.evils.ebics.service;

import org.w3c.dom.*;
import java.util.*;
import java.security.*;
import javax.xml.crypto.*;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.*;
import javax.xml.crypto.dsig.spec.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;

/** X002 signature over precisely the EBICS authenticate=true node set. */
public final class EbicsAuthentication {
    private static final String URI="#xpointer(//*[@authenticate='true'])";
    /** The authenticate="true" nodes and the AuthSignature live in the document's own EBICS
     * namespace - H005 (EBICS 3.0) or H004 (EBICS 2.5) - so it is read from the root
     * instead of being hardwired. */
    private static String ns(Document doc){return doc.getDocumentElement().getNamespaceURI();}
    private static final String DS=XMLSignature.XMLNS;
    private static URIDereferencer dereferencer(Document doc) {
        return (ref,ctx) -> {
            if(!URI.equals(ref.getURI())) throw new URIReferenceException("Unsupported signature reference");
            var nodes=new LinkedHashSet<Node>();
            var all=doc.getElementsByTagNameNS(ns(doc),"*");
            for(int i=0;i<all.getLength();i++) {
                var e=(Element)all.item(i);
                if("true".equals(e.getAttribute("authenticate"))) collect(e,nodes);
            }
            return (NodeSetData<Node>)nodes::iterator;
        };
    }
    private static void collect(Node node,Set<Node> nodes) {
        nodes.add(node);
        if(node.hasAttributes()) for(int i=0;i<node.getAttributes().getLength();i++) nodes.add(node.getAttributes().item(i));
        for(Node c=node.getFirstChild();c!=null;c=c.getNextSibling()) collect(c,nodes);
    }
    public static String sign(String xml,PrivateKey key) throws Exception {
        var doc=HpbCodec.parse(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var root=doc.getDocumentElement();root.setAttributeNS("http://www.w3.org/2000/xmlns/","xmlns:ds",DS);
        var target=(Element)root.getElementsByTagNameNS(ns(doc),"AuthSignature").item(0);
        var f=XMLSignatureFactory.getInstance("DOM");
        var ref=f.newReference(URI,f.newDigestMethod(DigestMethod.SHA256,null),List.of(f.newTransform(CanonicalizationMethod.INCLUSIVE,(TransformParameterSpec)null)),null,null);
        var info=f.newSignedInfo(f.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE,(C14NMethodParameterSpec)null),f.newSignatureMethod(SignatureMethod.RSA_SHA256,null),List.of(ref));
        var ctx=new DOMSignContext(key,target);ctx.setDefaultNamespacePrefix("ds");ctx.setURIDereferencer(dereferencer(doc));
        f.newXMLSignature(info,null).sign(ctx);
        var wrapper=target.getFirstChild();while(wrapper.hasChildNodes())target.appendChild(wrapper.getFirstChild());target.removeChild(wrapper);
        return serialize(doc);
    }
    public static void verify(Document original,PublicKey key) throws Exception {
        var doc=(Document)original.cloneNode(true);
        var list=doc.getElementsByTagNameNS(ns(doc),"AuthSignature");
        if(list.getLength()!=1) throw new GeneralSecurityException("Missing authentication signature");
        var auth=(Element)list.item(0);
        var wrapper=doc.createElementNS(DS,"ds:Signature");
        auth.getParentNode().replaceChild(wrapper,auth);
        while(auth.hasChildNodes()) wrapper.appendChild(auth.getFirstChild());
        var ctx=new DOMValidateContext(key,wrapper);ctx.setURIDereferencer(dereferencer(doc));
        var sig=XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx);
        if(!SignatureMethod.RSA_SHA256.equals(sig.getSignedInfo().getSignatureMethod().getAlgorithm()) || sig.getSignedInfo().getReferences().size()!=1)
            throw new GeneralSecurityException("Invalid signature algorithm");
        var ref=(Reference)sig.getSignedInfo().getReferences().get(0);
        if(!URI.equals(ref.getURI()) || !DigestMethod.SHA256.equals(ref.getDigestMethod().getAlgorithm()) || ref.getTransforms().size()!=1
                || !CanonicalizationMethod.INCLUSIVE.equals(((Transform)ref.getTransforms().get(0)).getAlgorithm()))
            throw new GeneralSecurityException("Invalid signature coverage");
        if(!sig.validate(ctx)) throw new GeneralSecurityException("Invalid request signature");
    }
    public static String serialize(Node node) throws Exception {
        var transformer=TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");
        var out=new StringWriter();transformer.transform(new DOMSource(node),new StreamResult(out));return out.toString();
    }
}
