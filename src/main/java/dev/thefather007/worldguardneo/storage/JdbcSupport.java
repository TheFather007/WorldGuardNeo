package dev.thefather007.worldguardneo.storage;

import dev.thefather007.worldguardneo.WorldGuardNeo;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Classloader-robust JDBC bootstrapping shared by the SQL storage backends.
 *
 * <p>In a modded environment the driver jar is often loaded by a different classloader than us, and
 * {@link java.sql.DriverManager} only sees drivers registered by the caller's own classloader — so
 * it throws "No suitable driver found" even when {@code Class.forName} locates the class. We sidestep
 * DriverManager: find the {@link Driver} class (caller + thread-context loaders), instantiate it, and
 * call {@link Driver#connect} directly, which doesn't care which classloader loaded the driver.
 */
final class JdbcSupport {

    private JdbcSupport() {}

    /** Locate a driver class by name, trying the current then thread-context classloader. Null if none resolve. */
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
     * Open a connection via a {@link Driver} instance built from {@code driverClass}, bypassing
     * {@link java.sql.DriverManager}. {@code props} may carry user/password/options (or be empty).
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
