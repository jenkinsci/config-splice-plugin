package io.jenkins.plugins.configsplice.engine;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Encoding detection and admission control (SRS section 10.3).
 *
 * <p>Version 1.0 reads and writes UTF-8 only, with or without a BOM. Everything else is detected
 * and rejected <em>before</em> parsing, so an unsupported file is never partially understood and
 * never written back. This is a safety property, not a convenience one: silently transcoding a
 * file, or decoding it with the wrong charset and re-encoding it, would corrupt bytes the user
 * never asked us to touch.
 */
public final class EncodingSupport {

    /** Byte order marks we recognise. Only {@link #UTF_8} and {@link #NONE} are supported for processing. */
    public enum Bom {
        NONE(new byte[0]),
        UTF_8(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}),
        UTF_16LE(new byte[] {(byte) 0xFF, (byte) 0xFE}),
        UTF_16BE(new byte[] {(byte) 0xFE, (byte) 0xFF}),
        UTF_32LE(new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x00}),
        UTF_32BE(new byte[] {0x00, 0x00, (byte) 0xFE, (byte) 0xFF});

        private final byte[] signature;

        Bom(byte[] signature) {
            this.signature = signature;
        }

        public int length() {
            return signature.length;
        }

        public byte[] bytes() {
            return signature.clone();
        }
    }

    private EncodingSupport() {
    }

    /**
     * Detects a byte order mark.
     *
     * <p>UTF-32 signatures are tested before UTF-16 because the UTF-32LE mark begins with the
     * UTF-16LE mark; testing in the other order would misclassify a UTF-32LE file as UTF-16LE.
     */
    public static Bom detectBom(byte[] bytes) {
        if (startsWith(bytes, Bom.UTF_32LE.signature)) {
            return Bom.UTF_32LE;
        }
        if (startsWith(bytes, Bom.UTF_32BE.signature)) {
            return Bom.UTF_32BE;
        }
        if (startsWith(bytes, Bom.UTF_8.signature)) {
            return Bom.UTF_8;
        }
        if (startsWith(bytes, Bom.UTF_16LE.signature)) {
            return Bom.UTF_16LE;
        }
        if (startsWith(bytes, Bom.UTF_16BE.signature)) {
            return Bom.UTF_16BE;
        }
        return Bom.NONE;
    }

    /**
     * Rejects any BOM other than none or UTF-8.
     *
     * @throws SpliceException with {@link ErrorCode#UNSUPPORTED_ENCODING}
     */
    public static void requireSupportedBom(Bom bom) throws SpliceException {
        if (bom != Bom.NONE && bom != Bom.UTF_8) {
            throw new SpliceException(
                    ErrorCode.UNSUPPORTED_ENCODING,
                    "file has a " + bom.name() + " byte order mark; Version 1.0 supports UTF-8 only");
        }
    }

    /**
     * Validates the {@code encoding} pseudo-attribute of an XML declaration, when present.
     *
     * <p>Accepted: no declaration, a declaration without {@code encoding}, or {@code encoding}
     * naming UTF-8 in any ASCII letter case with either quote style. Everything else — including
     * {@code us-ascii}, which would round-trip harmlessly today but silently break the first time a
     * non-ASCII replacement is written — is rejected per SRS section 10.3.
     *
     * <p>A UTF-16/UTF-32 declaration paired with a UTF-8 BOM is covered by the same rejection, so
     * the "BOM/declaration conflict" requirement needs no separate check while only UTF-8 is
     * supported.
     */
    public static void requireSupportedXmlDeclaration(String text) throws SpliceException {
        String declared = declaredXmlEncoding(text);
        if (declared == null) {
            return;
        }
        String normalised = declared.trim().toLowerCase(Locale.ROOT);
        if (!"utf-8".equals(normalised) && !"utf8".equals(normalised)) {
            throw new SpliceException(
                    ErrorCode.UNSUPPORTED_ENCODING,
                    "XML declaration names encoding '" + normalised + "'; Version 1.0 supports UTF-8 only");
        }
    }

    /**
     * Extracts the {@code encoding} pseudo-attribute from a leading XML declaration.
     *
     * @return the declared encoding, or {@code null} if there is no declaration or no encoding in it
     */
    static String declaredXmlEncoding(String text) {
        if (!text.startsWith("<?xml")) {
            return null;
        }
        int close = text.indexOf("?>");
        if (close < 0) {
            // Malformed; leave the diagnosis to the XML parser rather than guessing here.
            return null;
        }
        String declaration = text.substring(0, close);
        int at = declaration.indexOf("encoding");
        if (at < 0) {
            return null;
        }
        int equals = declaration.indexOf('=', at + "encoding".length());
        if (equals < 0) {
            return null;
        }
        int i = equals + 1;
        while (i < declaration.length() && Character.isWhitespace(declaration.charAt(i))) {
            i++;
        }
        if (i >= declaration.length()) {
            return null;
        }
        char quote = declaration.charAt(i);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        int endQuote = declaration.indexOf(quote, i + 1);
        if (endQuote < 0) {
            return null;
        }
        return declaration.substring(i + 1, endQuote);
    }

    /** Convenience for tests and callers that already hold text: the UTF-8 charset used throughout. */
    public static Charset charset() {
        return StandardCharsets.UTF_8;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (prefix.length == 0 || bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
