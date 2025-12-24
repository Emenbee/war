package com.tommytony.war.utility;

import com.tommytony.war.War;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.OfflinePlayer;

/**
 * Utility class for querying player statistics from the H2 database.
 * Provides methods to retrieve kill/death counts and flag captures.
 *
 * @author iangry0
 */
public class StatsQueryUtil {

	/**
	 * Entry for leaderboard results containing player UUID and count.
	 */
	public static class LeaderboardEntry {
		private final UUID playerUuid;
		private final int count;

		public LeaderboardEntry(UUID playerUuid, int count) {
			this.playerUuid = playerUuid;
			this.count = count;
		}

		public UUID getPlayerUuid() {
			return playerUuid;
		}

		public int getCount() {
			return count;
		}
	}

	/**
	 * Get total kills and deaths for a player across all warzones.
	 * 
	 * @param player The player to query
	 * @return Map with keys "kills", "deaths", and "flag_carrier_kills", or empty map if no data
	 */
	public static Map<String, Integer> getTotalKillsDeaths(OfflinePlayer player) {
		Map<String, Integer> result = new HashMap<>();
		result.put("kills", 0);
		result.put("deaths", 0);
		result.put("flag_carrier_kills", 0);
		result.put("captures", 0);

		if (!War.war.getStatsConfig().isEnabled()) {
			return result;
		}

		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;

		try {
			conn = War.war.getStatsConfig().getConnection();
			stmt = conn.prepareStatement(
				"SELECT total_kills, total_deaths, flag_carrier_kills, total_captures FROM war_player_kills WHERE player_uuid = ?"
			);
			stmt.setString(1, player.getUniqueId().toString());
			rs = stmt.executeQuery();

			if (rs.next()) {
				result.put("kills", rs.getInt("total_kills"));
				result.put("deaths", rs.getInt("total_deaths"));
				result.put("flag_carrier_kills", rs.getInt("flag_carrier_kills"));
				result.put("captures", rs.getInt("total_captures"));
			}
		} catch (SQLException ex) {
			War.war.getLogger().log(Level.WARNING, "Error querying total kills/deaths for " + player.getName(), ex);
		} finally {
			closeResources(rs, stmt);
		}

		return result;
	}

	/**
	 * Get kills and deaths for a player in a specific warzone.
	 * 
	 * @param player The player to query
	 * @param warzoneName The warzone name
	 * @return Map with keys "kills", "deaths", and "flag_carrier_kills", or empty map if no data
	 */
	public static Map<String, Integer> getWarzoneKillsDeaths(OfflinePlayer player, String warzoneName) {
		Map<String, Integer> result = new HashMap<>();
		result.put("kills", 0);
		result.put("deaths", 0);
		result.put("flag_carrier_kills", 0);
		result.put("captures", 0);

		if (!War.war.getStatsConfig().isEnabled()) {
			return result;
		}

		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;

		try {
			conn = War.war.getStatsConfig().getConnection();
			stmt = conn.prepareStatement(
				"SELECT kills, deaths, flag_carrier_kills, captures FROM war_warzone_kills WHERE player_uuid = ? AND warzone_name = ?"
			);
			stmt.setString(1, player.getUniqueId().toString());
			stmt.setString(2, warzoneName);
			rs = stmt.executeQuery();

			if (rs.next()) {
				result.put("kills", rs.getInt("kills"));
				result.put("deaths", rs.getInt("deaths"));
				result.put("flag_carrier_kills", rs.getInt("flag_carrier_kills"));
				result.put("captures", rs.getInt("captures"));
			}
		} catch (SQLException ex) {
			War.war.getLogger().log(Level.WARNING, 
				"Error querying warzone kills/deaths for " + player.getName() + " in " + warzoneName, ex);
		} finally {
			closeResources(rs, stmt);
		}

		return result;
	}

	/**
	 * Get total flag captures for a player across all warzones.
	 * 
	 * @param player The player to query
	 * @return Total number of flag captures
	 */
	public static int getTotalFlagCaptures(OfflinePlayer player) {
		if (!War.war.getStatsConfig().isEnabled()) {
			return 0;
		}

		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;
		int total = 0;

		try {
			conn = War.war.getStatsConfig().getConnection();
			stmt = conn.prepareStatement(
				"SELECT total_captures FROM war_player_kills WHERE player_uuid = ?"
			);
			stmt.setString(1, player.getUniqueId().toString());
			rs = stmt.executeQuery();

			if (rs.next()) {
				total = rs.getInt("total_captures");
			}
		} catch (SQLException ex) {
			War.war.getLogger().log(Level.WARNING, "Error querying total flag captures for " + player.getName(), ex);
		} finally {
			closeResources(rs, stmt);
		}

		return total;
	}

	/**
	 * Get flag captures for a player in a specific warzone.
	 * 
	 * @param player The player to query
	 * @param warzoneName The warzone name
	 * @return Number of flag captures in that warzone
	 */
	public static int getWarzoneFlagCaptures(OfflinePlayer player, String warzoneName) {
		if (!War.war.getStatsConfig().isEnabled()) {
			return 0;
		}

		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;
		int captures = 0;

		try {
			conn = War.war.getStatsConfig().getConnection();
			stmt = conn.prepareStatement(
				"SELECT captures FROM war_warzone_kills WHERE player_uuid = ? AND warzone_name = ?"
			);
			stmt.setString(1, player.getUniqueId().toString());
			stmt.setString(2, warzoneName);
			rs = stmt.executeQuery();

			if (rs.next()) {
				captures = rs.getInt("captures");
			}
		} catch (SQLException ex) {
			War.war.getLogger().log(Level.WARNING, 
				"Error querying flag captures for " + player.getName() + " in " + warzoneName, ex);
		} finally {
			closeResources(rs, stmt);
		}

		return captures;
	}

	/**
	 * Get K/D ratio for a player across all warzones.
	 * 
	 * @param player The player to query
	 * @return K/D ratio, or 0.0 if no data
	 */
	public static double getKDRatio(OfflinePlayer player) {
		Map<String, Integer> stats = getTotalKillsDeaths(player);
		int kills = stats.get("kills");
		int deaths = stats.get("deaths");

		if (deaths == 0) {
			return kills;
		}

		return (double) kills / deaths;
	}

	/**
	 * Get top players by total kills.
	 * 
	 * @param limit Maximum number of players to return
	 * @param offset Number of players to skip (for pagination)
	 * @return List of leaderboard entries ordered by kills descending
	 */
	public static List<LeaderboardEntry> getTopPlayersByKills(int limit, int offset) {
		List<LeaderboardEntry> result = new ArrayList<>();

		if (!War.war.getStatsConfig().isEnabled()) {
			return result;
		}

		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;

		try {
			conn = War.war.getStatsConfig().getConnection();
			stmt = conn.prepareStatement(
				"SELECT player_uuid, total_kills FROM war_player_kills ORDER BY total_kills DESC LIMIT ? OFFSET ?"
			);
			stmt.setInt(1, limit);
			stmt.setInt(2, offset);
			rs = stmt.executeQuery();

			while (rs.next()) {
				UUID uuid = UUID.fromString(rs.getString("player_uuid"));
				int kills = rs.getInt("total_kills");
				result.add(new LeaderboardEntry(uuid, kills));
			}
		} catch (SQLException ex) {
			War.war.getLogger().log(Level.WARNING, "Error querying top players by kills", ex);
		} finally {
			closeResources(rs, stmt);
		}

		return result;
	}

	/**
	 * Get top players by flag captures.
	 * 
	 * @param limit Maximum number of players to return
	 * @param offset Number of players to skip (for pagination)
	 * @return List of leaderboard entries ordered by captures descending
	 */
	public static List<LeaderboardEntry> getTopPlayersByFlagCaptures(int limit, int offset) {
		List<LeaderboardEntry> result = new ArrayList<>();

		if (!War.war.getStatsConfig().isEnabled()) {
			return result;
		}

		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;

		try {
			conn = War.war.getStatsConfig().getConnection();
			stmt = conn.prepareStatement(
				"SELECT player_uuid, total_captures FROM war_player_kills " +
				"ORDER BY total_captures DESC LIMIT ? OFFSET ?"
			);
			stmt.setInt(1, limit);
			stmt.setInt(2, offset);
			rs = stmt.executeQuery();

			while (rs.next()) {
				UUID uuid = UUID.fromString(rs.getString("player_uuid"));
				int captures = rs.getInt("total_captures");
				result.add(new LeaderboardEntry(uuid, captures));
			}
		} catch (SQLException ex) {
			War.war.getLogger().log(Level.WARNING, "Error querying top players by flag captures", ex);
		} finally {
			closeResources(rs, stmt);
		}

		return result;
	}

	/**
	 * Get top players by total deaths.
	 * 
	 * @param limit Maximum number of players to return
	 * @param offset Number of players to skip (for pagination)
	 * @return List of leaderboard entries ordered by deaths descending
	 */
	public static List<LeaderboardEntry> getTopPlayersByDeaths(int limit, int offset) {
		List<LeaderboardEntry> result = new ArrayList<>();

		if (!War.war.getStatsConfig().isEnabled()) {
			return result;
		}

		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;

		try {
			conn = War.war.getStatsConfig().getConnection();
			stmt = conn.prepareStatement(
				"SELECT player_uuid, total_deaths FROM war_player_kills ORDER BY total_deaths DESC LIMIT ? OFFSET ?"
			);
			stmt.setInt(1, limit);
			stmt.setInt(2, offset);
			rs = stmt.executeQuery();

			while (rs.next()) {
				UUID uuid = UUID.fromString(rs.getString("player_uuid"));
				int deaths = rs.getInt("total_deaths");
				result.add(new LeaderboardEntry(uuid, deaths));
			}
		} catch (SQLException ex) {
			War.war.getLogger().log(Level.WARNING, "Error querying top players by deaths", ex);
		} finally {
			closeResources(rs, stmt);
		}

		return result;
	}

	/**
	 * Get top players by flag carrier kills.
	 * 
	 * @param limit Maximum number of players to return
	 * @param offset Number of players to skip (for pagination)
	 * @return List of leaderboard entries ordered by flag carrier kills descending
	 */
	public static List<LeaderboardEntry> getTopPlayersByFlagCarrierKills(int limit, int offset) {
		List<LeaderboardEntry> result = new ArrayList<>();

		if (!War.war.getStatsConfig().isEnabled()) {
			return result;
		}

		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;

		try {
			conn = War.war.getStatsConfig().getConnection();
			stmt = conn.prepareStatement(
				"SELECT player_uuid, flag_carrier_kills FROM war_player_kills " +
				"ORDER BY flag_carrier_kills DESC LIMIT ? OFFSET ?"
			);
			stmt.setInt(1, limit);
			stmt.setInt(2, offset);
			rs = stmt.executeQuery();

			while (rs.next()) {
				UUID uuid = UUID.fromString(rs.getString("player_uuid"));
				int flagCarrierKills = rs.getInt("flag_carrier_kills");
				result.add(new LeaderboardEntry(uuid, flagCarrierKills));
			}
		} catch (SQLException ex) {
			War.war.getLogger().log(Level.WARNING, "Error querying top players by flag carrier kills", ex);
		} finally {
			closeResources(rs, stmt);
		}

		return result;
	}

	/**
	 * Helper method to close JDBC resources safely.
	 * 
	 * @param rs ResultSet to close
	 * @param stmt Statement to close
	 */
	private static void closeResources(ResultSet rs, PreparedStatement stmt) {
		if (rs != null) {
			try {
				rs.close();
			} catch (SQLException ex) {
				// Ignore
			}
		}
		if (stmt != null) {
			try {
				stmt.close();
			} catch (SQLException ex) {
				// Ignore
			}
		}
	}
}
