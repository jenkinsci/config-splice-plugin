package io.jenkins.plugins.configsplice.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Encoding admission control (SRS section 10.3): accept UTF-8, reject everything else cleanly. */
class EncodingSupportTest {

    @Test
    void utf8WithoutBomIsAccepted() throws Exception {
        SourceDocument document = SourceDocument.of("{\"A\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(EncodingSupport.Bom.NONE, document.bom());
        assertTrue(document.rendersIdentically());
    }

    @Test
    void utf8WithBomIsAcceptedAndTheBomIsNotPartOfTheText() throws Exception {
        byte[] bytes = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "{\"A\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        SourceDocument document = SourceDocument.of(bytes);

        assertEquals(EncodingSupport.Bom.UTF_8, document.bom());
        assertEquals('{', document.text().charAt(0), "the BOM must not appear in the decoded text");
        assertTrue(document.rendersIdentically());
        assertArrayEquals(bytes, document.render(document.text()));
    }

    @Test
    void nonAsciiContentSurvivesTheRoundTrip() throws Exception {
        SourceDocument document =
                SourceDocument.of("{\"City\":\"São Paulo\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(document.rendersIdentically());
    }

    @Test
    void utf16IsRejected() {
        byte[] utf16 = "{\"A\":\"x\"}".getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = concat(new byte[] {(byte) 0xFF, (byte) 0xFE}, utf16);

        SpliceException thrown = assertThrows(SpliceException.class, () -> SourceDocument.of(withBom));
        assertEquals(ErrorCode.UNSUPPORTED_ENCODING, thrown.code());
    }

    @Test
    @DisplayName("UTF-32LE is not misread as UTF-16LE")
    void utf32IsDistinguishedFromUtf16() {
        byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x00, 0x7B};
        assertEquals(EncodingSupport.Bom.UTF_32LE, EncodingSupport.detectBom(bytes));
    }

    @Test
    void malformedUtf8IsRejectedRatherThanSilentlyReplaced() {
        // 0xC3 starts a two-byte sequence that never completes. new String(...) would turn this into
        // U+FFFD and we would write back a file whose untouched bytes had changed.
        byte[] bytes = new byte[] {'{', '"', 'A', '"', ':', '"', (byte) 0xC3, '"', '}'};

        SpliceException thrown = assertThrows(SpliceException.class, () -> SourceDocument.of(bytes));
        assertEquals(ErrorCode.UNSUPPORTED_ENCODING, thrown.code());
    }

    @Test
    void xmlDeclarationsNamingUtf8AreAcceptedInAnyCaseOrQuoteStyle() throws Exception {
        EncodingSupport.requireSupportedXmlDeclaration("<?xml version=\"1.0\" encoding=\"utf-8\"?><a/>");
        EncodingSupport.requireSupportedXmlDeclaration("<?xml version=\"1.0\" encoding=\"UTF-8\"?><a/>");
        EncodingSupport.requireSupportedXmlDeclaration("<?xml version='1.0' encoding='Utf-8'?><a/>");
        EncodingSupport.requireSupportedXmlDeclaration("<?xml version=\"1.0\"?><a/>");
        EncodingSupport.requireSupportedXmlDeclaration("<configuration/>");
    }

    @Test
    void legacyXmlDeclarationsAreRejectedEvenWhenTheBytesAreAscii() {
        for (String encoding : new String[] {"us-ascii", "iso-8859-1", "windows-1252", "utf-16"}) {
            String xml = "<?xml version=\"1.0\" encoding=\"" + encoding + "\"?><configuration/>";
            SpliceException thrown = assertThrows(
                    SpliceException.class,
                    () -> EncodingSupport.requireSupportedXmlDeclaration(xml),
                    encoding + " must be rejected");
            assertEquals(ErrorCode.UNSUPPORTED_ENCODING, thrown.code());
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
