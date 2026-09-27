package signaliduna;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
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
        setMinimumSize(new Dimension(620, 420));
        setLocationRelativeTo(null);
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("SQL Clipboard Formatter benötigt eine grafische Umgebung.");
            return;
        }
        // Muss vor dem Erzeugen des Panels passieren, sonst bekommen die
        // Komponenten noch das System-LookAndFeel.
        FormatterPanel.setzeTheme(FormatterPanel.STANDARD);
        SwingUtilities.invokeLater(() -> new SqlClipboardFormatter().setVisible(true));
    }
}
