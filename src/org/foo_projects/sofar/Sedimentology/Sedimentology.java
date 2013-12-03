package org.foo_projects.sofar.Sedimentology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Map;

import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.scheduler.BukkitScheduler;

import com.massivecraft.factions.entity.BoardColls;
import com.massivecraft.factions.entity.Faction;
import com.massivecraft.mcore.ps.PS;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.object.TownyUniverse;

/*
 * Sedimentology concepts
 *
 * Erosion & deposition go hand in hand - at higher elevation (>64) erosion dominates, and at
 * lower elevations, sedimentation dominates.
 *
 * Decay cycles affect rocks - stone breaks into gravel, into sand, into clay. Each step takes longer
 *
 * Transports are wind, water, ice.
 *
 * Wind can displace sand - create dunes.
 * Water displaces sand and clay
 * Ice displaces all blocks
 *
 * Snow compacts into Ice
 *
 * Blocks do not disappear, the volume of blocks remains equal througout the process
 *
 * slope affects erosion - a higher slope means more erosion
 *
 * deposition happens where the slope reduces - a lower slope means more deposition
 *
 */

public final class Sedimentology extends JavaPlugin {
	private long stat_considered;
	private long stat_displaced;
	private long stat_degraded;
	private long stat_errors;
	private long stat_protected;
	private long stat_ignored_edge;
	private long stat_ignored_type;
	private long stat_ignored_storm;
	private long stat_ignored_vegetation;
	private long stat_ignored_resistance;
	private long stat_ignored_water;
	private long stat_ignored_sand;
	private long stat_ignored_hardness;
	private int x, y, z;
	private long conf_blocks = 10;
	private boolean conf_protect = true;
	private boolean have_factions = false;
	private boolean have_towny = false;

	private Object towny_universe = null;

	public static final List<String> defaultWorldList = Collections.unmodifiableList(Arrays.asList("world"));

	private class sedWorld {
		World world;
		Map<String, Integer> chunkMap;
	}

	private List<sedWorld> sedWorldList;

	private Random rnd = new Random();

	private class offset {
		public offset(int i, int j) {
			x = i;
			z = j;
		}
		public int x;
		public int z;
	};

	public boolean ChunkMapLoaded(Map<String, Integer> chunkMap, int x, int z) {
		String key = "(" + x + "," + z + ")";
		if (chunkMap.get(key) == null)
			return false;
		return chunkMap.get(key) == 1;
	}

	public void ChunkMapLoad(Map<String, Integer> chunkMap, int x, int z) {
		String key = "(" + x + "," + z + ")";
		chunkMap.put(key, 1);
	}

	public void ChunkMapUnload(Map<String, Integer> chunkMap, int x, int z) {
		String key = "(" + x + "," + z + ")";
		chunkMap.put(key, 0);
	}

	/* helper function to find lowest deposit elevation ignoring water */
	private int findDepositLocation(World world, int x, int y, int z) {
		int yy = y;
		while (true) {
			switch (world.getBlockAt(x, yy, z).getType()) {
				case AIR:
				case WATER:
				case STATIONARY_WATER:
				case LEAVES:
				case CACTUS:
				case SAPLING:
				case LONG_GRASS:
				case DEAD_BUSH:
				case YELLOW_FLOWER:
				case RED_ROSE:
				case BROWN_MUSHROOM:
				case RED_MUSHROOM:
				case CROPS:
				case MELON_STEM:
				case PUMPKIN_STEM:
				case MELON_BLOCK:
				case PUMPKIN:
				case VINE:
				case SUGAR_CANE:
				case DOUBLE_PLANT:
				case LEAVES_2:
				case SNOW:
				case SNOW_BLOCK:
					yy--;
					if (yy == 0)
						return yy;
					break;
				default:
					return yy + 1;
			}
		}
	}

	/* determine if a block is protected */
	private boolean isProtected(World world, int xx, int yy, int zz) {
		if (!conf_protect)
			return false;

		if (have_factions) {
			Faction faction = BoardColls.get().getFactionAt(PS.valueOf(new Location(world, xx, yy, zz)));
			if (!faction.isNone())
				return true;
		}
		if (have_towny) {
			if (((TownyUniverse)towny_universe).getTownBlock(new Location(world, xx, yy, zz)) != null)
				return true;
		}

		return false;
	}

	public void sed(sedWorld sedworld) {
		stat_considered++;
		World world = sedworld.world;
		Chunk ChunkList[] = world.getLoadedChunks();
		Chunk c = ChunkList[(int) Math.abs(rnd.nextDouble() * ChunkList.length)];

		double hardness;
		double resistance;
		double waterfactor;
		double vegetationfactor;
		double stormfactor;

		boolean underwater = false;
		boolean undermovingwater = false;
		boolean targetunderwater = false;
		boolean undersnow = false;

		int bx = (int)(Math.round(rnd.nextDouble() * 16));
		int bz = (int)(Math.round(rnd.nextDouble() * 16));

		/* don't bother touching chunks at the edges */
		for (int xx = c.getX() - 1 ; xx <= c.getX() + 1; xx++) {
			for (int zz = c.getZ() - 1 ; zz <= c.getZ() + 1; zz++) {
				if (ChunkMapLoaded(sedworld.chunkMap, xx, zz) == false) {
					stat_ignored_edge++;
					return;
				}
			}
		}

		x = bx + (c.getX() * 16);
		z = bz + (c.getZ() * 16);

		/* find highest block, even if underwater */
		y = world.getHighestBlockYAt(x, z) - 1;
		switch (world.getBlockAt(x, y, z).getType()) {
			case WATER:
				undermovingwater = true;
			case STATIONARY_WATER:
				underwater = true;
				y = findDepositLocation(world, x, y, z) - 1;
				break;
			//FIXME: start handling ice from here
			case SNOW:
			case SNOW_BLOCK:
				undersnow = true;
				y = findDepositLocation(world, x, y, z) - 1;
				break;
			default:
				break;
		}

		if (isProtected(world, x, y, z)) {
			stat_protected++;
			return;
		}
		Block b = world.getBlockAt(x, y, z);

		/* filter out blocks we don't erode right now */
		hardness = 1.0;
		resistance = 1.0;
		switch (b.getType()) {
			case SOIL:
			case DIRT:
			case GRASS:
				break;
			case SAND: /* this covers red sand */
				/*
				 * breaking sand into clay is hard, this also prevents all
				 * sand from turning into clay
				 */
				hardness = 0.01;
				break;
			case GRAVEL:
				hardness = 0.15;
				resistance = 0.75;
				break;
			case CLAY:
				resistance = 0.3;
				break;
			case HARD_CLAY:
			case STAINED_CLAY:
			case SANDSTONE:
			case COBBLESTONE:
				hardness = 0.05;
				resistance = 0.05;
				break;
			case STONE:
				hardness = 0.01;
				resistance = 0.01;
				break;
			/* ores don't break down much at all, but they are displaced as easy stone */
			case COAL_ORE:
			case IRON_ORE:
			case LAPIS_ORE:
			case EMERALD_ORE:
			case GOLD_ORE:
			case DIAMOND_ORE:
			case REDSTONE_ORE:
				hardness = 0.0001;
				resistance = 0.01;
				break;
			default:
				stat_ignored_type++;
				return;
		}

		/* lower overall chance due to lack of water */
		stormfactor = 0.1;
		if (world.hasStorm()) {
			switch (b.getBiome()) {
				/* arid envirnments where no precipitation happens */
				case DESERT:
				case DESERT_HILLS:
				case DESERT_MOUNTAINS:
				case MESA:
				case MESA_BRYCE:
				case MESA_PLATEAU:
				case MESA_PLATEAU_FOREST:
				case MESA_PLATEAU_FOREST_MOUNTAINS:
				case MESA_PLATEAU_MOUNTAINS:
				case SAVANNA:
				case SAVANNA_MOUNTAINS:
				case SAVANNA_PLATEAU:
				case SAVANNA_PLATEAU_MOUNTAINS:
					break;
				default:
					stormfactor = 1.0;
					break;
			}
		}

		if ((!underwater) && (rnd.nextDouble() > stormfactor)) {
			stat_ignored_storm++;
			return;
		}

		// water increases displacement/degradation
		waterfactor = 0.01; //this is probably too low

		if (undermovingwater)
			waterfactor = 1.0;
		else if (underwater)
			waterfactor = 0.5;
		else {
waterloop:
			for (int xx = x - 2; xx < x + 2; xx++) {
				for (int zz = z - 2; zz < z + 2; zz++) {
					for (int yy = y - 2; yy < y + 2; yy++) {
						switch(world.getBlockAt(xx, yy, zz).getType()) {
						case WATER:
							waterfactor = 0.25;
							break waterloop;
						case STATIONARY_WATER:
							waterfactor = 0.125;
							break;
						default:
							break;
						}
					}
				}
			}
		}

		if (rnd.nextDouble() > waterfactor) {
			stat_ignored_water++;
			return;
		}

		/* a snow cover slows down things a bit */
		if (undersnow) {
			if (rnd.nextDouble() > 0.25) {
				stat_ignored_water++;
				return;
			}
		}

		/* slow down when deeper under the sealevel */
		if (underwater) {
			if (y < world.getSeaLevel()) {
				/* exponentially slower with depth. 100% at 1 depth, 50% at 2, 25% at 3 etc... */
				if (rnd.nextDouble() > 2.0 * Math.pow(0.5, world.getSeaLevel() - y)) {
					stat_ignored_water++;
					return;
				}
			}
		}

		// vegetation slows down displacement
		vegetationfactor = 1.0;

		for (int xx = x - 3; xx < x + 3; xx++) {
			for (int zz = z - 3; zz < z + 3; zz++) {
				for (int yy = y - 3; yy < y + 3; yy++) {
					switch(world.getBlockAt(xx, yy, zz).getType()) {
						case LEAVES:
						case CACTUS:
						case SAPLING:
						case LOG:
						case LONG_GRASS:
						case DEAD_BUSH:
						case YELLOW_FLOWER:
						case RED_ROSE:
						case BROWN_MUSHROOM:
						case RED_MUSHROOM:
						case CROPS:
						case MELON_STEM:
						case PUMPKIN_STEM:
						case MELON_BLOCK:
						case PUMPKIN:
						case VINE:
						case SUGAR_CANE:
						case DOUBLE_PLANT:
						case LEAVES_2:
						case LOG_2:
							/* distance to vegetation: 3.0 (far) to 0.3 (near) */
							double d = (Math.abs(xx - x) + Math.abs(yy - y) + Math.abs(zz -z)) / 3.0;
							/* somewhat complex calculation here to make the chance
							 * proportional to the distance: 0.5 / (1.0 -> 3.7)
							 * Basically ends up being 0.5 (far) to 0.135 (near)
							 */
							double f = 0.5 / (4.0 - d); 
							if (f < vegetationfactor)
								vegetationfactor = f;
							break; //vegetationloop;
						default:
							break;
					}
				}
			}
		}

		if (rnd.nextDouble() > vegetationfactor) {
			stat_ignored_vegetation++;
			return;
		}

		/* displace block? */
displace:
		if (true) {
			//FIXME displace should factor in elevation, but it doesn't git well
			// in our implementation. Perhaps we can modify hardness and increase it
			// for high elevations

			int step, steps;

			/* find the most suitable target location to move this block to */
			if ((b.getType() == Material.SAND) || (underwater))
				steps = 24;
			else
				steps = 8;

			int lowest = y;
			offset lowestoffset = new offset(0, 0);
			offset o = new offset(0, 0);
			int tx = 0, ty = 0, tz = 0;

			for (step = 0; step < steps; step++) {
				o = walker_f(o);
				int h = findDepositLocation(world, x + o.x, lowest, z + o.z);

				if (h < lowest) {
					lowest = h;
					lowestoffset = o;
					break;
				}
			}

			/* flat ? */
			if (lowest == y)
				break displace;

			tx = x + lowestoffset.x;
			ty = lowest;
			tz = z + lowestoffset.z;

			if (isProtected(world, tx, ty, tz)) {
					stat_protected++;
					return;
			}

			/* roll to move it */
			if (rnd.nextDouble() > resistance) {
				stat_ignored_resistance++;
				return;
			}

			/* It's time to move it, move it. */
			Block t = world.getBlockAt(tx, ty, tz);
			switch (t.getType()) {
				case AIR:
				case WATER:
				case STATIONARY_WATER:
				case LEAVES:
				case CACTUS:
				case SAPLING:
				case LONG_GRASS:
				case DEAD_BUSH:
				case YELLOW_FLOWER:
				case RED_ROSE:
				case BROWN_MUSHROOM:
				case RED_MUSHROOM:
				case CROPS:
				case MELON_STEM:
				case PUMPKIN_STEM:
				case MELON_BLOCK:
				case PUMPKIN:
				case VINE:
				case SUGAR_CANE:
				case SNOW:
				case SNOW_BLOCK:
				case DOUBLE_PLANT:
				case LEAVES_2:
					/* play a sound at the deposition area */
					Sound snd;

					switch (world.getBlockAt(tx, ty, z).getType()) {
						case WATER:
						case STATIONARY_WATER:
							targetunderwater = true;
							break;
						default:
							break;
					}

					Material mat = b.getType();
					byte dat = b.getData();
					/* fix water issues at sealevel */
					if ((y <= world.getSeaLevel()) &&
							((world.getBlockAt(x - 1, y, z).getType() == Material.STATIONARY_WATER) ||
								(world.getBlockAt(x + 1, y, z).getType() == Material.STATIONARY_WATER) ||
								(world.getBlockAt(x, y, z - 1).getType() == Material.STATIONARY_WATER) ||
								(world.getBlockAt(x, y, z + 1).getType() == Material.STATIONARY_WATER)))
						b.setType(Material.STATIONARY_WATER);
					else
						b.setType(Material.AIR);
					t.setType(mat);
					t.setData(dat);

					if (targetunderwater && !underwater) {
						snd = Sound.SPLASH;
					} else if (y - ty > 2) {
						snd = Sound.FALL_BIG;
					} else {
						switch(b.getType()) {
						case CLAY:
						case SAND:
							snd = Sound.DIG_SAND;
						case DIRT:
						case GRASS:
							snd = Sound.DIG_GRASS;
							break;
						case GRAVEL:
							snd = Sound.DIG_GRAVEL;
							break;
						case HARD_CLAY:
						case STAINED_CLAY:
						case COBBLESTONE:
						case STONE:
						case COAL_ORE:
						case IRON_ORE:
						case LAPIS_ORE:
						case EMERALD_ORE:
						case GOLD_ORE:
						case DIAMOND_ORE:
						case REDSTONE_ORE:
							snd = Sound.DIG_STONE;
							break;
						default:
							snd = Sound.FALL_SMALL;
							break;
						}
					}
					world.playSound(new Location(world, tx, ty, tz), snd, 1, 1);

					stat_displaced++;
					return;
				default:
					/* figure out how this happened */
					stat_errors++;
					getLogger().info("Attempted to move into a block of " + t.getType().name());
					return;
			}
		}

		/* degrade block? */

		// degrade should factor in elevation?

		//FIXME should detect the presence of ice near and factor in.

		/* do not decay sand further unless in a wet Biome, and under water */
		if (b.getType() == Material.SAND) {
			switch (b.getBiome()) {
				case RIVER:
				case OCEAN:
				case DEEP_OCEAN:
				case SWAMPLAND:
				case FROZEN_RIVER:
				case FROZEN_OCEAN:
					if (underwater)
						break;
				default:
					stat_ignored_sand++;
					return;
			}
		}

		/* For now, don't decay into sand for biomes that are mostly dirtish, unless under water */
		if (b.getType() == Material.DIRT){
			switch (b.getBiome()) {
				case OCEAN:
				case BEACH:
				case COLD_BEACH:
				case STONE_BEACH:
				case MUSHROOM_SHORE:
				case DESERT:
				case DESERT_HILLS:
				case FROZEN_OCEAN:
				case FROZEN_RIVER:
					break;
				default:
					if (underwater)
						break;
					stat_ignored_sand++;
					return;
			}
		}

		if (rnd.nextDouble() < hardness) {
			switch (b.getType()) {
				case DIRT:
					b.setType(Material.SAND);
					break;
				case SOIL:
				case GRASS:
					b.setType(Material.DIRT);
					break;
				case SAND:
					b.setType(Material.CLAY);
					break;
				case GRAVEL:
					b.setType(Material.DIRT);
					break;
				case CLAY:
					/* we can displace clay, but not degrade */
					return;
				case HARD_CLAY:
				case STAINED_CLAY:
				case SANDSTONE:
				case COBBLESTONE:
					b.setType(Material.GRAVEL);
					break;
				case STONE:
					b.setType(Material.COBBLESTONE);
					break;
				case COAL_ORE:
				case IRON_ORE:
				case LAPIS_ORE:
				case EMERALD_ORE:
				case GOLD_ORE:
				case DIAMOND_ORE:
				case REDSTONE_ORE:
					b.setType(Material.STONE);
					break;
				default:
					stat_errors++;
					return;
			}
		} else {
			stat_ignored_hardness++;
			return;
		}

		stat_degraded++;
	};

	private class SedimentologyRunnable implements Runnable {
		public void run() {
			for (sedWorld sedWorldItem: sedWorldList) {
				for (int j = 0; j < conf_blocks; j++)
					sed(sedWorldItem);
			}
		}
	}

	class SedimentologyCommand implements CommandExecutor {
		public boolean onCommand(CommandSender sender, Command command, String label, String[] split) {
			String msg = "unknown command";
			String helpmsg = "\n" + 
					"/sedimentology help - display this help message\n" +
					"/sedimentology stats - display statistics\n" +
					"/sedimentology list - display enabled worlds\n" +
					"/sedimentology blocks <int> - sed number of block attempts per cycle\n" +
					"/sedimentology protect <bool> - use Factions or Towny protection\n" +
					"/sedimentology enable <world> - enable for world\n" +
					"/sedimentology disable <world> - enable for world";

			if (split.length >= 1) {
				switch (split[0]) {
					case "blocks":
						if (split.length == 2) {
							conf_blocks = Long.parseLong(split[1]);
							getConfig().set("blocks", conf_blocks);
							saveConfig();
						}
						msg = "number of blocks set to " + conf_blocks;
						break;
					case "protect":
						if (split.length == 2) {
							switch (split[1].toLowerCase()) {
								case "true":
								case "on":
								case "yes":
								case "1":
									conf_protect = true;
									break;
								case "false":
								case "off":
								case "no":
								case "0":
									conf_protect = false;
									break;
								default:
									break;
							}
							getConfig().set("protect", conf_protect);
							saveConfig();
						}
						msg = "protection is set to " + (conf_protect ? "true" : "false");
						break;
					case "list":
						msg = "plugin enabled for worlds:\n";
						for (sedWorld s: sedWorldList) {
							msg += "- " + s.world.getName() + "\n";
						}
						break;
					case "enable":
						if (split.length != 2) {
							msg = "enable requires a world name";
							break;
						};
						if (!enableWorld(split[1]))
							msg = "unable to enable for world \"" + split[1] + "\"";
						else
							msg = "enabled for world \"" + split[1] + "\"";
						break;
					case "disable":
						if (split.length != 2) {
							msg = "disable requires a world name";
							break;
						};
						if (!disableWorld(split[1]))
							msg = "unable to disable for world \"" + split[1] + "\"";
						else
							msg = "disabled for world \"" + split[1] + "\"";
						break;
					case "stats":
						World world = org.bukkit.Bukkit.getWorld("world");
						Chunk ChunkList[] = world.getLoadedChunks();
						msg = String.format("blocks: %d protect: %s\n" +
									"considered %d, displaced %d, degraded %d blocks in %d chunks %d errors\nlast one at %d %d %d\n" +
									"ignored: edge %d, type %d, storm %d, vegetation %d, resistance %d, water %d, sand %d, hardness %d, protected %d",
									conf_blocks, conf_protect ? "true" : "false",
									stat_considered, stat_displaced, stat_degraded, ChunkList.length, stat_errors,
									x, y, z,
									stat_ignored_edge, stat_ignored_type, stat_ignored_storm, stat_ignored_vegetation,
									stat_ignored_resistance, stat_ignored_water, stat_ignored_sand, stat_ignored_hardness,
									stat_protected);
						break;
					case "help":
					default:
						msg = helpmsg;
						break;
				}
			} else {
				msg = helpmsg;
			}

			if (!(sender instanceof Player)) {
				getLogger().info(msg);
			} else {
				Player player = (Player) sender;
				player.sendMessage(msg);
			}
			return true;
		}
	}

	class SedimentologyListener implements Listener {
		@EventHandler
		public void onChunkLoadEvent(ChunkLoadEvent event) {
			World w = event.getWorld();
			for (sedWorld ww: sedWorldList) {
				if (ww.world.equals(w)) {
					Chunk c = event.getChunk();
					int x = c.getX();
					int z = c.getZ();
					ChunkMapLoad(ww.chunkMap, x, z);
				}
			}
		}

		@EventHandler
		public void onChunkUnloadEvent(ChunkUnloadEvent event) {
			World w = event.getWorld();
			for (sedWorld ww: sedWorldList) {
				if (ww.world.equals(w)) {
					Chunk c = event.getChunk();
					int x = c.getX();
					int z = c.getZ();
					ChunkMapUnload(ww.chunkMap, x, z);
				}
			}
		}
	}

	public boolean enableWorld(String worldName)
	{
		getLogger().info("enabling world \"" + worldName + "\"");

		if (org.bukkit.Bukkit.getWorld(worldName) == null) {
			getLogger().info("No such world");
			return false;
		}
		if (!sedWorldList.isEmpty()) {
			for (sedWorld ww: sedWorldList) {
				if (org.bukkit.Bukkit.getWorld(worldName).getName().equals(ww.world.getName())) {
					getLogger().info("Already enabled for this world");
					return false;
				}
			}
		}
		/* nether/end world environments are not supported */
		if (org.bukkit.Bukkit.getWorld(worldName).getEnvironment() != Environment.NORMAL) {
			getLogger().info("Invalid environment for this world");
			return false;
		}

		List<String> worldStringList = getConfig().getStringList("worlds");
		if (worldStringList.indexOf(worldName) == -1) {
			worldStringList.add(worldName);
			getConfig().set("worlds", worldStringList);
			saveConfig();
		}

		sedWorld s = new sedWorld();
		s.world = org.bukkit.Bukkit.getWorld(worldName);
		s.chunkMap = new HashMap<String, Integer>();

		/* fill initial chunkmap here */
		Chunk chunkList[] = s.world.getLoadedChunks();
		for (Chunk c: chunkList) {
			int x = c.getX();
			int z = c.getZ();
			ChunkMapLoad(s.chunkMap, x, z);
		}

		sedWorldList.add(s);

		return true;
	}

	public boolean disableWorld(String worldName)
	{
		if (org.bukkit.Bukkit.getWorld(worldName) == null) {
			getLogger().info("No such world");
			return false;
		}
		for (sedWorld ww: sedWorldList) {
			if (org.bukkit.Bukkit.getWorld(worldName).getName().equals(ww.world.getName())) {
				List<String> worldStringList = getConfig().getStringList("worlds");
				worldStringList.remove(worldName);
				getConfig().set("worlds", worldStringList);
				saveConfig();

				sedWorldList.remove(ww);

				return true;
			}
		}

		getLogger().info("world not currently enabled");

		return false;
	}

	private int walker_start;
	private int walker_step;
	private int walker_phase;
	private offset walker[];

	public offset walker_f(offset o) {
		/*
		 * attempt to walk a grid as follows:
		 * a(4)-b(4)-c(4)-d(8)-e(4)
		 *    edcde
		 *    dbabd
		 *    caOac
		 *    dbabd
		 *    edcde
		 */

		if (o.x == 0 && o.z == 0)
			walker_step = 0;

		switch (walker_step) {
			case 0:
			case 4:
			case 8:
			case 20:
				walker_start = (int)Math.round(rnd.nextDouble() * 4.0);
				walker_phase = 4;
				break;
			case 12:
				walker_start = (int)Math.round(rnd.nextDouble() * 8.0);
				walker_phase = 8;
				break;
			default:
				break;
		}

		int section_start = walker_step - (walker_step % walker_phase);
		int section_part = ((walker_step - section_start) + walker_start) % walker_phase;

		walker_step++;

		return walker[section_start + section_part];
	}

	public void onEnable() {
		sedWorldList = new ArrayList<sedWorld>();

		/* these are used to displace blocks */
		walker = new offset[24];

		walker[0] = new offset(0,1);
		walker[1] = new offset(1,0);
		walker[2] = new offset(0,-1);
		walker[3] = new offset(-1,0);

		walker[4] = new offset(1,1);
		walker[5] = new offset(1,-1);
		walker[6] = new offset(-1,1);
		walker[7] = new offset(-1,-1);

		walker[8] = new offset(2,0);
		walker[9] = new offset(-2,0);
		walker[10] = new offset(0,2);
		walker[11] = new offset(0,-2);

		walker[12] = new offset(2,1);
		walker[13] = new offset(2,-1);
		walker[14] = new offset(1,2);
		walker[15] = new offset(1,-2);
		walker[16] = new offset(-1,2);
		walker[17] = new offset(-1,-2);
		walker[18] = new offset(-2,1);
		walker[19] = new offset(-2,-1);

		walker[20] = new offset(2,2);
		walker[21] = new offset(-2,2);
		walker[22] = new offset(2,-2);
		walker[23] = new offset(-2,-2);

		/* config data handling */
		saveDefaultConfig();

		conf_blocks = getConfig().getInt("blocks");
		conf_protect = getConfig().getBoolean("protect");

		List<String> worldStringList = getConfig().getStringList("worlds");

		/* populate chunk cache for each world */
		for (int i = 0; i < worldStringList.size(); i++)
			enableWorld(worldStringList.get(i));

		/* Detect Factions */
		if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("Factions"))
			have_factions = true;
		getLogger().info("Factions support is " + (have_factions ? "enabled" : "disabled"));
		if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("Towny")) {
			Plugin p = org.bukkit.Bukkit.getPluginManager().getPlugin("Towny");
			if (p != null) {
				towny_universe = ((Towny)(p)).getTownyUniverse();
				have_towny = true;
			}
		}
		getLogger().info("Towny support is " + (have_towny ? "enabled" : "disabled"));

		/* even handler takes care of updating it from there */
		getServer().getPluginManager().registerEvents(new SedimentologyListener(), this);

		getCommand("sedimentology").setExecutor(new SedimentologyCommand());

		BukkitScheduler scheduler = Bukkit.getServer().getScheduler();
		scheduler.scheduleSyncRepeatingTask(this, new SedimentologyRunnable(), 1L, 1L);
	}
}

