package org.foo_projects.sofar.Sedimentology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Map;

import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.scheduler.BukkitScheduler;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

import com.massivecraft.factions.entity.BoardColls;
import com.massivecraft.factions.entity.Faction;
import com.massivecraft.mcore.ps.PS;

import com.palmergames.bukkit.towny.object.TownyUniverse;

import com.sk89q.worldguard.bukkit.WGBukkit;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;

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
 * Thicker snow packs behave like ice: crunch rocks into gravel
 *
 * Blocks do not disappear, the volume of blocks remains equal througout the process
 *
 * slope affects erosion - a higher slope means more erosion
 *
 * deposition happens where the slope reduces - a lower slope means more deposition
 *
 */

public final class Sedimentology extends JavaPlugin {
	private List<SedWorld> sedWorldList;
	public Random rnd = new Random();

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
	private long stat_ignored_wave;
	private long stat_ignored_sand;
	private long stat_ignored_hardness;
	private long stat_ignored_lockedin;
	private long stat_ignored_rate;
	private int stat_lastx;
	private int stat_lasty;
	private int stat_lastz;

	private enum sedType {
		SED_BLOCK,
		SED_SNOW;
	}

	private long conf_blocks = 10;
	private long conf_snowblocks = 100;
	private long conf_ticks = 1;
	private boolean conf_protect = true;
	private boolean conf_compensate = true;

	private boolean have_factions = false;
	private boolean have_factions_old = false;
	private boolean have_towny = false;
	private boolean have_worldguard = false;
	private boolean have_residence = false;

	@SuppressWarnings("deprecation")
	private byte getData(Block block)
	{
		return block.getData();
	}

	@SuppressWarnings("deprecation")
	private void setData(Block block, byte data)
	{
		block.setData(data);
	}

	private boolean enableWorld(String worldName)
	{
		getLogger().info("enabling world \"" + worldName + "\"");

		if (org.bukkit.Bukkit.getWorld(worldName) == null) {
			getLogger().info("No such world");
			return false;
		}
		if (!sedWorldList.isEmpty()) {
			for (SedWorld ww: sedWorldList) {
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

		World w = org.bukkit.Bukkit.getWorld(worldName);
		SedWorld s = new SedWorld(w);

		/* fill initial chunkmap here */
		Chunk chunkList[] = w.getLoadedChunks();
		for (Chunk c: chunkList)
			s.load(c.getX(), c.getZ());

		sedWorldList.add(s);

		return true;
	}

	private boolean disableWorld(String worldName)
	{
		if (org.bukkit.Bukkit.getWorld(worldName) == null) {
			getLogger().info("No such world");
			return false;
		}
		for (SedWorld ww: sedWorldList) {
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

	private class offset {
		public offset(int i, int j) {
			x = i;
			z = j;
		}
		public int x;
		public int z;
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

	private class SedDice {
		private Random r;

		public SedDice(Random random) {
			this.r = random;
		}

		public boolean roll(double bet) {
			return (r.nextDouble() > bet);
		}
	}

	private class SedBlock {
		private Block block;

		public SedBlock(Block b) {
			block = b;
		}

		/* arid envirnments where no precipitation happens */
		public boolean inAridBiome() {
			switch (block.getBiome()) {
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
					return true;
				default:
					return false;
			}
		}

		/* environments where clay is naturally found */
		public boolean inClayBiome() {
			switch (block.getBiome()) {
				case RIVER:
				case OCEAN:
				case DEEP_OCEAN:
				case SWAMPLAND:
				case FROZEN_RIVER:
				case FROZEN_OCEAN:
					return true;
				default:
					return false;
			}
		}

		/* environments where sand is naturally found */
		public boolean inSandBiome() {
			switch (block.getBiome()) {
				case OCEAN:
				case BEACH:
				case COLD_BEACH:
				case STONE_BEACH:
				case MUSHROOM_SHORE:
				case DESERT:
				case DESERT_HILLS:
				case FROZEN_OCEAN:
				case FROZEN_RIVER:
					return true;
				default:
					return false;
			}
		}

		public boolean isProtected() {
			if (!conf_protect)
				return false;

			if (have_factions_old) {
				com.massivecraft.factions.Faction faction = com.massivecraft.factions.Board.getFactionAt(new com.massivecraft.factions.FLocation(block));
				if (!faction.isNone())
					return true;
			}
			if (have_factions) {
				Faction faction = BoardColls.get().getFactionAt(PS.valueOf(block.getLocation()));
				if (!faction.isNone())
					return true;
			}
			if (have_towny) {
				if (TownyUniverse.getTownBlock(block.getLocation()) != null)
					return true;
			}
			if (have_worldguard) {
				RegionManager rm = WGBukkit.getRegionManager(block.getWorld());
				if (rm == null)
					return false;
				ApplicableRegionSet set = rm.getApplicableRegions(block.getLocation());
				return (set.size() > 0);
			}
			if (have_residence) {
				ClaimedResidence res = Residence.getResidenceManager().getByLoc(block.getLocation());
				if (res != null)
					return true;
			}

			return false;
		}

		public Material getType() {
			return block.getType();
		}

		public void setType(Material material) {
			block.setType(material);
		}

		public byte getData() {
			return Sedimentology.this.getData(block);
		}

		public void setData(byte data) {
			Sedimentology.this.setData(block, data);
		}

		public Biome getBiome() {
			return block.getBiome();
		}

	}

	private class SedWorld {
		private Map<String, Integer> chunkMap;
		private World world;

		public SedWorld(World w) {
			this.chunkMap = new HashMap<String, Integer>();
			this.world = w;
		}

		public boolean isLoaded(int x, int z) {
			String key = "(" + x + "," + z + ")";
			if (chunkMap.get(key) == null)
				return false;
			return chunkMap.get(key) == 1;
		}

		public void load(int x, int z) {
			String key = "(" + x + "," + z + ")";
			chunkMap.put(key, 1);
		}

		public void unload(int x, int z) {
			String key = "(" + x + "," + z + ")";
			chunkMap.put(key, 0);
		}

		public void sedRandomBlock(sedType type) {
			Chunk ChunkList[] = world.getLoadedChunks();
			if (ChunkList.length == 0)
				return;
			Chunk c = ChunkList[(int) Math.abs(rnd.nextDouble() * ChunkList.length)];

			int bx = (int)(Math.round(rnd.nextDouble() * 16));
			int bz = (int)(Math.round(rnd.nextDouble() * 16));

			/* don't bother touching chunks at the edges */
			for (int xx = c.getX() - 1 ; xx <= c.getX() + 1; xx++) {
				for (int zz = c.getZ() - 1 ; zz <= c.getZ() + 1; zz++) {
					if (!this.isLoaded(xx,  zz)) {
						stat_ignored_edge++;
						return;
					}
				}
			}

			int x = bx + (c.getX() * 16);
			int z = bz + (c.getZ() * 16);

			switch(type) {
			case SED_BLOCK:
				sedBlock(x, z);
				break;
			case SED_SNOW:
				sedSnowBlock(x, z);
				break;
			default:
				return;
			}
			
		}

		private int snowdepth(int x, int y, int z)
		{
			int stackheight = 1;
			Block bottom = world.getBlockAt(x, y, z);
			Block top = bottom;

			while (true) {
				if (top.getRelative(BlockFace.UP).getType() != Material.SNOW)
					break;

				top = top.getRelative(BlockFace.UP);
				stackheight++;
			}

			return stackheight;
		}

		private int snow(int x, int y, int z) {
			byte snowcount = 0;
			int snowheight = 0;
			int stackheight;
			Block bottom = world.getBlockAt(x, y, z);

			stackheight = snowdepth(x, y, z);

			Block top = world.getBlockAt(x, y + stackheight - 1, z);

			/* scan area for snow depth to even out snow height */
			for (int xx = x - 1; xx <= x + 1; xx++) {
				for (int zz = z - 1; zz <= z + 1; zz++) {
					if ((xx == x) && (zz == z))
						continue;
stack:
					for (int yy = top.getY() + 2; yy >= y - 2; yy--) {
						if (world.getBlockAt(xx, yy, zz).getType() == Material.SNOW) {
							snowcount++;
							snowheight += getData(world.getBlockAt(xx, yy, zz)) + (yy * 8) + 1;
							break stack;
						}
					}
				}
			}

			/* thaw ? */
			if (!world.hasStorm()) {
				if (top.getLightLevel() >= 12) {
					/* Snow melt is currently slower than snow accumulation, but melt happens more than
					 * snow accumulation. This means that for default setting, you're most likely will see
					 * snow mostly accumulate to near the max snow levels. However, increasing snowblocks
					 * higher will result in the max snow height being reached quicker, and subsequently snow
					 * decaying faster than it grows, causing snow to melt more.
					 * 
					 * Bottom line - if you want lots of snow, set snowblocks low (10 or lower), but if you
					 * prefer snow to come and go fast, set snowblocks high (50-100).
					 */
					if ((top.getY() != bottom.getY()) || (getData(top) > 0)) {
						/* smooth snow melt */
						if (snowcount > 0) {
							int avg = (snowheight / snowcount);
							if ((((top.getY() * 8) + getData(top) + 1) > avg - 1) || (Math.random() > 0.9)) {
								if (getData(top) > 0)
									setData(top, (byte)(getData(top) - 1));
								else
									top.setType(Material.AIR);
							}
						} else {
							setData(top, (byte)(getData(top) - 1));
						}
					} else {
						/* remove snow only at the edges */
						if ((snowcount < 6) && (top.getY() == bottom.getY()))
							top.setType(Material.AIR);
					}
				}
				return stackheight;
			}

			/* cold enough for snow accumulation? */
			if (bottom.getTemperature() > 0.25)
				return stackheight;

			/* don't stack snow on leaves and plants */
			if (isCrushable(bottom.getRelative(BlockFace.DOWN)))
				return stackheight;


			/* don't grow snow if there's no neighbour blocks with snow */
			if (snowcount == 0)
				return stackheight;

			/* snow stack? */
			long maxstackheight = Math.max((y - 64) / 16, Math.round((0.25 - bottom.getTemperature()) / 0.9));

			/* grow, but must be completely surrounded by snow blocks */
			if ((getData(top) == 7) && (snowcount == 8) && (stackheight < maxstackheight)) {
				Block above = top.getRelative(BlockFace.UP);
				above.setType(Material.SNOW);
				setData(above, (byte)0);
			} else {
				/* if neighbours do not have snow, don't stack so high */
				int avg = (snowheight / snowcount);
				if ((((top.getY() * 8) + getData(top) + 1) < avg + 1) || (Math.random() > 0.9))
					setData(top, (byte)Math.min((int)getData(top) + 1, ((snowcount > 0) ? snowcount - 1 : 0)));
			}

			return stackheight;
		}

		private void sedSnowBlock(int x, int z)
		{
			World world = this.world;
			int y;

			y = world.getHighestBlockYAt(x, z);
			switch (world.getBlockAt(x, y, z).getType()) {
				case SNOW:
					snow(x, y, z);
					break;
				default:
					break;
			}
		}

		public void sedBlock(int x, int z) {
			World world = this.world;
			SedDice dice = new SedDice(rnd);

			int y;

			int snowdepth = 0;

			double hardness;
			double resistance;
			double waterfactor;
			double vegetationfactor;
			double stormfactor;

			boolean underwater = false;
			boolean undermovingwater = false;
			boolean targetunderwater = false;
			boolean undersnow = false;

			stat_considered++;

			/* handle snow separately first */
			y = world.getHighestBlockYAt(x, z);
			switch (world.getBlockAt(x, y, z).getType()) {
				case SNOW:
					snowdepth = snowdepth(x, y, z);
					undersnow = true;
					break;
				default:
					break;
			}

			/* find highest block, even if underwater */
			y = world.getHighestBlockYAt(x, z) - 1;
			switch (world.getBlockAt(x, y, z).getType()) {
				case WATER:
					undermovingwater = true;
				case STATIONARY_WATER:
					underwater = true;
					y = findDepositLocation(x, y, z) - 1;
					break;
				case SNOW:
					y = findDepositLocation(x, y, z) - 1;
					break;
				default:
					break;
			}

			SedBlock b = new SedBlock(world.getBlockAt(x, y, z));

			if (b.isProtected()) {
				stat_protected++;
				return;
			}

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
				case MOSSY_COBBLESTONE:
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

			/*
			 * if under a significant snow cover, lower hardness drastically.
			 * 
			 * There's no need to lower resistance, since that is the obvious result
			 * of lowering the hardness as the resulting block will be easier to move.
			 */
			if ((undersnow) && (snowdepth >= 2))
				hardness = Math.pow(hardness, 1 / (snowdepth - 0.5));

			/* lower overall chance due to lack of water */
			stormfactor = 0.1;
			if (world.hasStorm()) {
				if (!b.inAridBiome())
					stormfactor = 1.0;
			}

			if ((!underwater) && (dice.roll(stormfactor))) {
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

			if (dice.roll(waterfactor)) {
				stat_ignored_water++;
				return;
			}

			/* slow down when deeper under the sealevel */
			if (underwater) {
				if (y < world.getSeaLevel()) {
					/* exponentially slower with depth. 100% at 1 depth, 50% at 2, 25% at 3 etc... */
					if (dice.roll(2.0 * Math.pow(0.5, world.getSeaLevel() - y))) {
						stat_ignored_wave++;
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

			if (dice.roll(vegetationfactor)) {
				stat_ignored_vegetation++;
				return;
			}

			/* displace block? */
displace:
			if (true) {
				int step, steps;

				/* our block must be able to move sideways, otherwise it could leave
				 * strange gaps. So check if all 4 sides horizontally are solid
				 */
				if (!(world.getBlockAt(x + 1, y, z).isEmpty() || world.getBlockAt(x + 1, y, z).isLiquid() ||
						world.getBlockAt(x - 1, y, z).isEmpty() || world.getBlockAt(x - 1, y, z).isLiquid() ||
						world.getBlockAt(x, y, z + 1).isEmpty() || world.getBlockAt(x, y, z + 1).isLiquid() ||
						world.getBlockAt(x, y, z - 1).isEmpty() || world.getBlockAt(x, y, z - 1).isLiquid())) {
					stat_ignored_lockedin++;
					break displace;
				}

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
					int h = findDepositLocation(x + o.x, lowest, z + o.z);

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

				SedBlock t = new SedBlock(world.getBlockAt(tx, ty, tz));

				if (t.isProtected()) {
						stat_protected++;
						return;
				}

				/* a snow cover slows down displacement */
				if (undersnow) {
					if (dice.roll(0.25)) {
						stat_ignored_water++;
						return;
					}
				}

				/* roll to move it */
				if (dice.roll(resistance)) {
					stat_ignored_resistance++;
					return;
				}

				/* It's time to move it, move it. */
				if (isCrushable(t.block) || t.block.isLiquid() || t.block.isEmpty()) {
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
								(world.getBlockAt(x, y, z + 1).getType() == Material.STATIONARY_WATER)) &&
							((world.getBlockAt(x - 1, y, z).getType() != Material.AIR) &&
								(world.getBlockAt(x + 1, y, z).getType() != Material.AIR) &&
								(world.getBlockAt(x, y, z - 1).getType() != Material.AIR) &&
								(world.getBlockAt(x, y, z + 1).getType() != Material.AIR)))
						b.setType(Material.STATIONARY_WATER);
					else
						b.setType(Material.AIR);
					b.setData((byte)0);
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

					/* fix waterfall stuff above sealevel */
					if (b.block.getRelative(BlockFace.UP).isLiquid()) {
						Block u = b.block.getRelative(BlockFace.UP);
						while (u.isLiquid() && u.getRelative(BlockFace.UP).isLiquid())
							u = u.getRelative(BlockFace.UP);
						if (u.getY() > world.getSeaLevel() && (getData(u) != 0)) {
							u.setType(Material.AIR);
							while (u.getRelative(BlockFace.DOWN).getY() != b.block.getY() && u.getY() > world.getSeaLevel()){
								u = u.getRelative(BlockFace.DOWN);
								u.setType(Material.AIR);
							}
						}
					}

					return;
				} else {
					/* figure out how this happened */
					stat_errors++;
					getLogger().info("Attempted to move into a block of " + t.getType().name());
					return;
				}
			}

			/* degrade block? */

			// degrade should factor in elevation?

			/*
			 * compensate for speed - this prevents at high block rates that
			 * everything just turns into a mudbath - We do want the occasional
			 * mudbath to appear, but only after rain or by chance, not always 
			 */
			if (conf_compensate && (conf_blocks > 10) && (b.getType() == Material.GRASS)) {
				if (dice.roll(10.0 / conf_blocks)) {
					stat_ignored_rate++;
					return;
				}
			}

			/* do not decay gravel (and dirt, sand, etc.) further if under snow */
			if ((b.getType() == Material.GRAVEL) && (undersnow)) {
				stat_ignored_sand++;
				return;
			}

			/* do not decay sand further unless in a wet Biome, and under water, and under sealevel */
			if (b.getType() == Material.SAND) {
				if (!(b.inClayBiome() && underwater && b.block.getY() < world.getSeaLevel())) {
					stat_ignored_sand++;
					return;
				}
			}

			/* For now, don't decay into sand for biomes that are mostly dirtish, unless under water */
			if (b.getType() == Material.DIRT){
				if (!(b.inSandBiome() || underwater)) {
					stat_ignored_sand++;
					return;
				}
			}

			if (dice.roll(hardness)) {
				stat_ignored_hardness++;
				return;
			}

			switch (b.getType()) {
				case DIRT:
					b.setType(Material.SAND);
					b.setData((byte)0);
					break;
				case SOIL:
				case GRASS:
					b.setType(Material.DIRT);
					break;
				case SAND:
					b.setType(Material.CLAY);
					b.setData((byte)0);
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
				case MOSSY_COBBLESTONE:
				case COBBLESTONE:
					b.setType(Material.GRAVEL);
					b.setData((byte)0);
					break;
				case STONE:
					switch (b.getBiome()) {
						case MEGA_TAIGA:
						case MEGA_TAIGA_HILLS:
						case MEGA_SPRUCE_TAIGA:
						case MEGA_SPRUCE_TAIGA_HILLS:
							b.setType(Material.MOSSY_COBBLESTONE);
							break;
						default:
							b.setType(Material.COBBLESTONE);
							break; /* breaks inner switch only */
					}
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

			stat_lastx = x;
			stat_lasty = y;
			stat_lastz = z;
			stat_degraded++;
		}

		/* helper function to find lowest deposit elevation ignoring water */
		private int findDepositLocation(int x, int y, int z) {
			int yy = y;
			while (true) {
				Block b = world.getBlockAt(x, yy, z);
				if (isCrushable(b) || b.isLiquid() || b.isEmpty()) {
					yy--;
					if (yy == 0)
						return yy;
				} else {
					return yy + 1;
				}
			}
		}

		private boolean isCrushable(Block b) {
			switch (b.getType()) {
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
					return true;
				default:
					return false;
			}
		}
	}

	private class SedimentologyRunnable implements Runnable {
		public void run() {
			for (SedWorld sedWorld: sedWorldList) {
				for (int j = 0; j < conf_snowblocks; j++)
					sedWorld.sedRandomBlock(sedType.SED_SNOW);
				for (int j = 0; j < conf_blocks; j++)
					sedWorld.sedRandomBlock(sedType.SED_BLOCK);
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
					"/sedimentology snowblocks <int> - sed number of snowblock attempts per cycle\n" +
					"/sedimentology enable <world> - enable for world\n" +
					"/sedimentology disable <world> - enable for world";

command:
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
					case "snowblocks":
						if (split.length == 2) {
							conf_snowblocks = Long.parseLong(split[1]);
							getConfig().set("snowblocks", conf_snowblocks);
							saveConfig();
						}
						msg = "number of snowblocks set to " + conf_snowblocks;
						break;
					case "list":
						msg = "plugin enabled for worlds:\n";
						for (SedWorld s: sedWorldList) {
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
						long chunks = 0;
						for (SedWorld sw: sedWorldList)
							chunks += sw.world.getLoadedChunks().length;
						msg = String.format("blocks: %d snowblocks: %d ticks: %d protect: %s\n" +
									"considered %d, displaced %d, degraded %d blocks in %d chunks %d errors\nlast one at %d %d %d\n" +
									"ignored: edge %d, type %d, storm %d, vegetation %d, resistance %d, water %d, wave %d, sand %d, hardness %d, " +
									"protected %d, locked in %d, rate %d",
									conf_blocks, conf_snowblocks, conf_ticks, conf_protect ? "true" : "false",
									stat_considered, stat_displaced, stat_degraded, chunks, stat_errors,
									stat_lastx, stat_lasty, stat_lastz,
									stat_ignored_edge, stat_ignored_type, stat_ignored_storm, stat_ignored_vegetation,
									stat_ignored_resistance, stat_ignored_water, stat_ignored_wave, stat_ignored_sand, stat_ignored_hardness,
									stat_protected, stat_ignored_lockedin, stat_ignored_rate);
						break;
					case "test":
						if (split.length != 7) {
							msg = "test requires 6 parameters: world x1 z1 x2 z2 blocks";
							break;
						};
						for (SedWorld sw: sedWorldList) {
							if (sw.world.getName().equals(split[1])) {
								int x1 = Integer.parseInt(split[2]);
								int z1 = Integer.parseInt(split[3]);
								int x2 = Integer.parseInt(split[4]);
								int z2 = Integer.parseInt(split[5]);
								if (x1 > x2) {
									int t = x1;
									x1 = x2;
									x2 = t;
								}
								if (z1 > z2) {
									int t = z1;
									z1 = z2;
									z2 = t;
								}
								for (long i = 0; i < Long.parseLong(split[6]); i++)
									for (int x = x1; x <= x2; x++)
										for (int z = z1; z <= z2; z++)
											sw.sedBlock(x, z);
								msg = "test cycle finished";
								break command;
							}
						}
						msg = "Invalid world name - world must be enabled already";
						break;
					case "snowtest":
						if (split.length != 7) {
							msg = "snowtest requires 6 parameters: world x1 z1 x2 z2 blocks";
							break;
						};
						for (SedWorld sw: sedWorldList) {
							if (sw.world.getName().equals(split[1])) {
								int x1 = Integer.parseInt(split[2]);
								int z1 = Integer.parseInt(split[3]);
								int x2 = Integer.parseInt(split[4]);
								int z2 = Integer.parseInt(split[5]);
								if (x1 > x2) {
									int t = x1;
									x1 = x2;
									x2 = t;
								}
								if (z1 > z2) {
									int t = z1;
									z1 = z2;
									z2 = t;
								}
								for (long i = 0; i < Long.parseLong(split[6]); i++)
									for (int x = x1; x <= x2; x++)
										for (int z = z1; z <= z2; z++)
											sw.sedSnowBlock(x, z);
								msg = "test cycle finished";
								break command;
							}
						}
						msg = "Invalid world name - world must be enabled already";
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
			for (SedWorld ww: sedWorldList) {
				if (ww.world.equals(w)) {
					Chunk c = event.getChunk();
					ww.load(c.getX(),  c.getZ());
				}
			}
		}

		@EventHandler
		public void onChunkUnloadEvent(ChunkUnloadEvent event) {
			World w = event.getWorld();
			for (SedWorld ww: sedWorldList) {
				if (ww.world.equals(w)) {
					Chunk c = event.getChunk();
					ww.unload(c.getX(),  c.getZ());
				}
			}
		}
	}

	public void onEnable() {
		sedWorldList = new ArrayList<SedWorld>();

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
		conf_snowblocks = getConfig().getInt("snowblocks");
		conf_ticks = getConfig().getInt("ticks");
		getLogger().info("blocks: " + conf_blocks + " snowblocks: " + conf_snowblocks + " ticks: " + conf_ticks);

		List<String> worldStringList = getConfig().getStringList("worlds");

		/* populate chunk cache for each world */
		for (int i = 0; i < worldStringList.size(); i++)
			enableWorld(worldStringList.get(i));

		/* Detect Factions */
		if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("Factions")) {
			try {
				/* this is an old API thing */
				new com.massivecraft.factions.FLocation();
			} catch (NoClassDefFoundError e) {
				have_factions = true;
			}
			if (!have_factions)
				have_factions_old = true;
		}

		/* Towny */
		if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("Towny"))
			have_towny = true;

		/* WorldGuard */
		if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("WorldGuard"))
			have_worldguard = true;
		
		if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("Residence"))
				have_residence = true;

		getLogger().info("Protection plugins: " +
						(have_factions | have_factions_old ? "+" : "-") + "Factions, " +
						(have_towny ? "+" : "-") + "Towny, " +
						(have_worldguard ? "+" : "-") + "WorldGuard, " +
						(have_residence ? "+" : "-") + "Residence"
						);

		conf_protect = getConfig().getBoolean("protect");
		getLogger().info("protection is " + (conf_protect ? "on" : "off"));

		/* even handler takes care of updating it from there */
		getServer().getPluginManager().registerEvents(new SedimentologyListener(), this);

		getCommand("sedimentology").setExecutor(new SedimentologyCommand());

		BukkitScheduler scheduler = Bukkit.getServer().getScheduler();
		scheduler.scheduleSyncRepeatingTask(this, new SedimentologyRunnable(), 1L, conf_ticks);
	}
}

