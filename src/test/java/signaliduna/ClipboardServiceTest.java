package signaliduna;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.HeadlessException;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testet das Fehlerverhalten der Zwischenablage. Surefire laeuft mit
 * {@code java.awt.headless=true}, damit hier und auf CI weder eine echte
 * Zwischenablage noech ein Bildschirm noetig ist.
 *
 * <p>Die alte Fassung fing {@code IllegalStateException} nicht ab - auf X11 mit
 * fremdem Clipboard-Besitzer flog die Exception auf dem EDT auf.
 */
class ClipboardServiceTest {

    @Test
    @DisplayName("read() meldet headless UNAVAILABLE statt zu werfen")
    void read_meldet_headless() {
        assertTrue(GraphicsEnvironment.isHeadless(), "Test setzt headless=true voraus");

        ClipboardService.Result result = ClipboardService.read();

        assertEquals(ClipboardService.Status.UNAVAILABLE, result.status());
        assertNull(result.text());
        assertNotNull(result.detail());
        assertTrue(result.detail().contains("grafische Umgebung"), result.detail());
    }

    @Test
    @DisplayName("write() wirft HeadlessException statt IllegalStateException")
    void write_wirft_headless_exception() {
        assertThrows(HeadlessException.class, () -> ClipboardService.write("select 1", null));
    }

    @Test
    @DisplayName("read() liefert niemals OK, wenn der Zustand nicht abfragbar ist")
    void read_liefert_keinen_text_ohne_clipboard() {
        ClipboardService.Result result = ClipboardService.read();

        assertTrue(result.status() != ClipboardService.Status.OK, "headless darf nie OK melden");
    }
}
