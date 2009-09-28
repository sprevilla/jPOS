/*
 * jPOS Project [http://jpos.org]
 * Copyright (C) 2000-2009 Alejandro P. Revilla
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jpos.util;

import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOUtil;
import org.jpos.space.Space;
import org.jpos.space.SpaceFactory;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * General purpose, Field Separator delimited message.
 * 
 * <h1>How to use</h1>
 * <p>
 * The message format (or schema) is defined in xml files containing a schema element, with an optional id attribute, and multiple
 * field elements. A field element is made up of the following attributes:
 * <dl>
 * <dt>id</dt>
 * <dd>The name of the field. This is used in calls to {@link FSDMsg#set(String, String)}. It should be unique amongst the fields in an FSDMsg.</dd>
 * <dt>length</dt>
 * <dd>The maximum length of the data allowed in this field. Fixed length fields will be padded to this length. A zero length is allowed, and can
 * be useful to define extra separator characters in the message.</dd>
 * <dt>type</dt>
 * <dd>The type of the included data, including an optional separator for marking the end of the field and the beginning of the next one. The data type
 * is defined by the first char of the type, and the separator is defined by the following chars. If a field separator is specified, then no
 * padding is done on values for this field.
 * </dd>
 * <dt>key</dt>
 * <dd>If this optional attribute has a value of "true", then fields from another schema, specified by the value, are appended to this schema.</dd>
 * </dl>
 * <p>
 * Possible types are:
 * <dl>
 * <dt>A</dt><dd>Alphanumeric. Padding if any is done with spaces to the right.</dd>
 * <dt>B</dt><dd>Binary. Padding, if any, is done with zeros to the left.</dd>
 * <dt>K</dt><dd>Constant. The value is specified by the field content. No padding is done.</dd>
 * <dt>N</dt><dd>Numeric. Padding, if any, is done with zeros to the left.</dd>
 * </dl>
 * </p>
 * <p>
 * Supported field separators are:
 * <dl>
 * <dt>FS</dt><dd>Field separator using '034' as the separator.</dd>
 * <dt>US</dt><dd>Field separator using '037' as the separator.</dd>
 * <dt>GS</dt><dd>Field separator using '035' as the separator.</dd>
 * <dt>RS</dt><dd>Field separator using '036' as the separator.</dd>
 * <dt>PIPE</dt><dd>Field separator using '|' as the separator.</dd>
 * <dt>EOF</dt><dd>End of Field - no separator character is emitted, but also no padding is done.</dd>
 * </dl>
 * </p>
 * <p>
 * Key fields allow you to specify a tree of possible message formats. The key fields are the fork points of the tree.
 * Multiple key fields are supported. It is also possible to have more key fields specified in appended schemas.
 * </p>
 * 
 * @author Alejandro Revila
 * @author Mark Salter
 * @author Dave Bergert
 * @since 1.4.7
 */
public class FSDMsg implements Loggeable, Cloneable {
    public static char FS = '\034';
    public static char US = '\037';
    public static char GS = '\035';
    public static char RS = '\036';
    public static char EOF = '\000';
    public static char PIPE = '\u007C';
    
    Map fields;
    Map separators;
    
    String baseSchema;
    String basePath;
    byte[] header;

    /**
     * Creates a FSDMsg with a specific base path for the message format schema.
     * @param basePath   schema path, for example: "file:src/data/NDC-" looks for a file src/data/NDC-base.xml
     */
    public FSDMsg (String basePath) {
        this (basePath, "base");
    }
    
    /**
     * Creates a FSDMsg with a specific base path for the message format schema, and a base schema name. For instance,
     * FSDMsg("file:src/data/NDC-", "root") will look for a file: src/data/NDC-root.xml
     * @param basePath   schema path
     * @param baseSchema schema name
     */
    public FSDMsg (String basePath, String baseSchema) {
        super();
        fields = new LinkedHashMap();
        separators = new LinkedHashMap();        
        this.basePath   = basePath;
        this.baseSchema = baseSchema;
        
        setSeparator("FS", FS);
        setSeparator("US", US);
        setSeparator("GS", GS);
        setSeparator("RS", RS);
        setSeparator("EOF", EOF);
        setSeparator("PIPE", PIPE);
    }
    public String getBasePath() {
        return basePath;
    }
    public String getBaseSchema() {
        return baseSchema;
    }
   
    /*
     * add a new or override an existing separator type/char pair.
     * 
     *  @param separatorName   string of type used in definition (FS, US etc)
     *  @param separator       char representing type
     */
    public void setSeparator(String separatorName, char separator) {
        separators.put(separatorName,new Character(separator));
    }
    
    /*
     * add a new or override an existing separator type/char pair.
     * 
     *  @param separatorName   string of type used in definition (FS, US etc)
     *  @param separator       char representing type
     */
    public void unsetSeparator(String separatorName) {
        if (separators.containsKey(separatorName)) {
            separators.remove(separatorName);
        } else {
            throw new RuntimeException("unsetSeparator was attempted for "+separatorName+" which was not previously defined.");
        }
    }
    
    /**
     * parse message. If the stream ends before the message is completely read, then the method adds an EOF field.
     *
     * @param is input stream
     *
     * @throws IOException
     * @throws JDOMException
     * @throws MalformedURLException
     */
    public void unpack (InputStream is) 
        throws IOException, JDOMException, MalformedURLException {
        try {
            unpack (is, getSchema (baseSchema));
        } catch (EOFException e) {
            fields.put ("EOF", "true");
        }
    }
    /**
     * parse message. If the stream ends before the message is completely read, then the method adds an EOF field.
     *
     * @param b message image
     *
     * @throws IOException
     * @throws JDOMException
     * @throws MalformedURLException
     * @throws ISOException 
     */
    public void unpack (byte[] b) 
        throws IOException, JDOMException, MalformedURLException {
        unpack (new ByteArrayInputStream (b));
    }

    /**
     * @return message string
     * @throws ISOException 
     */
    public String pack () 
        throws JDOMException, MalformedURLException, IOException, ISOException
    {
        StringBuffer sb = new StringBuffer ();
        pack (getSchema (baseSchema), sb);
        return sb.toString ();
    }
    public byte[] packToBytes () 
        throws JDOMException, MalformedURLException, IOException, ISOException, UnsupportedEncodingException
    {
        return pack().getBytes("ISO8859_1");
    }

    protected String get (String id, String type, int length, String defValue) 
        throws ISOException
    {
        String value = (String) fields.get (id);
        if (value == null)
            value = defValue == null ? "" : defValue;

        type   = type.toUpperCase ();

        switch (type.charAt (0)) {
            case 'N':
                if (isSeparated(type)) {
                    // Leave value unpadded.
                } else {
                    value = ISOUtil.zeropad (value, length);
                }
                break;
            case 'A':
                if (isSeparated(type)) {
                    // Leave value unpadded.
                } else {
                    value = ISOUtil.strpad (value, length);
                }
                if (value.length() > length)
                    value = value.substring(0,length);
                break;
            case 'K':
                if (defValue != null)
                    value = defValue;
                break;
            case 'B':
                try {
                    if ((length << 1) >= value.length()) {
                        if (isSeparated(type)) {
                            // Convert but do not pad if this field ends with a
                            // separator
                            value = new String(ISOUtil.hex2byte(value), "ISO8859_1");
                        } else {
                            value = new String(ISOUtil.hex2byte(ISOUtil.zeropad(
                                    value, length << 1).substring(0, length << 1)),
                                    "ISO8859_1");
                        }
                    } else {
                        throw new RuntimeException("field content=" + value
                                + " is too long to fit in field " + id
                                + " whose length is " + length);
                    }
                } catch (UnsupportedEncodingException e) {
                    // ISO8859_1 is supported
                }
            break;
        }
        return (isSeparated(type)) ? ISOUtil.blankUnPad(value) : value;
    }
    
    private boolean isSeparated(String type) {
        /*
         * if type's last two characters appear in our Map of separators,
         * return true
         */
        if (type.length() > 2) {
            if (separators.containsKey(getSeparatorType(type))) {
                return true;
            } else {
                if (type.endsWith("DS")) { 
                    return true;
                }
                
                throw new RuntimeException("FSDMsg.isSeparated(String) found that type of "+type+" is used, but "+getSeparatorType(type)+" has not been defined as a separator!");
            }
        }
        return false;

    }

    private boolean isDummySeparator(String type) {
        /*
         * if type's last two characters appear in our Map of separators, return
         * true
         */
        if (type.endsWith("DS")) {
            return true;
        }
        return false;

    }
    
    private boolean isBinary(String type) {
        /*
         * if type's first digit is a 'B' return true
         */
        return type.startsWith("B");
    }
    
    public boolean isSeparator(byte b) {
        return separators.containsValue(new Character((char)b));
    }
    
    private String getSeparatorType(String type) {
        if (type.length() > 2) {
            return type.substring(1);
        }
        return null;
    }
    
    private char getSeparator(String type) {
        if (type.length() > 2) {
            if (separators.containsKey(getSeparatorType(type))) {
                return ((Character)separators.get(getSeparatorType(type))).charValue();
            } else {
                if (type.endsWith("DS")) {
                    // Dummy separator type, return 0 to indicate nothing to add.
                    return 0;
                }
            }
        }
        
        throw new RuntimeException("getSeparator called on for type="+type+" which does not resolve to a known separator.");
    }

    protected void pack (Element schema, StringBuffer sb) 
        throws JDOMException, MalformedURLException, IOException, ISOException
    {
        String keyOff = "";
        Iterator iter = schema.getChildren("field").iterator();
        while (iter.hasNext()) {
            Element elem = (Element) iter.next ();
            String id    = elem.getAttributeValue ("id");
            int length   = Integer.parseInt (elem.getAttributeValue ("length"));
            String type  = elem.getAttributeValue ("type");
            boolean key  = "true".equals (elem.getAttributeValue ("key"));
            String defValue = elem.getText();
            String value = get (id, type, length, defValue);
            sb.append (value);
            
            if (isSeparated(type)) {
                char c = getSeparator(type);
                if (c > 0)
                    sb.append(c);
            }
            if (key)
                keyOff = keyOff + ISOUtil.normalize(value);
        }
        if (keyOff.length() > 0) 
            pack (getSchema (getId (schema) + keyOff), sb);
    }

    protected void unpack (InputStream is, Element schema) 
        throws IOException, JDOMException, MalformedURLException  
    
    {
        Iterator iter = schema.getChildren("field").iterator();
        String keyOff = "";
        while (iter.hasNext()) {
            Element elem = (Element) iter.next();

            String id    = elem.getAttributeValue ("id");
            int length   = Integer.parseInt (elem.getAttributeValue ("length"));
            String type  = elem.getAttributeValue ("type").toUpperCase();
            boolean key  = "true".equals (elem.getAttributeValue ("key"));
            String value = readField (
                is, id, length, type );
            
            if (key)
                keyOff = keyOff + ISOUtil.normalize(value);

            if ("K".equals(type) && !value.equals (elem.getText()))
                throw new IllegalArgumentException (
                    "Field "+id 
                       + " value='"     +value
                       + "' expected='" + elem.getText () + "'"
                );
        }
        if (keyOff.length() > 0) {
            unpack (is, 
                getSchema (getId (schema) + keyOff)
            );
        }
    }
    private String getId (Element e) {
        String s = e.getAttributeValue ("id");
        return s == null ? "" : s;
    }
    protected String read (InputStream is, int len, String type) 
        throws IOException 
    {
        StringBuffer sb = new StringBuffer();
        byte[] b = new byte[1];
        boolean expectSeparator = isSeparated(type);
        boolean separated = expectSeparator;

        if (isDummySeparator(type)) {
            /*
             * No need to look for a seperator, that is not there! Try and take
             * len bytes from the is.
             */
            for (int i = 0; i < len; i++) {
                if (is.read(b) < 0) {
                    break; // end of stream indicates end of field?
                }
                sb.append((char) (b[0] & 0xff));
            }
        } else {

            for (int i = 0; i < len; i++) {
                if (is.read(b) < 0) {
                    if (!type.endsWith("EOF"))
                        throw new EOFException();
                    else {
                        separated = false;
                        break;
                    }
                }
                if (expectSeparator && (b[0] == getSeparator(type))) {
                    separated = false;
                    break;
                }
                sb.append((char) (b[0] & 0xff));
            }

            if (separated && !type.endsWith("EOF")) {
                if (is.read(b) < 0) {
                    throw new EOFException();
                }
            }
        }
        return sb.toString();
    }
    protected String readField 
        (InputStream is, String fieldName, int len, String type) 
        throws IOException
    {
        String fieldValue = read (is, len, type);
        
        if (isBinary(type))
            fieldValue = ISOUtil.hexString (fieldValue.getBytes ("ISO8859_1"));
        fields.put (fieldName, fieldValue);
//         System.out.println ("++++ "+fieldName + ":" + fieldValue + " " + type + "," + isBinary(type));
        return fieldValue;
    }
    public void set (String name, String value) {
        if (value != null)
            fields.put (name, value);
        else
            fields.remove (name);
    }
    public void setHeader (byte[] h) {
        this.header = h;
    }
    public byte[] getHeader () {
        return header;
    }
    public String getHexHeader () {
        return header != null ? ISOUtil.hexString (header).substring (2) : "";
    }
    public String get (String fieldName) {
        return (String) fields.get (fieldName);
    }
    public String get (String fieldName, String def) {
        String s = (String) fields.get (fieldName);
        return s != null ? s : def;
    }
    public void copy (String fieldName, FSDMsg msg) {
        fields.put (fieldName, msg.get (fieldName));
    }
    public byte[] getHexBytes (String name) {
        String s = get (name);
        return s == null ? null : ISOUtil.hex2byte (s);
    }
    public int getInt (String name) {
        int i = 0;
        try {
            i = Integer.parseInt (get (name));
        } catch (Exception e) { }
        return i;
    }
    public int getInt (String name, int def) {
        int i = def;
        try {
            i = Integer.parseInt (get (name));
        } catch (Exception e) { }
        return i;
    }
    public Element toXML () {
        Element e = new Element ("message");
        if (header != null) {
            e.addContent (
                new Element ("header")
                    .setText (getHexHeader ())
            );
        }
        Iterator iter = fields.keySet().iterator();
        while (iter.hasNext()) {
            String fieldName = (String) iter.next();
            Element inner = new Element (fieldName);
            inner.addContent (ISOUtil.normalize ((String) fields.get (fieldName)));
            e.addContent (inner);
        }
        return e;
    }
    protected Element getSchema () 
        throws JDOMException, MalformedURLException, IOException {
        return getSchema (baseSchema);
    }
    protected Element getSchema (String message) 
        throws JDOMException, MalformedURLException, IOException {
        StringBuffer sb = new StringBuffer (basePath);
        sb.append (message);
        sb.append (".xml");
        String uri = sb.toString ();

        Space sp = SpaceFactory.getSpace();
        Element schema = (Element) sp.rdp (uri);
        if (schema == null) {
            synchronized (FSDMsg.class) {
                schema = (Element) sp.rdp (uri);
                if (schema == null) {
                    SAXBuilder builder = new SAXBuilder ();
                    URL url = new URL (uri);
                    File f = new File(url.getFile());
                    if (f.exists()) {
                        schema = builder.build (url).getRootElement ();
                    } else {
                        throw new RuntimeException(f.getCanonicalPath().toString() + " not found");
                    }
                }
                sp.out (uri, schema);
            }
        } 
        return schema;
    }
    /**
     * @return message's Map
     */
    public Map getMap () {
        return fields;
    }
    public void setMap (Map fields) {
        this.fields = fields;
    }
    public void dump (PrintStream p, String indent) {
        String inner = indent + "  ";
        p.println (indent + "<fsdmsg schema='" + basePath + baseSchema  + "'>");
        if (header != null) {
            append (p, "header", getHexHeader(), inner);
        }
        Iterator iter = fields.keySet().iterator();
        while (iter.hasNext()) {
            String f = (String) iter.next();
            String v = ((String) fields.get (f));
            append (p, f, v, inner);
        }
        p.println (indent + "</fsdmsg>");
    }
    private void append (PrintStream p, String f, String v, String indent) {
        p.println (indent + f + ": '" + v + "'");
    }
    public boolean hasField(String fieldName) {
        return fields.containsKey(fieldName);
    }
    public Object clone() {
        try {              
            FSDMsg m = (FSDMsg) super.clone();
	    m.fields = (Map) ((LinkedHashMap) fields).clone();
            return m;
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }
}

