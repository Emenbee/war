package com.tommytony.war.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.logging.Level;
import org.apache.commons.lang.Validate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import com.tommytony.war.War;

/**
 * Storage class for H2 stats database configuration settings.
 * Manages embedded H2 database for tracking player kills and flag captures.
 *
 * @author iangry0
 */
public class StatsConfig {

	private ConfigurationSection section;
	private String databasePath;
	private Connection connection;

	/**
	 * Load the values from the specified section into the stats config.
	 *
	 * @param section Section to load stats settings from.
	 */
	public StatsConfig(ConfigurationSection section) {
		this.section = section;
		this.databasePath = section.getString("database.path", "plugins/War/stats");
	}

	/**
	 * Create a new stats configuration section with default values.
	 */
	public StatsConfig() {
		this(new MemoryConfiguration());
		section.set("enabled", true);
		section.set("database.path", "plugins/War/stats");
		section.set("batch.size", 100);
		section.set("batch.interval", 300); // 5 minutes
	}

	/**
	 * Check if stats tracking is enabled.
	 *
	 * @return true if stats tracking is enabled, false otherwise.
	 */
	public boolean isEnabled() {
		return section.getBoolean("enabled", true);
	}

	/**
	 * Get the batch size for stats logging.
	 *
	 * @return batch size
	 */
	public int getBatchSize() {
		return section.getInt("batch.size", 100);
	}

	/**
	 * Get the batch interval in seconds.
	 *
	 * @return batch interval in seconds
	 */
	public int getBatchInterval() {
		return section.getInt("batch.interval", 300);
	}

	/**
	 * Get the database path.
	 *
	 * @return database path
	 */
	public String getDatabasePath() {
		return databasePath;
	}

	private String getJDBCUrl() {
		return "jdbc:h2:" + databasePath + ";AUTO_SERVER=TRUE;MODE=MySQL";
	}

	/**
	 * Initialize the H2 database and create tables if they don't exist.
	 *
	 * @throws SQLException Error occurred connecting to database or creating tables.
	 */
	public void initialize() throws SQLException {
		if (!isEnabled()) {
			return;
		}
		
		Connection conn = null;
		try {
			conn = getConnection();
			Statement stmt = conn.createStatement();
			
			// Table for total player kills across all warzones
			stmt.executeUpdate(
				"CREATE TABLE IF NOT EXISTS war_player_kills (" +
				"player_uuid VARCHAR(36) PRIMARY KEY, " +
				"total_kills INT NOT NULL DEFAULT 0, " +
				"total_deaths INT NOT NULL DEFAULT 0, " +
				"flag_carrier_kills INT NOT NULL DEFAULT 0, " +
				"total_captures INT NOT NULL DEFAULT 0, " +
				"last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"
			);
			
			// Table for per-warzone kills
			stmt.executeUpdate(
				"CREATE TABLE IF NOT EXISTS war_warzone_kills (" +
				"player_uuid VARCHAR(36) NOT NULL, " +
				"warzone_name VARCHAR(255) NOT NULL, " +
				"kills INT NOT NULL DEFAULT 0, " +
				"deaths INT NOT NULL DEFAULT 0, " +
				"flag_carrier_kills INT NOT NULL DEFAULT 0, " +
				"captures INT NOT NULL DEFAULT 0, " +
				"last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
				"PRIMARY KEY (player_uuid, warzone_name))"
			);
			
			// Create indexes for better query performance
			stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_warzone_kills_player ON war_warzone_kills(player_uuid)");
			stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_warzone_kills_zone ON war_warzone_kills(warzone_name)");
			
			stmt.close();
			War.war.getLogger().info("Stats database initialized successfully at: " + databasePath);
		} catch (SQLException ex) {
			War.war.getLogger().log(Level.SEVERE, "Failed to initialize stats database", ex);
			throw ex;
		} finally {
			if (conn != null && conn != connection) {
				conn.close();
			}
		}
	}

	/**
	 * Get a connection to the H2 database represented by this configuration.
	 * Creates a new connection each time since JDBC connections are not thread-safe.
	 *
	 * @return connection to H2 database.
	 * @throws SQLException Error occurred connecting to database.
	 * @throws IllegalArgumentException Stats tracking is not enabled.
	 */
	public Connection getConnection() throws SQLException {
		Validate.isTrue(this.isEnabled(), "Stats tracking is not enabled");
		return DriverManager.getConnection(this.getJDBCUrl());
	}

	/**
	 * Close the database connection.
	 * Note: Each caller should close their own connection when done.
	 */
	public synchronized void close() {
		// This is for shutdown cleanup only
		if (connection != null) {
			try {
				connection.close();
			} catch (SQLException ex) {
				War.war.getLogger().log(Level.WARNING, "Error closing stats database connection", ex);
			}
			connection = null;
		}
	}

	/**
	 * Copy represented configuration into another configuration section.
	 *
	 * @param section Mutable section to write values in.
	 */
	public void saveTo(ConfigurationSection section) {
		Map<String, Object> values = this.section.getValues(true);
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			section.set(entry.getKey(), entry.getValue());
		}
	}
}
