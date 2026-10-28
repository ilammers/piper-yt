package io.github.piper_yt.piperyt.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * The local library: one SQLite file holding favorites, the feed cache, playlists, history,
 * downloads, recommendation caches and settings. Nothing in it ever leaves the machine except
 * through an export the user asks for.
 *
 * <p>One connection, used by one thread at a time ({@link #LOCK}). Every statement is short, so
 * the main thread may run small reads directly; writes and anything that loops go through
 * {@code Ui.async} or a background worker.
 */
public final class Db {
    /** Guards the connection; hold it across statements that must see each other's effects. */
    static final Object LOCK = new Object();

    private static Connection connection;
    private static Path file;

    private Db() {
    }

    /** Reads one row of a result. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Work done inside a transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T run() throws SQLException;
    }

    /** Where the library lives: {@code $PIPER_DATA_DIR}, else the XDG data dir. */
    public static Path dataDir() {
        final String override = System.getenv("PIPER_DATA_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        final String xdg = System.getenv("XDG_DATA_HOME");
        final Path base = xdg != null && !xdg.isBlank()
                ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".local", "share");
        // Not ~/.local/share/piper-yt: install.sh replaces that directory wholesale
        return base.resolve("io.github.piper_yt.PiperYT");
    }

    /** Opens (creating and migrating if needed) the library in {@link #dataDir()}. */
    public static void open() throws IOException, SQLException {
        open(dataDir().resolve("library.db"));
    }

    /** Opens the library at {@code path}, closing any open one. Tests use a temp file. */
    public static void open(final Path path) throws IOException, SQLException {
        synchronized (LOCK) {
            close();
            Files.createDirectories(path.toAbsolutePath().getParent());
            final Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA busy_timeout = 5000");
                // Deleted rows (history!) are overwritten, not left in free pages
                st.execute("PRAGMA secure_delete = ON");
            }
            Migrations.migrate(c, Migrations.ALL);
            connection = c;
            file = path;
        }
    }

    public static void close() {
        synchronized (LOCK) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (final SQLException e) {
                    System.err.println("Closing the library: " + e.getMessage());
                }
                connection = null;
                file = null;
            }
        }
    }

    public static Path file() {
        return file;
    }

    public static boolean isOpen() {
        synchronized (LOCK) {
            return connection != null;
        }
    }

    static Connection connection() {
        if (connection == null) {
            throw new IllegalStateException("The library is not open");
        }
        return connection;
    }

    /** Runs {@code work} in one transaction, rolling back if it throws. */
    public static <T> T tx(final Work<T> work) {
        synchronized (LOCK) {
            final Connection c = connection();
            try {
                final boolean outer = c.getAutoCommit();
                if (!outer) {
                    // Already inside a transaction: join it
                    return work.run();
                }
                c.setAutoCommit(false);
                try {
                    final T result = work.run();
                    c.commit();
                    return result;
                } catch (final SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            } catch (final SQLException e) {
                throw new DbException(e);
            }
        }
    }

    /** Runs an INSERT/UPDATE/DELETE and returns the changed row count. */
    public static int update(final String sql, final Object... args) {
        synchronized (LOCK) {
            try (PreparedStatement st = prepare(sql, args)) {
                return st.executeUpdate();
            } catch (final SQLException e) {
                throw new DbException(sql, e);
            }
        }
    }

    /** Runs an INSERT and returns the new row's id. */
    public static long insert(final String sql, final Object... args) {
        synchronized (LOCK) {
            try (PreparedStatement st = prepare(sql, args)) {
                st.executeUpdate();
                try (Statement idSt = connection().createStatement();
                     ResultSet rs = idSt.executeQuery("SELECT last_insert_rowid()")) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            } catch (final SQLException e) {
                throw new DbException(sql, e);
            }
        }
    }

    public static <T> List<T> query(final String sql, final RowMapper<T> mapper,
                                    final Object... args) {
        synchronized (LOCK) {
            try (PreparedStatement st = prepare(sql, args); ResultSet rs = st.executeQuery()) {
                final List<T> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapper.map(rs));
                }
                return result;
            } catch (final SQLException e) {
                throw new DbException(sql, e);
            }
        }
    }

    /** The first row, or null. */
    public static <T> T one(final String sql, final RowMapper<T> mapper, final Object... args) {
        synchronized (LOCK) {
            try (PreparedStatement st = prepare(sql, args); ResultSet rs = st.executeQuery()) {
                return rs.next() ? mapper.map(rs) : null;
            } catch (final SQLException e) {
                throw new DbException(sql, e);
            }
        }
    }

    public static long count(final String sql, final Object... args) {
        final Long n = one(sql, rs -> rs.getLong(1), args);
        return n == null ? 0 : n;
    }

    private static PreparedStatement prepare(final String sql, final Object... args)
            throws SQLException {
        final PreparedStatement st = connection().prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            final Object arg = args[i];
            if (arg == null) {
                st.setNull(i + 1, Types.NULL);
            } else if (arg instanceof Boolean b) {
                st.setInt(i + 1, b ? 1 : 0);
            } else if (arg instanceof Enum<?> e) {
                st.setString(i + 1, e.name());
            } else {
                st.setObject(i + 1, arg);
            }
        }
        return st;
    }

    /** A nullable long column. */
    public static Long getLong(final ResultSet rs, final String column) throws SQLException {
        final long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** Thrown for SQL errors, which mean a bug or a broken file rather than user error. */
    public static final class DbException extends RuntimeException {
        DbException(final SQLException cause) {
            super(cause.getMessage(), cause);
        }

        DbException(final String sql, final SQLException cause) {
            super(cause.getMessage() + " [" + sql.strip().split("\n")[0] + "]", cause);
        }
    }
}
