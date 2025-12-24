package com.tommytony.war.job;

import com.google.common.collect.ImmutableList;
import com.tommytony.war.War;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Job to batch and insert player stats (kills, deaths, flag captures) to H2 database.
 * This job processes stats asynchronously with batching for better performance.
 *
 * @author iangry0
 */
public class LogStatsJob extends BukkitRunnable {

	private final ImmutableList<StatsRecord> records;

	public LogStatsJob(final ImmutableList<StatsRecord> records) {
		this.records = records;
	}

	@Override
	/**
	 * Adds all records to the H2 database using batch processing.
	 * Updates existing records or inserts new ones using MERGE statements.
	 * This method is thread safe.
	 */
	public void run() {
		if (!War.war.getStatsConfig().isEnabled()) {
			return;
		}

		Connection conn = null;
		try {
			conn = War.war.getStatsConfig().getConnection();
			conn.setAutoCommit(false);

			// Prepare statements for batch processing
			PreparedStatement playerKillsStmt = conn.prepareStatement(
				"INSERT INTO war_player_kills (player_uuid, total_kills, total_deaths, flag_carrier_kills, total_captures, last_updated) " +
				"VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
				"ON DUPLICATE KEY UPDATE " +
				"total_kills = total_kills + VALUES(total_kills), " +
				"total_deaths = total_deaths + VALUES(total_deaths), " +
				"flag_carrier_kills = flag_carrier_kills + VALUES(flag_carrier_kills), " +
				"total_captures = total_captures + VALUES(total_captures), " +
				"last_updated = CURRENT_TIMESTAMP"
			);

			PreparedStatement warzoneKillsStmt = conn.prepareStatement(
				"INSERT INTO war_warzone_kills (player_uuid, warzone_name, kills, deaths, flag_carrier_kills, captures, last_updated) " +
				"VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
				"ON DUPLICATE KEY UPDATE " +
				"kills = kills + VALUES(kills), " +
				"deaths = deaths + VALUES(deaths), " +
				"flag_carrier_kills = flag_carrier_kills + VALUES(flag_carrier_kills), " +
				"captures = captures + VALUES(captures), " +
				"last_updated = CURRENT_TIMESTAMP"
			);

			// Process each record
			for (StatsRecord record : records) {
				String playerUuid = record.getPlayer().getUniqueId().toString();
				
				if (record.getType() == StatsType.KILL || record.getType() == StatsType.DEATH || record.getType() == StatsType.FLAG_CARRIER_KILL) {
					// Update total kills/deaths/flag carrier kills
					playerKillsStmt.setString(1, playerUuid);
					playerKillsStmt.setInt(2, record.getType() == StatsType.KILL || record.getType() == StatsType.FLAG_CARRIER_KILL ? 1 : 0);
					playerKillsStmt.setInt(3, record.getType() == StatsType.DEATH ? 1 : 0);
					playerKillsStmt.setInt(4, record.getType() == StatsType.FLAG_CARRIER_KILL ? 1 : 0);
					playerKillsStmt.setInt(5, 0); // captures
					playerKillsStmt.addBatch();

					// Update per-warzone kills/deaths/flag carrier kills
					if (record.getWarzoneName() != null && !record.getWarzoneName().isEmpty()) {
						warzoneKillsStmt.setString(1, playerUuid);
						warzoneKillsStmt.setString(2, record.getWarzoneName());
						warzoneKillsStmt.setInt(3, record.getType() == StatsType.KILL || record.getType() == StatsType.FLAG_CARRIER_KILL ? 1 : 0);
						warzoneKillsStmt.setInt(4, record.getType() == StatsType.DEATH ? 1 : 0);
						warzoneKillsStmt.setInt(5, record.getType() == StatsType.FLAG_CARRIER_KILL ? 1 : 0);
						warzoneKillsStmt.setInt(6, 0); // captures
						warzoneKillsStmt.addBatch();
					}
				} else if (record.getType() == StatsType.FLAG_CAPTURE) {
					// Update total captures
					playerKillsStmt.setString(1, playerUuid);
					playerKillsStmt.setInt(2, 0); // kills
					playerKillsStmt.setInt(3, 0); // deaths
					playerKillsStmt.setInt(4, 0); // flag_carrier_kills
					playerKillsStmt.setInt(5, 1); // captures
					playerKillsStmt.addBatch();
					
					// Update per-warzone captures
					if (record.getWarzoneName() != null && !record.getWarzoneName().isEmpty()) {
						warzoneKillsStmt.setString(1, playerUuid);
						warzoneKillsStmt.setString(2, record.getWarzoneName());
						warzoneKillsStmt.setInt(3, 0); // kills
						warzoneKillsStmt.setInt(4, 0); // deaths
						warzoneKillsStmt.setInt(5, 0); // flag_carrier_kills
						warzoneKillsStmt.setInt(6, 1); // captures
						warzoneKillsStmt.addBatch();
					}
				}
			}

			// Execute all batches
			playerKillsStmt.executeBatch();
			warzoneKillsStmt.executeBatch();
			
			conn.commit();

			// Close statements
			playerKillsStmt.close();
			warzoneKillsStmt.close();

			War.war.getLogger().log(Level.FINE, 
				"Inserted " + records.size() + " stats records into database");

		} catch (SQLException ex) {
			War.war.getLogger().log(Level.SEVERE,
				"Error inserting stats into database", ex);
			if (conn != null) {
				try {
					conn.rollback();
				} catch (SQLException rollbackEx) {
					War.war.getLogger().log(Level.SEVERE,
						"Error rolling back transaction", rollbackEx);
				}
			}
		} finally {
			if (conn != null) {
				try {
					conn.setAutoCommit(true);
					conn.close();
				} catch (SQLException ex) {
					War.war.getLogger().log(Level.WARNING,
						"Error closing connection", ex);
				}
			}
		}
	}

	/**
	 * Enum representing different types of stats that can be tracked.
	 */
	public enum StatsType {
		KILL,
		DEATH,
		FLAG_CAPTURE,
		FLAG_CARRIER_KILL
	}

	/**
	 * Record class for storing stats information before batch insertion.
	 */
	public static final class StatsRecord {
		private final OfflinePlayer player;
		private final StatsType type;
		private final String warzoneName;

		public StatsRecord(OfflinePlayer player, StatsType type, String warzoneName) {
			this.player = player;
			this.type = type;
			this.warzoneName = warzoneName;
		}

		public OfflinePlayer getPlayer() {
			return player;
		}

		public StatsType getType() {
			return type;
		}

		public String getWarzoneName() {
			return warzoneName;
		}
	}
}
