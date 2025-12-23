package com.tommytony.war.mapper;

import com.tommytony.war.War;
import com.tommytony.war.volume.Volume;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.logging.Level;

/**
 *
 * @author tommytony
 *
 */
public class VolumeMapper {

	protected static final String delim = "-------mcwar iSdgraIyMvOanTEJjZgocczfuG------";
	protected static final int DATABASE_VERSION = 3;

	private static String getBlockDescriptor(Location loc, String type, String data, String metadata) {
		return String.format("<%d,%d,%d> type: %s, data: %s, meta: %s", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), type, data, metadata);
	}

	public static void loadCorners(Connection databaseConnection, Volume volume, World world, String prefix) throws SQLException {
		Validate.isTrue(!databaseConnection.isClosed());
		Statement stmt = databaseConnection.createStatement();
		ResultSet cornerQuery = stmt.executeQuery("SELECT * FROM " + prefix + "corners");
		cornerQuery.next();
		final Block corner1 = world.getBlockAt(cornerQuery.getInt("x"), cornerQuery.getInt("y"), cornerQuery.getInt("z"));
		cornerQuery.next();
		final Block corner2 = world.getBlockAt(cornerQuery.getInt("x"), cornerQuery.getInt("y"), cornerQuery.getInt("z"));
		cornerQuery.close();
		volume.setCornerOne(corner1);
		volume.setCornerTwo(corner2);
		stmt.close();
	}

	/**
	 * Loads the given volume
	 *
	 * @param databaseConnection Open connection to zone database
	 * @param volume             Volume to load
	 * @param start              Starting position to load blocks at
	 * @param total              Amount of blocks to read
	 * @return Changed blocks
	 * @throws SQLException Error communicating with SQLite3 database
	 */
	public static int loadBlocks(Connection databaseConnection, Volume volume, int start, int total, boolean[][][] changes, boolean inMemory, String prefix, boolean chest) throws SQLException {
		Validate.isTrue(!databaseConnection.isClosed());
		if (inMemory) {
			volume.getBlocks().clear();
		}
		final Block corner1 = volume.getCornerOne().getBlock();
		Statement stmt = databaseConnection.createStatement();
		Map<Integer, String> stringCache = new HashMap<>();
		stringCache.put(0, null);
		ResultSet cacheQuery = stmt.executeQuery("SELECT * FROM " + prefix + "strings");
		while (cacheQuery.next()) {
			stringCache.put(cacheQuery.getInt("id"), cacheQuery.getString("type"));
		}
		cacheQuery.close();
		int minX = volume.getMinX(), minY = volume.getMinY(), minZ = volume.getMinZ();
		int changed = 0;
		ResultSet query = stmt.executeQuery("SELECT * FROM " + prefix + "blocks ORDER BY rowid LIMIT " + start + ", " + total);
		while (query.next()) {
			int x = query.getInt("x"), y = query.getInt("y"), z = query.getInt("z");
			changed++;
			Block relative = corner1.getRelative(x, y, z);
			int xi = relative.getX() - minX, yi = relative.getY() - minY, zi = relative.getZ() - minZ;
			if (changes != null) {
				changes[xi][yi][zi] = true;
			}

			if (chest && !relative.getType().name().toUpperCase().contains("CHEST")) {
				continue;
			}

			BlockState modify = relative.getState();
			// Load information from database, or null if not set
			String type = stringCache.get(query.getInt("type"));
			String data = stringCache.get(query.getInt("data"));
			String metadata = stringCache.get(query.getInt("metadata"));

			// Try to look up the material. May fail due to mods or MC updates.
			Material mat = null;
			try {
				mat = Material.valueOf(type);
			} catch (IllegalArgumentException e) {
				War.war.getLogger().log(Level.WARNING, "Failed to parse block type. " + getBlockDescriptor(modify.getLocation(), type, data, metadata));
				continue;
			}

			// Try to get the block data (damage, non-tile information) using the 1.13 functions
			BlockData bdata = null;
			try {
				if (data != null) {
					bdata = Bukkit.createBlockData(data);
				}
			} catch (IllegalArgumentException iae) {
				War.war.getLogger().log(Level.WARNING, "Exception loading some block data. " + getBlockDescriptor(modify.getLocation(), type, data, metadata), iae);
			}
			// Update the block type/data in memory if they have changed
			boolean updatedType = false;
			if (modify.getType() != mat) {
				modify.setType(mat);
				updatedType = true;
			}
			boolean updatedData = false;
			if (bdata != null && !modify.getBlockData().equals(bdata)) {
				modify.setBlockData(bdata);
				updatedData = true;
			}
			if (!inMemory && (updatedType || updatedData)) {
				// Update the type & data if it has changed, needed here for tile entity check
				modify.update(true, false); // No-physics update, preventing the need for deferring blocks
				relative = corner1.getRelative(x, y, z);
				modify = relative.getState();
			}
	// Try to update the tile entity data
			if (metadata != null) {
				try {
					if (modify instanceof Sign) {
						final String[] lines = metadata.split("\n");
						for (int i = 0; i < lines.length; i++) {
							((Sign) modify).setLine(i, lines[i]);
						}
					}


// Existing code...
					if (modify instanceof Container) {
						YamlConfiguration yamlConfig = new YamlConfiguration();
						try {
							yamlConfig.loadFromString(metadata);
						} catch (InvalidConfigurationException e) {
							War.war.getLogger().log(Level.WARNING, "Invalid container metadata. " + getBlockDescriptor(modify.getLocation(), type, data, metadata), e);
							continue;
						}

						List<?> itemsList = yamlConfig.getList("items");
						if (itemsList != null) {
							Inventory inv = ((Container) modify).getInventory();
							inv.clear();
							int slot = 0;
							for (Object obj : itemsList) {
								if (obj instanceof ItemStack) {
									inv.setItem(slot, (ItemStack) obj);
								}
								++slot;
							}
						}
					}

					// Records
					if (modify instanceof Jukebox) {
						if (metadata != null && !metadata.equals("items: - null")) {
							try {
								Material material = Material.matchMaterial(metadata);
								if (material != null) {
									((Jukebox) modify).setRecord(new ItemStack(material));
								} else {
									War.war.getLogger().log(Level.WARNING, "Invalid record type: " + metadata);
								}
							} catch (Exception e) {
								War.war.getLogger().log(Level.WARNING, "Error setting jukebox record: " + metadata, e);
							}
						}
					}



					// Skulls
					if (modify instanceof Skull) {
						try {
							UUID playerId = UUID.fromString(metadata);
							OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
							((Skull) modify).setOwningPlayer(player);
						} catch (IllegalArgumentException e) {
							War.war.getLogger().log(Level.WARNING, "Invalid player UUID. " + getBlockDescriptor(modify.getLocation(), type, data, metadata), e);
						}
					}

					// Command blocks
					if (modify instanceof CommandBlock) {
						final String[] commandArray = metadata.split("\n");
						if (commandArray.length >= 2) {
							((CommandBlock) modify).setName(commandArray[0]);
							((CommandBlock) modify).setCommand(commandArray[1]);
						} else {
							War.war.getLogger().log(Level.WARNING, "Invalid command block metadata. " + getBlockDescriptor(modify.getLocation(), type, data, metadata));
						}
					}

					// Creature spawner
					if (modify instanceof CreatureSpawner) {
						try {
							EntityType entityType = EntityType.valueOf(metadata);
							((CreatureSpawner) modify).setSpawnedType(entityType);
						} catch (IllegalArgumentException e) {
							War.war.getLogger().log(Level.WARNING, "Invalid creature type. " + getBlockDescriptor(modify.getLocation(), type, data, metadata), e);
						}
					}

					if (!inMemory) {
						modify.update(true, false);
					}
				} catch (Exception ex) {
					War.war.getLogger().log(Level.WARNING, "Exception loading some tile entity data. " + getBlockDescriptor(modify.getLocation(), type, data, metadata), ex);
				}
			}

				if (inMemory) {
				volume.getBlocks().add(modify);
			}
		}
		query.close();
		stmt.close();
		return changed;
	}

	/**
	 * Load saved entities.
	 *
	 * @param databaseConnection Open databaseConnection to warzone DB file.
	 * @param volume Volume for warzone.
	 * @return number affected
	 * @throws SQLException SQLite error
	 */
	public static int loadEntities(Connection databaseConnection, Volume volume) throws SQLException {
		Validate.isTrue(!databaseConnection.isClosed());
		// first, clear entities from the area
		for (Entity e : volume.getWorld().getEntitiesByClass(Hanging.class)) {
			if (volume.contains(e.getLocation())) {
				e.remove();
			}
		}
		int changed = 0;
		Statement stmt = databaseConnection.createStatement();
		final Block corner1 = volume.getCornerOne().getBlock();
		Location test = new Location(volume.getWorld(), 0, 253, 0); // admins pls don't build stuff here kthx
		ResultSet query = stmt.executeQuery("SELECT * FROM entities ORDER BY rowid");
		while (query.next()) {
			double x = query.getDouble("x"), y = query.getDouble("y"), z = query.getDouble("z");
			changed++;
			// translate from relative DB location to absolute
			Location absolute = corner1.getLocation().clone().add(x, y, z);
			int type = query.getInt("type");
			String facing = query.getString("facing");
			String metadata = query.getString("metadata");
			BlockFace face = BlockFace.valueOf(facing);

			// Spawn the paintings in the sky somewhere, works because I can only get them to spawn north/south
			test.getBlock().setType(Material.AIR);
			test.add(2, 0, 0).getBlock().setType(Material.STONE);

			try {
				if (type == 1) {
					Painting p = (Painting) volume.getWorld().spawnEntity(test.clone().add(0, 0, 1), EntityType.PAINTING);
					Art art = Art.valueOf(metadata);
					p.teleport(calculatePainting(art, face, absolute));
					p.setFacingDirection(face, true);
					p.setArt(art, true);
				} else if (type == 2) {
					ItemFrame itemFrame = (ItemFrame) volume.getWorld().spawnEntity(test.clone().add(0, 0, 1), EntityType.ITEM_FRAME);
					itemFrame.teleport(absolute);
					itemFrame.setFacingDirection(face, true);
					String[] args = metadata.split(delim);
					itemFrame.setRotation(Rotation.valueOf(args[0]));
					YamlConfiguration config = new YamlConfiguration();
					config.loadFromString(args[1]);
					itemFrame.setItem(config.getItemStack("item"));
				}
			} catch (Exception ex) {
				War.war.getLogger().log(Level.WARNING, "Exception loading entity. x:" + x + " y:" + y + " z:" + z + " type:" + type, ex);
			}
		}
		test.getBlock().setType(Material.AIR);
		query.close();
		stmt.close();
		return changed;
	}

	public static void saveCorners(Connection databaseConnection, Volume volume, String prefix) throws SQLException {
		Statement stmt = databaseConnection.createStatement();
		stmt.executeUpdate("PRAGMA user_version = " + DATABASE_VERSION);
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS "+prefix+"corners (pos INTEGER PRIMARY KEY  NOT NULL  UNIQUE, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL)");
		stmt.executeUpdate("DELETE FROM "+prefix+"corners");
		stmt.close();
		PreparedStatement cornerStmt = databaseConnection.prepareStatement("INSERT INTO " + prefix + "corners SELECT 1 AS pos, ? AS x, ? AS y, ? AS z UNION SELECT 2, ?, ?, ?");
		cornerStmt.setInt(1, volume.getCornerOne().getBlockX());
		cornerStmt.setInt(2, volume.getCornerOne().getBlockY());
		cornerStmt.setInt(3, volume.getCornerOne().getBlockZ());
		cornerStmt.setInt(4, volume.getCornerTwo().getBlockX());
		cornerStmt.setInt(5, volume.getCornerTwo().getBlockY());
		cornerStmt.setInt(6, volume.getCornerTwo().getBlockZ());
		cornerStmt.executeUpdate();
		cornerStmt.close();
	}

	public static int saveEntities(Connection databaseConnection, Volume volume) throws SQLException {
		Statement stmt = databaseConnection.createStatement();
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS entities (x NUMERIC, y NUMERIC, z NUMERIC, type SMALLINT, facing TEXT, metadata TEXT)");
		stmt.executeUpdate("DELETE FROM entities");
		stmt.close();
		PreparedStatement entityStmt = databaseConnection.prepareStatement("INSERT INTO entities (x, y, z, type, facing, metadata) VALUES (?, ?, ?, ?, ?, ?)");
		int i = 0;
		for (Entity e : volume.getWorld().getEntities()) {
			if (volume.contains(e.getLocation()) && e instanceof Hanging) {
				entityStmt.setDouble(1, e.getLocation().getX() - volume.getCornerOne().getBlockX());
				entityStmt.setDouble(2, e.getLocation().getY() - volume.getCornerOne().getBlockY());
				entityStmt.setDouble(3, e.getLocation().getZ() - volume.getCornerOne().getBlockZ());
				entityStmt.setString(5, ((Hanging) e).getFacing().name());
				if (e instanceof Painting) {
					Painting p = (Painting) e;
					entityStmt.setInt(4, 1);
					entityStmt.setString(6, p.getArt().name());
				} else if (e instanceof ItemFrame) {
					ItemFrame itemFrame = (ItemFrame) e;
					YamlConfiguration config = new YamlConfiguration();
					config.set("item", itemFrame.getItem());
					entityStmt.setInt(4, 2);
					entityStmt.setString(6, itemFrame.getRotation().name() + delim + config.saveToString());
				} else {
					entityStmt.setInt(4, 0);
					entityStmt.setString(6, "");
				}
				entityStmt.addBatch();
				++i;
			}
		}
		entityStmt.executeBatch();
		entityStmt.close();
		return i;
	}

	public static int saveBlocks(Connection databaseConnection, Volume volume, String prefix) throws SQLException {
		Statement stmt = databaseConnection.createStatement();
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS "+prefix+"blocks (x BIGINT, y BIGINT, z BIGINT, type BIGINT, data BIGINT, metadata BIGINT)");
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS "+prefix+"strings (id INTEGER PRIMARY KEY NOT NULL UNIQUE, type TEXT)");
		stmt.executeUpdate("DELETE FROM "+prefix+"blocks");
		stmt.executeUpdate("DELETE FROM "+prefix+"strings");
		stmt.close();
		Map<String, Integer> stringCache = new HashMap<>();
		int cachei = 1;
		int changed = 0;
		long startTime = System.currentTimeMillis();
		PreparedStatement dataStmt = databaseConnection.prepareStatement("INSERT INTO "+prefix+"blocks (x, y, z, type, data, metadata) VALUES (?, ?, ?, ?, ?, ?)");
		databaseConnection.setAutoCommit(false);
		final int batchSize = 10000;
		for (int i = 0, x = volume.getMinX(); i < volume.getSizeX(); i++, x++) {
			for (int j = 0, y = volume.getMinY(); j < volume.getSizeY(); j++, y++) {
				for (int k = 0, z = volume.getMinZ(); k < volume.getSizeZ(); k++, z++) {
					// Make sure we are using zone volume-relative coords
					final Block block = volume.getWorld().getBlockAt(x, y, z);
					if (block.getType() == Material.AIR) {
						continue; // Do not save air blocks to the file anymore.
					}
					int typeid, dataid, metaid;
					// Save even more space by writing each string only once
					if (stringCache.containsKey(block.getType().name())) {
						typeid = stringCache.get(block.getType().name());
					} else {
						typeid = cachei;
						stringCache.put(block.getType().name(), cachei++);
					}
					// Save new-style data
					if (BlockData.class.isAssignableFrom(block.getType().data)) {
						String data = block.getBlockData().getAsString();
						if (stringCache.containsKey(data)) {
							dataid = stringCache.get(data);
						} else {
							dataid = cachei;
							stringCache.put(data, cachei++);
						}
					} else {
						dataid = 0;
					}

					// Save tile entities
					BlockState state = block.getState();
					String metadata = "";
					if (state instanceof Sign) {
						metadata = StringUtils.join(((Sign) state).getLines(), "\n");
					} else if (state instanceof InventoryHolder) {
						List<ItemStack> items = Arrays.asList(((InventoryHolder) state).getInventory().getContents());
						YamlConfiguration config = new YamlConfiguration();
						// Serialize to config, then store config in database
						config.set("items", items);
						metadata = config.saveToString();
					} else if (state instanceof Jukebox) {
						metadata = ((Jukebox) state).getPlaying().toString();
					} else if (state instanceof Skull) {
						OfflinePlayer player = ((Skull) state).getOwningPlayer();
						metadata = player == null ? "" : player.getUniqueId().toString();
					} else if (state instanceof CommandBlock) {
						metadata = ((CommandBlock) state).getName() + "\n" + ((CommandBlock) state).getCommand();
					} else if (state instanceof CreatureSpawner) {
						metadata = ((CreatureSpawner) state).getSpawnedType().toString();
					}
					if (metadata.isEmpty()) {
						metaid = 0;
					} else if (stringCache.containsKey(metadata)) {
						metaid = stringCache.get(metadata);
					} else {
						metaid = cachei;
						stringCache.put(metadata, cachei++);
					}

					dataStmt.setInt(1, block.getX() - volume.getCornerOne().getBlockX());
					dataStmt.setInt(2, block.getY() - volume.getCornerOne().getBlockY());
					dataStmt.setInt(3, block.getZ() - volume.getCornerOne().getBlockZ());
					dataStmt.setInt(4, typeid);
					dataStmt.setInt(5, dataid);
					dataStmt.setInt(6, metaid);

					dataStmt.addBatch();

					if (++changed % batchSize == 0) {
						dataStmt.executeBatch();
						if ((System.currentTimeMillis() - startTime) >= 5000L) {
							String seconds = new DecimalFormat("#0.00").format((double) (System.currentTimeMillis() - startTime) / 1000.0D);
							War.war.getLogger().log(Level.FINE, "Still saving volume {0} , {1} seconds elapsed.", new Object[] {volume.getName(), seconds});
						}
					}
				}
			}
		}
		dataStmt.executeBatch(); // insert remaining records
		databaseConnection.commit();
		dataStmt.close();
		PreparedStatement stringStmt = databaseConnection.prepareStatement("INSERT INTO "+prefix+"strings (id, type) VALUES (?, ?)");
		for (Map.Entry<String, Integer> mapping : stringCache.entrySet()) {
			stringStmt.setInt(1, mapping.getValue());
			stringStmt.setString(2, mapping.getKey());
			stringStmt.addBatch();
		}
		stringStmt.executeBatch();
		databaseConnection.commit();
		databaseConnection.setAutoCommit(true);
		return changed;

	}

	/**
	 * Save a simple volume, like the WarHub.
	 *
	 * @param volume Volume to save (takes corner data and loads from world).
	 * @return amount of changed blocks
	 * @throws SQLException
	 */
	public static int saveSimpleVolume(Volume volume) throws SQLException {
		File volFile = new File(War.war.getDataFolder(), String.format("/dat/volume-%s.sl3", volume.getName()));
		Connection databaseConnection = getConnection(volFile);
		int changed = 0;
		saveCorners(databaseConnection, volume, "");
		saveBlocks(databaseConnection, volume, "");
		databaseConnection.close();
		return changed;
	}

	public static boolean deleteSimpleVolume(Volume volume) {
		File volFile = new File(War.war.getDataFolder(), String.format("/dat/volume-%s.sl3", volume.getName()));
		boolean deletedData = volFile.delete();
		if (!deletedData) {
			War.war.log("Failed to delete file " + volFile.getName(), Level.WARNING);
		}
		return deletedData;
	}

	public static Volume loadSimpleVolume(String volumeName, World world) throws SQLException {
		File volFile = new File(War.war.getDataFolder(), String.format("/dat/volume-%s.sl3", volumeName));
		Connection databaseConnection = getConnection(volFile);
		int version = checkConvert(databaseConnection);
		Volume v = new Volume(volumeName, world);
		switch (version) {
			case 1:
			case 2:
				War.war.log(volumeName + " cannot be migrated from War 1.9 due to breaking MC1.13 changes - please resave.", Level.WARNING);
				loadCorners(databaseConnection, v, world, "");
				convertSchema2_3(databaseConnection, "", true);
				return v;
			case 3:
				break;
			default:
				throw new IllegalStateException(String.format("Unsupported volume format (was already converted to version: %d, current format: %d)", version, DATABASE_VERSION));
		}
		loadCorners(databaseConnection, v, world, "");
		loadBlocks(databaseConnection, v, 0, 0, null, true, "", false);
		return v;
	}

	protected static Connection getConnection(File filename) throws SQLException {
		return DriverManager.getConnection("jdbc:sqlite:" + filename.getPath());
	}

	protected static int checkConvert(Connection databaseConnection) throws SQLException {
		Statement stmt = databaseConnection.createStatement();
		ResultSet versionQuery = stmt.executeQuery("PRAGMA user_version");
		int version = versionQuery.getInt("user_version");
		versionQuery.close();
		stmt.close();
		return version;
	}

	protected static void convertSchema2_3(Connection databaseConnection, String prefix, boolean isSimple) throws SQLException {
		Statement stmt = databaseConnection.createStatement();
		stmt.executeUpdate("DROP TABLE " + prefix + "blocks");
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS "+prefix+"blocks (x BIGINT, y BIGINT, z BIGINT, type BIGINT, data BIGINT, metadata BIGINT)");
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS "+prefix+"strings (id INTEGER PRIMARY KEY NOT NULL UNIQUE, type TEXT)");
		stmt.executeUpdate("PRAGMA user_version = " + DATABASE_VERSION);
		stmt.close();
	}





	/**
	 * Finds the correct location to place a painting based on its characteristics.
	 * Credit goes to whatever forum I got this from.
	 *
	 * @param art Painting type
	 * @param facing Block face
	 * @param loc Desired location
	 * @return Corrected location
	 */
	private static Location calculatePainting(Art art, BlockFace facing, Location loc) {
		String artKey = art.getKey().getKey();
		
		// 1x1 paintings (no calculation needed)
		if (artKey.equals("alban") || artKey.equals("aztec") || artKey.equals("aztec2") || 
		    artKey.equals("bomb") || artKey.equals("kebab") || artKey.equals("plant") || 
		    artKey.equals("wasteland")) {
			return loc;
		}
		
		// 1x2 paintings
		if (artKey.equals("graham") || artKey.equals("wanderer")) {
			return loc.getBlock().getLocation().add(0, -1, 0);
		}
		
		// 2x1 and 4x3 paintings (same calculation)
		if (artKey.equals("creebet") || artKey.equals("courbet") || artKey.equals("pool") || 
		    artKey.equals("sea") || artKey.equals("sunset") || artKey.equals("donkey_kong") || 
		    artKey.equals("skeleton")) {
			if (facing == BlockFace.WEST)
				return loc.getBlock().getLocation().add(0, 0, -1);
			else if (facing == BlockFace.SOUTH)
				return loc.getBlock().getLocation().add(-1, 0, 0);
			else
				return loc;
		}
		
		// 2x2, 4x2, and 4x4 paintings (same calculation)
		if (artKey.equals("bust") || artKey.equals("match") || artKey.equals("skull_and_roses") || 
		    artKey.equals("stage") || artKey.equals("void") || artKey.equals("wither") || 
		    artKey.equals("fighters") || artKey.equals("burning_skull") || artKey.equals("pigscene") || 
		    artKey.equals("pointer")) {
			if (facing == BlockFace.WEST)
				return loc.getBlock().getLocation().add(0, -1, -1);
			else if (facing == BlockFace.SOUTH)
				return loc.getBlock().getLocation().add(-1, -1, 0);
			else
				return loc.add(0, -1, 0);
		}
		
		// Unsupported or unknown artwork
		return loc;
	}

}
