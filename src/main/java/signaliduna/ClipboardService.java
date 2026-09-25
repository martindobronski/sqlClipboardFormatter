package signaliduna;

import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/**
 * Kapselt die Zwischenablage. Die Aufrufe blockieren je nach Plattform
 * (X11, entfernte X-Sessions) und gehören daher nicht auf den EDT.
 */
final class ClipboardService {

    enum Status {
        /** Text gelesen. */
        OK,
        /** Zwischenablage enthält keinen Text. */
        NO_TEXT,
        /** Zwischenablage nicht erreichbar (headless, fremder Besitzer). */
        UNAVAILABLE,
        /** Leeres Feld. */
        EMPTY
    }

    record Result(Status status, String text, String detail) {
    }

    private ClipboardService() {
    }

    /**
     * Liest Text aus der Zwischenablage. Wirft nie, sondern meldet den Grund
     * im Ergebnis - inklusive {@code IllegalStateException}, die bei fremdem
     * Clipboard-Besitz auftreten kann und die alte Version ungefährlich
     * auf dem EDT landen ließ.
     */
    static Result read() {
        if (GraphicsEnvironment.isHeadless()) {
            return new Result(Status.UNAVAILABLE, null, "Keine grafische Umgebung verfügbar.");
        }
        try {
            Transferable contents = clipboard().getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return new Result(Status.NO_TEXT, null, "Die Zwischenablage enthält keinen lesbaren Text.");
            }
            String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
            if (text == null || text.isBlank()) {
                return new Result(Status.EMPTY, null, "Die Zwischenablage ist leer.");
            }
            return new Result(Status.OK, text, null);
        } catch (UnsupportedFlavorException | IOException | IllegalStateException | HeadlessException ex) {
            return new Result(Status.UNAVAILABLE, null, describe(ex));
        } catch (RuntimeException ex) {
            return new Result(Status.UNAVAILABLE, null, describe(ex));
        }
    }

    /**
     * Schreibt Text in die Zwischenablage.
     *
     * @param owner Besitzer für {@link Clipboard#lostOwnership}; {@code null} ist
     *              zulässig, bedeutet aber Plattform-abhängigen Verlust der
     *              Besitz-Information
     * @throws HeadlessException wenn keine grafische Umgebung verfügbar ist
     * @throws IllegalStateException wenn die Zwischenablage gerade gesperrt ist
     */
    static void write(String text, ClipboardOwner owner) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new HeadlessException();
        }
        clipboard().setContents(new StringSelection(text), owner);
    }

    private static Clipboard clipboard() {
        return Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return ex.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
