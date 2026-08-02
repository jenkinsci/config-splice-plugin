package io.jenkins.plugins.configsplice.engine;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable in-memory view of one configuration file: its original bytes, its BOM state, and its
 * decoded text.
 *
 * <p>This class owns the <em>only</em> byte/character boundary in the engine. Locators work purely
 * in {@code char} offsets against {@link #text()}; {@link #render(String)} turns spliced text back
 * into bytes with the original BOM restored.
 *
 * <p>Decoding is strict on purpose. The default {@code new String(bytes, UTF_8)} silently replaces
 * malformed sequences with U+FFFD, which would mean writing back a file whose untouched bytes had
 * changed — a direct violation of the exact-preservation guarantee (SRS section 10.4) and a
 * plausible way to corrupt a config file the plugin was only supposed to read.
 */
public final class SourceDocument {

    private final byte[] originalBytes;
    private final EncodingSupport.Bom bom;
    private final String text;

    private SourceDocument(byte[] originalBytes, EncodingSupport.Bom bom, String text) {
        this.originalBytes = originalBytes;
        this.bom = bom;
        this.text = text;
    }

    /**
     * Detects the encoding, admits only UTF-8, and decodes strictly.
     *
     * @throws SpliceException {@link ErrorCode#UNSUPPORTED_ENCODING} for a non-UTF-8 BOM or
     *                         malformed UTF-8
     */
    public static SourceDocument of(byte[] bytes) throws SpliceException {
        Objects.requireNonNull(bytes, "bytes");
        EncodingSupport.Bom bom = EncodingSupport.detectBom(bytes);
        EncodingSupport.requireSupportedBom(bom);

        byte[] body = Arrays.copyOfRange(bytes, bom.length(), bytes.length);
        String text = decodeStrictUtf8(body);
        return new SourceDocument(bytes.clone(), bom, text);
    }

    /** The decoded document text, excluding any BOM. */
    public String text() {
        return text;
    }

    public EncodingSupport.Bom bom() {
        return bom;
    }

    /** The original file bytes exactly as read, including any BOM. */
    public byte[] originalBytes() {
        return originalBytes.clone();
    }

    /**
     * Re-encodes {@code newText} to bytes, restoring the original BOM.
     *
     * <p>Rendering the unmodified {@link #text()} must reproduce {@link #originalBytes()} exactly;
     * {@link #rendersIdentically()} asserts that round-trip and is exercised by every fixture.
     */
    public byte[] render(String newText) {
        Objects.requireNonNull(newText, "newText");
        byte[] body = newText.getBytes(EncodingSupport.charset());
        if (bom.length() == 0) {
            return body;
        }
        byte[] out = new byte[bom.length() + body.length];
        System.arraycopy(bom.bytes(), 0, out, 0, bom.length());
        System.arraycopy(body, 0, out, bom.length(), body.length);
        return out;
    }

    /**
     * True when decoding and re-encoding the document is byte-exact.
     *
     * <p>Strict decoding should make this unconditionally true for every document we accept; it is
     * asserted rather than assumed because it underpins the exact-preservation oracle.
     */
    public boolean rendersIdentically() {
        return Arrays.equals(originalBytes, render(text));
    }

    private static String decodeStrictUtf8(byte[] body) throws SpliceException {
        CharsetDecoder decoder = EncodingSupport.charset()
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(body));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            // Deliberately does not include the offending bytes: they may be part of a secret.
            throw new SpliceException(
                    ErrorCode.UNSUPPORTED_ENCODING, "file is not valid UTF-8", e);
        }
    }
}
