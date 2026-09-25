package signaliduna;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Professioneller Java SQL-Formatter für die Zwischenablage.
 * Entfernt doppelte Leerzeichen im Code, validiert SQL-Inhalt und schützt das Clipboard.
 */
public class SqlClipboardFormatter extends JFrame {

    private final JTextArea sqlArea;
    private final JLabel statusLabel;
    private final JButton btnFormat;
    private final JButton btnWrite;

    public SqlClipboardFormatter() {
        setTitle("SQL Clipboard Formatter (Strict Protection)");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        mainPanel.setBackground(new Color(245, 245, 245));

        JLabel titleLabel = new JLabel("SQL Formatter (Best Practice Edition)", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(new Color(33, 33, 33));
        mainPanel.add(titleLabel, BorderLayout.NORTH);

        sqlArea = new JTextArea();
        sqlArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        sqlArea.setLineWrap(true);
        sqlArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(sqlArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("SQL Code"));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 3, 10, 0));
        buttonPanel.setOpaque(false);

        JButton btnRead = createStyledButton("Clipboard einlesen", new Color(70, 130, 180));
        btnFormat = createStyledButton("SQL Formatieren", new Color(46, 139, 87));
        btnWrite = createStyledButton("Ins Clipboard schreiben", new Color(220, 100, 50));

        btnFormat.setEnabled(false);
        btnWrite.setEnabled(false);

        buttonPanel.add(btnRead);
        buttonPanel.add(btnFormat);
        buttonPanel.add(btnWrite);

        JPanel footerPanel = new JPanel(new BorderLayout(10, 5));
        footerPanel.setOpaque(false);
        footerPanel.add(buttonPanel, BorderLayout.NORTH);

        JPanel infoLinePanel = new JPanel(new BorderLayout());
        infoLinePanel.setOpaque(false);
        infoLinePanel.setBorder(new EmptyBorder(5, 0, 0, 0));

        statusLabel = new JLabel("Bereit - Bitte SQL aus der Zwischenablage laden.");
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        statusLabel.setForeground(new Color(100, 100, 100));
        infoLinePanel.add(statusLabel, BorderLayout.WEST);

        JLabel versionLabel = new JLabel("Version 0.1 vom 23.09.2026");
        versionLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        versionLabel.setForeground(new Color(120, 120, 120));
        infoLinePanel.add(versionLabel, BorderLayout.EAST);

        footerPanel.add(infoLinePanel, BorderLayout.SOUTH);
        mainPanel.add(footerPanel, BorderLayout.SOUTH);
        add(mainPanel);

        btnRead.addActionListener(e -> readFromClipboard());
        btnFormat.addActionListener(e -> formatSqlText());
        btnWrite.addActionListener(e -> writeToClipboard());
    }

    private JButton createStyledButton(String text, Color baseColor) {
        JButton button = new JButton(text) {
            @Override
            public void setEnabled(boolean b) {
                super.setEnabled(b);
                if (b) {
                    setBackground(baseColor);
                } else {
                    setBackground(Color.LIGHT_GRAY);
                }
            }
        };
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setForeground(Color.WHITE);
        button.setBackground(baseColor);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                if (button.isEnabled()) button.setBackground(baseColor.brighter());
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                if (button.isEnabled()) button.setBackground(baseColor);
            }
        });
        return button;
    }

    private boolean isProbablySql(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        String testText = text.trim().toUpperCase();

        Pattern mainKeywords = Pattern.compile("\\b(SELECT|UPDATE|INSERT|DELETE|CREATE|DROP|ALTER|MERGE|TRUNCATE)\\b");
        Pattern supportKeywords = Pattern.compile("\\b(FROM|WHERE|JOIN|ORDER\\s+BY|GROUP\\s+BY|SET)\\b");
        Pattern commentPattern = Pattern.compile("^--.*", Pattern.MULTILINE);

        boolean hasMain = mainKeywords.matcher(testText).find();
        boolean hasSupport = supportKeywords.matcher(testText).find();
        boolean hasComments = commentPattern.matcher(testText).find();

        return (hasMain && hasSupport) || (hasMain && testText.contains("*")) || hasComments;
    }

    private void readFromClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable contents = clipboard.getContents(null);

            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String clipboardText = (String) contents.getTransferData(DataFlavor.stringFlavor);
                sqlArea.setText(clipboardText);

                if (isProbablySql(clipboardText)) {
                    btnFormat.setEnabled(true);
                    btnWrite.setEnabled(true);
                    statusLabel.setForeground(new Color(0, 128, 0));
                    statusLabel.setText("✓ Valides SQL erkannt. Formatierung und Schreiben freigeschaltet.");
                } else {
                    btnFormat.setEnabled(false);
                    btnWrite.setEnabled(false);
                    statusLabel.setForeground(Color.RED);
                    statusLabel.setText("⚠ Kein gültiges SQL in der Zwischenablage erkannt! Aktionen blockiert.");
                }
            } else {
                btnFormat.setEnabled(false);
                btnWrite.setEnabled(false);
                statusLabel.setForeground(Color.RED);
                statusLabel.setText("⚠ Die Zwischenablage enthält keinen lesbaren Text.");
            }
        } catch (UnsupportedFlavorException | IOException ex) {
            btnFormat.setEnabled(false);
            btnWrite.setEnabled(false);
            statusLabel.setForeground(Color.RED);
            statusLabel.setText("❌ Fehler beim Lesen der Zwischenablage: " + ex.getMessage());
        }
    }

    private void writeToClipboard() {
        String text = sqlArea.getText().trim();
        if (text.isEmpty()) {
            statusLabel.setForeground(Color.RED);
            statusLabel.setText("⚠ Das Textfeld ist leer. Nichts zu kopieren.");
            return;
        }

        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        StringSelection selection = new StringSelection(text);
        clipboard.setContents(selection, null);
        statusLabel.setForeground(new Color(0, 128, 0));
        statusLabel.setText("✓ SQL erfolgreich in die Zwischenablage kopiert!");
    }

    private void formatSqlText() {
        String rawText = sqlArea.getText();
        if (rawText.trim().isEmpty()) {
            return;
        }

        String[] inputLines = rawText.split("\r?\n", -1);
        StringBuilder finalOutput = new StringBuilder();

        boolean lastLineWasEmpty = false;

        for (String line : inputLines) {
            String trimmedLine = line.trim();

            if (trimmedLine.isEmpty()) {
                if (!lastLineWasEmpty) {
                    finalOutput.append("\n");
                    lastLineWasEmpty = true;
                }
            } else {
                lastLineWasEmpty = false;
                if (trimmedLine.startsWith("--")) {
                    finalOutput.append(formatCommentLine(trimmedLine)).append("\n");
                } else {
                    finalOutput.append(formatSqlLine(trimmedLine)).append("\n");
                }
            }
        }

        sqlArea.setText(finalOutput.toString().trim());
        statusLabel.setForeground(new Color(0, 128, 0));
        statusLabel.setText("✓ SQL-Text erfolgreich nach Best-Practice-Regeln formatiert.");
    }

    /**
     * Formatiert eine normale SQL-Zeile:
     * Bereinigt alle überflüssigen, mehrfachen Leerzeichen, schreibt Keywords groß und rückt ein.
     */
    private String formatSqlLine(String line) {
        // Reduziert mehrfache Leerzeichen mitten in der Zeile auf exakt ein Leerzeichen
        String formatted = line.replaceAll("\\s+", " ").trim();

        // Keywords in der Zeile großschreiben
        formatted = uppercaseKeywords(formatted);

        // Einrückungs-Präfix bestimmen
        String prefix = "";
        String upper = formatted.toUpperCase();
        if (upper.startsWith("AND ") || upper.startsWith("OR ") || upper.startsWith("SET ")) {
            prefix = "    "; // 4 Leerzeichen Einrückung
        }

        return prefix + formatted;
    }

    private String formatCommentLine(String line) {
        String commentContent = line.substring(2).trim();

        if (commentContent.isEmpty()) {
            return "--";
        }

        // Mehrfache Leerzeichen im Kommentar ebenfalls bereinigen
        commentContent = commentContent.replaceAll("\\s+", " ");
        commentContent = uppercaseKeywords(commentContent);
        String upperContent = commentContent.toUpperCase();

        if (upperContent.startsWith("AND ") || upperContent.startsWith("OR ") || upperContent.startsWith("SET ")) {
            return "--     " + commentContent;
        } else if (upperContent.startsWith("WHERE ") || upperContent.startsWith("SELECT ") || upperContent.startsWith("FROM ") || upperContent.startsWith("ORDER BY ")) {
            return "-- " + commentContent;
        }

        return "-- " + commentContent;
    }

    private String uppercaseKeywords(String text) {
        String[] keywords = {
                "select", "from", "where", "and", "or", "order by", "group by", "having",
                "join", "inner join", "left join", "right join", "update", "set", "insert into",
                "values", "delete from", "like", "desc", "asc"
        };

        String result = text;
        for (String keyword : keywords) {
            Pattern pattern = Pattern.compile("\\b" + keyword + "\\b", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(result);

            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(sb, keyword.toUpperCase());
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            new SqlClipboardFormatter().setVisible(true);
        });
    }
}


