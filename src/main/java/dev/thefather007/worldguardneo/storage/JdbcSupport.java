package dev.thefather007.worldguardneo.storage;

import dev.thefather007.worldguardneo.WorldGuardNeo;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Classloader-robust JDBC bootstrapping shared by the SQL storage backends.
 *
 * <p><b>Why this exists.</b> In a modded environment the JDBC driver jar is very often loaded by
 * a different classloader than WorldGuardNeo (a sibling mod's dependency loader, a libraries
 * directory, LuckPerms' isolated loader, …). {@link java.sql.DriverManager#getConnection} only
 * considers drivers registered by the <i>caller's own</i> classloader (and its ancestors), so it
 * throws {@code "No suitable driver found"} even though {@code Class.forName} located the class.
 * This was the cause of "H2 init failed → falling back to JSON" on servers that clearly had H2
 * present. We sidestep DriverManager entirely: locate the {@link Driver} class (trying both the
 * caller and the thread-context classloaders), instantiate it, and call {@link Driver#connect}
 * directly — that path doesn't care which classloader loaded the driver.
 */
final class JdbcSupport {

    private JdbcSupport() {}

    /**
     * Locate a driver class by trying each candidate name against the current classloader and
     * then the thread-context classloader. Returns the loaded {@link Class}, or null if none of
     * the candidates resolve anywhere.
     */
    static Class<?> findDriverClass(String... candidates) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        for (String name : candidates) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) { /* try context loader next */ }
            if (ctx != null) {
                try {
                    return Class.forName(name, true, ctx);
                } catch (ClassNotFoundException ignored) { /* try next candidate */ }
            }
        }
        return null;
    }

    /**
     * Open a connection through a {@link Driver} instance built from {@code driverClass},
     * bypassing {@link java.sql.DriverManager}. {@code props} may carry user/password/options;
     * pass an empty {@link Properties} for none.
     *
     * @throws SQLException if instantiation fails or the driver rejects the URL
     */
    static Connection connect(Class<?> driverClass, String url, Properties props) throws SQLException {
        try {
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            Connection c = driver.connect(url, props == null ? new Properties() : props);
            if (c == null) {
                // Driver#connect returns null when the URL isn't one it handles — surface a clear error.
                throw new SQLException("Driver " + driverClass.getName()
                        + " did not accept the JDBC URL (returned null): " + url);
            }
            return c;
        } catch (SQLException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Could not instantiate JDBC driver " + driverClass.getName(), e);
        }
    }

    /** Best-effort debug log of the driver actually selected, so admins can confirm the backend. */
    static void logDriver(String backend, Class<?> driverClass) {
        WorldGuardNeo.LOGGER.info("[WorldGuardNeo] {} storage using driver {} (loaded by {}).",
                backend, driverClass.getName(),
                driverClass.getClassLoader() == null ? "bootstrap" : driverClass.getClassLoader());
    }
}
