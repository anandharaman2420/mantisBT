package com.mantis.api.soap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Small, dependency-free helpers for reading and writing SOAP 1.1 envelopes.
 *
 * <p>The parser is hardened against XXE: DOCTYPE declarations and external entities are rejected,
 * which is important because SOAP request bodies are fully attacker controlled.</p>
 */
final class SoapXml {
    private SoapXml() {}

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new SoapFault("Client", "Malformed SOAP request");
        }
    }

    /** @return the single element inside {@code soap:Body}. */
    static Element operation(Document doc) {
        Element envelope = doc.getDocumentElement();
        Element body = child(envelope, "Body");
        if (body == null) throw new SoapFault("Client", "SOAP Body is missing");
        List<Element> ops = children(body);
        if (ops.isEmpty()) throw new SoapFault("Client", "SOAP Body is empty");
        return ops.get(0);
    }

    static String localName(Element e) {
        return e.getLocalName() == null ? e.getNodeName() : e.getLocalName();
    }

    static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) out.add((Element) n);
        }
        return out;
    }

    static Element child(Element parent, String name) {
        for (Element e : children(parent)) if (name.equals(localName(e))) return e;
        return null;
    }

    /** Raw text of the first child with the given name, or {@code null}. */
    static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent();
    }

    static String trimmed(Element parent, String name) {
        String s = text(parent, name);
        return s == null ? null : s.trim();
    }

    static Integer intValue(Element parent, String name) {
        String s = trimmed(parent, name);
        if (s == null || s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new SoapFault("Client", "Field '" + name + "' must be an integer");
        }
    }

    static Long longValue(Element parent, String name) {
        String s = trimmed(parent, name);
        if (s == null || s.isEmpty()) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw new SoapFault("Client", "Field '" + name + "' must be an integer");
        }
    }

    static Boolean boolValue(Element parent, String name) {
        String s = trimmed(parent, name);
        if (s == null || s.isEmpty()) return null;
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    /** Escapes text for XML element content and attributes. */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
