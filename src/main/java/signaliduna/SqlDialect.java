package signaliduna;

import com.github.vertical_blank.sqlformatter.languages.Dialect;

/**
 * SQL-Dialekte, die das Tool formatieren kann.
 *
 * <p>Die Auswahl ist keine Kosmetik: sql-formatter zerschlitzt
 * PostgreSQL-Dollar-Quoting ({@code $$ … $$}) in allen Dialekten ausser
 * PostgreSQL. Wer also eine Funktion mit {@code $$}-Body formatieren laesst,
 * bekommt sonst syntaktisch kaputtes SQL zurueck.
 */
enum SqlDialect {

    STANDARD("Standard SQL", "Standard", Dialect.StandardSql),
    POSTGRESQL("PostgreSQL", "PostgreSQL", Dialect.PostgreSql),
    MYSQL("MySQL / MariaDB", "MySQL", Dialect.MySql),
    TSQL("SQL Server (T-SQL)", "T-SQL", Dialect.TSql),
    PLSQL("Oracle PL/SQL", "PL/SQL", Dialect.PlSql),
    DB2("IBM DB2", "DB2", Dialect.Db2);

    private final String label;
    private final String shortLabel;
    private final Dialect libraryDialect;

    SqlDialect(String label, String shortLabel, Dialect libraryDialect) {
        this.label = label;
        this.shortLabel = shortLabel;
        this.libraryDialect = libraryDialect;
    }

    String label() {
        return label;
    }

    /** Kompakte Form fuer die Anzeige neben der Auswahlbox. */
    String shortLabel() {
        return shortLabel;
    }

    Dialect libraryDialect() {
        return libraryDialect;
    }

    @Override
    public String toString() {
        return label;
    }
}
