# War Stats Tracking System

This document describes the H2 database-backed stats tracking system for player kills, deaths, and flag captures.

## Overview

The War plugin now tracks detailed player statistics using an embedded H2 database with automatic batching for optimal performance. Stats are tracked both globally (across all warzones) and per-warzone.

## Features

- **Kill Tracking**: Total kills and per-warzone kills for each player
- **Death Tracking**: Total deaths and per-warzone deaths for each player
- **Flag Capture Tracking**: Total flag captures and per-warzone captures
- **Batched Processing**: Stats are queued and written in batches to minimize database I/O
- **Automatic Flushing**: Stats are flushed periodically or when batch size is reached
- **Thread-Safe**: All operations are thread-safe and use async processing

## Database Schema

### Table: `war_player_kills`
Stores total kills and deaths across all warzones.

| Column | Type | Description |
|--------|------|-------------|
| player_uuid | VARCHAR(36) | Player's unique ID (Primary Key) |
| player_name | VARCHAR(16) | Player's current name |
| total_kills | INT | Total kills across all warzones |
| total_deaths | INT | Total deaths across all warzones |
| last_updated | TIMESTAMP | Last update timestamp |

### Table: `war_warzone_kills`
Stores kills and deaths per warzone.

| Column | Type | Description |
|--------|------|-------------|
| player_uuid | VARCHAR(36) | Player's unique ID |
| player_name | VARCHAR(16) | Player's current name |
| warzone_name | VARCHAR(255) | Name of the warzone |
| kills | INT | Kills in this warzone |
| deaths | INT | Deaths in this warzone |
| last_updated | TIMESTAMP | Last update timestamp |

**Primary Key**: (player_uuid, warzone_name)

### Table: `war_flag_captures`
Stores flag captures per warzone.

| Column | Type | Description |
|--------|------|-------------|
| player_uuid | VARCHAR(36) | Player's unique ID |
| player_name | VARCHAR(16) | Player's current name |
| warzone_name | VARCHAR(255) | Name of the warzone |
| captures | INT | Number of flag captures |
| last_updated | TIMESTAMP | Last update timestamp |

**Primary Key**: (player_uuid, warzone_name)

## Configuration

Stats tracking is configured in the War plugin's configuration file:

```yaml
stats:
  enabled: true                    # Enable/disable stats tracking
  database:
    path: "plugins/War/stats"      # H2 database file path
  batch:
    size: 100                      # Number of stats to queue before auto-flush
    interval: 300                  # Flush interval in seconds (default: 5 minutes)
```

## Usage

### Querying Stats

The `StatsQueryUtil` class provides methods to query player statistics:

```java
import com.tommytony.war.utility.StatsQueryUtil;
import org.bukkit.OfflinePlayer;
import java.util.Map;

// Get total kills and deaths
Map<String, Integer> stats = StatsQueryUtil.getTotalKillsDeaths(player);
int kills = stats.get("kills");
int deaths = stats.get("deaths");

// Get kills/deaths for a specific warzone
Map<String, Integer> zoneStats = StatsQueryUtil.getWarzoneKillsDeaths(player, "MyWarzone");

// Get K/D ratio
double kd = StatsQueryUtil.getKDRatio(player);

// Get total flag captures
int captures = StatsQueryUtil.getTotalFlagCaptures(player);

// Get flag captures for specific warzone
int zoneCaptures = StatsQueryUtil.getWarzoneFlagCaptures(player, "MyWarzone");

// Get top 10 players by kills
Map<UUID, Integer> topKillers = StatsQueryUtil.getTopPlayersByKills(10);

// Get top 10 players by flag captures
Map<UUID, Integer> topCapturers = StatsQueryUtil.getTopPlayersByFlagCaptures(10);
```

### Manual Stats Recording

Stats are automatically tracked when:
- A player kills another player in a warzone
- A player dies in a warzone
- A player captures a flag

If you need to manually record stats:

```java
import com.tommytony.war.job.LogStatsJob;
import com.tommytony.war.War;

// Record a kill
War.war.queueStatsRecord(new LogStatsJob.StatsRecord(
    player, LogStatsJob.StatsType.KILL, warzoneName));

// Record a death
War.war.queueStatsRecord(new LogStatsJob.StatsRecord(
    player, LogStatsJob.StatsType.DEATH, warzoneName));

// Record a flag capture
War.war.queueStatsRecord(new LogStatsJob.StatsRecord(
    player, LogStatsJob.StatsType.FLAG_CAPTURE, warzoneName));
```

## Implementation Details

### Batching System

Stats are not written immediately to avoid performance issues. Instead:

1. Each stat event is added to a queue via `War.queueStatsRecord()`
2. When the queue reaches `batch.size` (default 100), it automatically flushes
3. A periodic task flushes the queue every `batch.interval` seconds (default 300)
4. On plugin shutdown, any remaining queued stats are flushed

### Database Operations

- **MERGE statements**: Used to update existing records or insert new ones
- **Batch execution**: Multiple records are inserted in a single batch
- **Connection pooling**: The H2 connection is reused across operations
- **Async processing**: All database writes occur asynchronously

### Thread Safety

- The stats queue is synchronized to prevent concurrent modification
- Database connections are obtained per-operation
- Async tasks handle all blocking I/O operations

## Files

- `StatsConfig.java` - Configuration and database initialization
- `LogStatsJob.java` - Batch processing job for writing stats
- `StatsQueryUtil.java` - Utility methods for querying stats
- `War.java` - Queue management and periodic flushing
- `Warzone.java` - Kill/death tracking integration
- `WarPlayerListener.java` - Flag capture tracking integration

## Performance Considerations

- **Batch Size**: Larger batch sizes reduce write frequency but delay stat visibility
- **Flush Interval**: Shorter intervals improve real-time accuracy but increase I/O
- **Database Location**: SSD storage recommended for best performance
- **Indexes**: Automatically created on player_uuid and warzone_name columns

## Maintenance

The H2 database is stored at `plugins/War/stats.mv.db` and requires no manual maintenance. The database:
- Automatically grows as needed
- Uses minimal disk space (typically < 10MB for thousands of records)
- Can be backed up by copying the `.mv.db` file while the server is stopped
- Can be queried externally using any H2-compatible tool

## Troubleshooting

### Stats not recording
1. Check that `stats.enabled: true` in config
2. Check server logs for SQL errors
3. Verify H2 dependency is loaded

### Database file locked
- Ensure only one server instance is running
- Check file permissions on the database directory

### High memory usage
- Reduce `batch.size` to flush more frequently
- Reduce `batch.interval` to prevent large queues

## Example Commands (Future Enhancement)

While not yet implemented, the stats system provides the foundation for commands like:

```
/war stats <player>          # Show player's total stats
/war stats <player> <zone>   # Show player's stats for a zone
/war leaderboard kills       # Top players by kills
/war leaderboard captures    # Top players by flag captures
/war leaderboard kd          # Top players by K/D ratio
```

These can be implemented using the `StatsQueryUtil` methods.
