package org.foo_projects.sofar.Sedimentology;

import java.util.HashMap;
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
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;

import org.bukkit.scheduler.BukkitScheduler;


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
	private long stat_ignored_edge;
	private long stat_ignored_type;
	private long stat_ignored_storm;
	private long stat_ignored_vegetation;
	private long stat_ignored_resistance;
	private long stat_ignored_water;
	private long stat_ignored_sand;
	private long stat_ignored_hardness;
	private int x, y, z;
	private long conf_attempts = 100;

	private Random rnd = new Random();

	private class offset {
		public offset(int i, int j) {
			x = i;
			z = j;
		}
		public int x;
		public int z;
	};

	private offset[] walker;
	private offset[] walker2;

	/* our chunk map - acts as a cache map of loaded chunks and reduces
	 * time needed to avoid map edge ssues
	 */
	private Map<String, Integer> ChunkMap = new HashMap<String, Integer>();

	public boolean ChunkMapLoaded(int x, int z) {
		String key = "(" + x + "," + z + ")";
		if (ChunkMap.get(key) == null)
			return false;
		return ChunkMap.get(key) == 1;
	}

	public void ChunkMapLoad(int x, int z) {
		String key = "(" + x + "," + z + ")";
		ChunkMap.put(key, 1);
	}

	public void ChunkMapUnload(int x, int z) {
		String key = "(" + x + "," + z + ")";
		ChunkMap.put(key, 0);
	}

	/* helper function to find lowest deposit elevation ignoring water */
	private int findDepositLocation(World world, int x, int y, int z) {
		int yy = y;
		while (true) {
			switch (world.getBlockAt(x, yy, z).getType()) {
				case AIR:
				case WATER:
				case STATIONARY_WATER:
					yy--;
					if (yy == 0)
						return yy;
					break;
				default:
					return yy + 1;
			}
		}
	}

	public void sed(World world) {
		stat_considered++;
		Chunk ChunkList[] = world.getLoadedChunks();
		Chunk c = ChunkList[(int) Math.abs(rnd.nextDouble() * ChunkList.length)];

		double hardness;
		double resistance;
		double waterfactor;
		double vegetationfactor;
		double stormfactor;

		boolean underwater = false;
		boolean undermovingwater = false;

		int bx = (int)(Math.round(rnd.nextDouble() * 16));
		int bz = (int)(Math.round(rnd.nextDouble() * 16));

		/* don't bother touching chunks at the edges */
		for (int xx = c.getX() - 1 ; xx <= c.getX() + 1; xx++) {
			for (int zz = c.getZ() - 1 ; zz <= c.getZ() + 1; zz++) {
				if (ChunkMapLoaded(xx, zz) == false) {
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
			//FIXME: start handling ice/snow from here
			default:
				break;
		}

		Block b = world.getBlockAt(x, y, z);

		/* filter out blocks we don't erode right now */
		hardness = 1.0;
		resistance = 1.0;
		switch (b.getType()) {
			case SOIL:
			case DIRT:
			case GRASS:
			case SAND:
				break;
			case GRAVEL:
				hardness = 0.15;
				resistance = 0.75;
				break;
			case CLAY:
				resistance = 0.3;
				break;
			case SANDSTONE:
				hardness = 0.05;
				resistance = 0.05;
				break;
			case COBBLESTONE:
				hardness = 0.05;
				resistance = 0.05;
				break;
			case STONE:
				hardness = 0.01;
				resistance = 0.01;
				break;
			default:
				stat_ignored_type++;
				return;
		}

		/* lower overall chance due to lack of water */
		stormfactor = 0.1;
		if (world.hasStorm())
			stormfactor = 1.0;

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
			// displace should factor in elevation


			// find the most suitable target location to move this block to
			int start = (int)(rnd.nextDouble() * 8.0);
			int lowest = y - 1;
			int lowestIndex = -1;
			int i = 0;
			int tx = 0, ty = 0, tz = 0;

			while (true) {
				offset o = walker[(start+i) % 8];
				int h = findDepositLocation(world, x + o.x, lowest, z + o.z);
				if (h < lowest) {
					lowest = h;
					lowestIndex = (start+i) % 8;
				}

				/* break out, we've looked all around */
				if (i++ == 7)
					break;
			}

			if (lowestIndex != -1) {
				tx = x + walker[lowestIndex].x;
				ty = lowest;
				tz = z + walker[lowestIndex].z;
			}

			// sand can be displaced further
			if ((lowestIndex == -1) && (b.getType() == Material.SAND)) {
				start = (int)(rnd.nextDouble() * 16.0);
				lowest = y - 1;
				lowestIndex = -1;
				i = 0;
				while (true) {
					offset o = walker2[(start+i) % 16];
					int h = findDepositLocation(world, x + o.x, lowest, z + o.z);
					if (h < lowest) {
						lowest = h;
						lowestIndex = (start+i) % 16;
					}

					/* break out, we've looked all around */
					if (i++ == 15)
						break;
				}
				if (lowestIndex != -1) {
					tx = x + walker2[lowestIndex].x;
					ty = lowest;
					tz = z + walker2[lowestIndex].z;
				}
			}

			/* flat? */
			if (lowestIndex == -1) {
				break displace;
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
					t.setType(b.getType());
					b.setType(Material.AIR);
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

		// should detect the presence of water or ice near and factor in.

		/* do not decay sand further unless in a wet Biome */
		if (b.getType() == Material.SAND) {
			switch (b.getBiome()) {
				case RIVER:
				case OCEAN:
				case SWAMPLAND:
				case FROZEN_RIVER:
				case FROZEN_OCEAN:
					break;
				default:
					stat_ignored_sand++;
					return;
			}
		}

		/* For now, don't decay into sand for biomes that are mostly dirtish */
		if (b.getType() == Material.DIRT){
			switch (b.getBiome()) {
				case BEACH:
				case DESERT:
				case DESERT_HILLS:
				case FROZEN_OCEAN:
				case FROZEN_RIVER:
					break;
				default:
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
				case SANDSTONE:
				case COBBLESTONE:
					b.setType(Material.GRAVEL);
					break;
				case STONE:
					b.setType(Material.COBBLESTONE);
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
			for (int i = 0; i < conf_attempts; i++)
				sed(org.bukkit.Bukkit.getWorld("world"));
		};
	}

	class SedimentologyCommand implements CommandExecutor {
		public boolean onCommand(CommandSender sender, Command command, String label, String[] split) {
			if (split.length == 1) {
				conf_attempts = Long.parseLong(split[0]);
				return true;
			}

			World world = org.bukkit.Bukkit.getWorld("world");
			Chunk ChunkList[] = world.getLoadedChunks();
			String msg = String.format("Sedimentology: \n" +
						"considered %d, displaced %d, degraded %d blocks in %d chunks %d errors\nlast one at %d %d %d\n" +
						"ignored: edge %d, type %d, storm %d, vegetation %d, resistance %d, water %d, sand %d, hardness %d",
						stat_considered, stat_displaced, stat_degraded, ChunkList.length, stat_errors,
						x, y, z,
						stat_ignored_edge, stat_ignored_type, stat_ignored_storm, stat_ignored_vegetation,
						stat_ignored_resistance, stat_ignored_water, stat_ignored_sand, stat_ignored_hardness);

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
			Chunk c = event.getChunk();
			int x = c.getX();
			int z = c.getZ();
			ChunkMapLoad(x, z);
		}

		@EventHandler
		public void onChunkUnloadEvent(ChunkUnloadEvent event) {
			Chunk c = event.getChunk();
			int x = c.getX();
			int z = c.getZ();
			ChunkMapUnload(x, z);
		}
	}

	public void onEnable() {
		BukkitScheduler scheduler = Bukkit.getServer().getScheduler();

		getCommand("sedimentology").setExecutor(new SedimentologyCommand());

		/* fill initial chunkmap here */
		World world = org.bukkit.Bukkit.getWorld("world");
		Chunk ChunkList[] = world.getLoadedChunks();
		for (Chunk c: ChunkList) {
			int x = c.getX();
			int z = c.getZ();
			ChunkMapLoad(x, z);
		}

		/* even handler takes care of updating it from there */
		getServer().getPluginManager().registerEvents(new SedimentologyListener(), this);

		/* these are used to displace blocks */
		walker = new offset[8];
		walker[0] = new offset(-1,-1);
		walker[1] = new offset(0,-1);
		walker[2] = new offset(1,-1);
		walker[3] = new offset(1,0);
		walker[4] = new offset(1,1);
		walker[5] = new offset(0,1);
		walker[6] = new offset(-1,1);
		walker[7] = new offset(-1,0);

		walker2 = new offset[16];
		walker2[0] = new offset(-2,-2);
		walker2[1] = new offset(-1,-2);
		walker2[2] = new offset(0,-2);
		walker2[3] = new offset(1,-2);
		walker2[4] = new offset(2,-2);
		walker2[5] = new offset(2,-1);
		walker2[6] = new offset(2,0);
		walker2[7] = new offset(2,1);
		walker2[8] = new offset(2,2);
		walker2[9] = new offset(1,2);
		walker2[10] = new offset(0,2);
		walker2[11] = new offset(-1,2);
		walker2[12] = new offset(-2,2);
		walker2[13] = new offset(-2,1);
		walker2[14] = new offset(-2,0);
		walker2[15] = new offset(-2,-1);

		scheduler.scheduleSyncRepeatingTask(this, new SedimentologyRunnable(), 1L, 1L);
	}
}




