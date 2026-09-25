package signaliduna;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;

/**
 * Fensterhülle für den {@link FormatterPanel}. Enthält bewusst keine
 * Fachlogik mehr.
 */
public final class SqlClipboardFormatter extends JFrame {

    private static final long serialVersionUID = 1L;

    public SqlClipboardFormatter() {
        super("SQL Clipboard Formatter");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setContentPane(new FormatterPanel());
        pack();
        setMinimumSize(new Dimension(560, 380));
        setLocationRelativeTo(null);
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("SQL Clipboard Formatter benötigt eine grafische Umgebung.");
            return;
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) {
            // Auf den Standard-LookAndFeel zurückfallen.
        }
        SwingUtilities.invokeLater(() -> new SqlClipboardFormatter().setVisible(true));
    }
}
