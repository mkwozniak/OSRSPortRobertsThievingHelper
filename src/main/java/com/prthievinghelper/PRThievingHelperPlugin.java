package com.prthievinghelper;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.*;
import net.runelite.client.ui.overlay.OverlayManager;
import java.util.*;

@Slf4j
@PluginDescriptor(
	name = "Port Roberts Thieving Helper"
)
public class PRThievingHelperPlugin extends Plugin
{
	public class ThievingStall
	{
		// static data
		public final int ID;
		public final WorldPoint Position;
		public final List<WorldPoint> WatchPoints;

		// states
		public boolean IsWatched = false;
		public boolean UnwatchedNotifier = false;
		public boolean WatchNotifier = false;
		public int WatchedTicksRemaining = 0;
		public int UnwatchedTicksRemaining = 0;
		public int ThievingTickCounter = 0;

		public ThievingStall(int id, WorldPoint position,
							 List<WorldPoint> watchPoints)
		{
			this.ID = id;
			this.WatchPoints = watchPoints;
			this.Position = position;
		}
	}

	@Inject
	private Client client;

	@Inject
	private PRThievingHelperConfig config;

	@Inject
	public OverlayManager overlayManager;

	@Inject
	private PRThievingHelperOverlay overlay;

	@Inject
	private Notifier notifier;

	public enum StallTypes {
		FUR, SILK, GEM, CANNON, FISH, ORE, SPICE, VEG, SILVER
	}

	public final Map<StallTypes, ThievingStall> STALLS = Map.of(
			StallTypes.FUR, new ThievingStall(58102,
					new WorldPoint(1870, 3292, 0),
					Arrays.asList(
							new WorldPoint(1869, 3292, 0),
							new WorldPoint(1869, 3293, 0),
							new WorldPoint(1869, 3294, 0)
					)),

			StallTypes.SILK, new ThievingStall(58101,
					new WorldPoint(1870, 3295, 0),
					Arrays.asList(
							new WorldPoint(1869, 3295, 0),
							new WorldPoint(1868, 3295, 0)
					)),

			StallTypes.GEM, new ThievingStall(58106,
					new WorldPoint(1869, 3289, 0),
					Arrays.asList(
							new WorldPoint(1869, 3290, 0),
							new WorldPoint(1869, 3291, 0)
					)),

			StallTypes.CANNON, new ThievingStall(58108,
					new WorldPoint(1867, 3296, 0),
					Arrays.asList(
							new WorldPoint(1867, 3295, 0),
							new WorldPoint(1866, 3295, 0)
					)),

			StallTypes.FISH, new ThievingStall(58103,
					new WorldPoint(1861, 3292, 0),
					Arrays.asList(
							new WorldPoint(1863, 3292, 0),
							new WorldPoint(1863, 3291, 0)
					)),

			StallTypes.ORE, new ThievingStall(58107,
					new WorldPoint(1861, 3295, 0),
					Arrays.asList(
							new WorldPoint(1863, 3294, 0),
							new WorldPoint(1863, 3293, 0)
					)),

			StallTypes.SPICE, new ThievingStall(58105,
					new WorldPoint(1863, 3289, 0),
					Arrays.asList(
							new WorldPoint(1864, 3290, 0),
							new WorldPoint(1865, 3290, 0)
					)),

			StallTypes.VEG, new ThievingStall(58100,
					new WorldPoint(1864, 3296, 0),
					Arrays.asList(
							new WorldPoint(1865, 3295, 0),
							new WorldPoint(1864, 3295, 0)
					)),

			StallTypes.SILVER, new ThievingStall(58104,
					new WorldPoint(1866, 3289, 0),
					Arrays.asList(
							new WorldPoint(1866, 3290, 0),
							new WorldPoint(1867, 3290, 0),
							new WorldPoint(1868, 3290, 0)
					))
	);

	private static final int GUARD_WATCH_DURATION = 10; // Guards watch for 10 ticks
	private static final int THIEVING_DURATION = 5; // Thieving takes 5 ticks
	private static final int SAFE_BUFFER_TICKS = 1; // Extra tick buffer

	private static final String GUARD_NAME = "Market Guard";
	private static final Set<Integer> GUARD_IDS = Set.of(
			14881, 14882, 14883
	);

	private static final Map<StallTypes, Integer> stallObjectIds = Map.of(
			StallTypes.FUR, 58102,
			StallTypes.SILK, 58101,
			StallTypes.GEM, 58106,
			StallTypes.CANNON, 58108,
			StallTypes.FISH, 58103,
			StallTypes.ORE, 58107,
			StallTypes.SPICE, 58105,
			StallTypes.VEG, 58100,
			StallTypes.SILVER, 58104
	);

	private static final int SOUND_ID_UNWATCHED = 8410;
	private static final int SOUND_ID_WATCHED = 3814;

	private final Map<Integer, TileObject> stallObjects = new HashMap<>();
	private static final List<NPC> GUARDS = new ArrayList<>();

	private float flashAlpha = 0f;

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(overlay);
		for (StallTypes stall : StallTypes.values()) {
			STALLS.get(stall).IsWatched = false;
			STALLS.get(stall).UnwatchedNotifier = false;
			STALLS.get(stall).WatchNotifier = false;
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		GUARDS.clear();
		stallObjects.clear();
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		checkForStallObject(event.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		stallObjects.remove(event.getGameObject().getId());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		checkForStallObject(event.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
		stallObjects.remove(event.getGroundObject().getId());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		checkForStallObject(event.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
		stallObjects.remove(event.getDecorativeObject().getId());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		checkForStallObject(event.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		stallObjects.remove(event.getWallObject().getId());
	}

	private void checkForStallObject(TileObject object)
	{
		if (object == null)
		{
			return;
		}
		
		int id = object.getId();
		// Check if this is one of our stall objects
		if (stallObjectIds.containsValue(id))
		{
			stallObjects.put(id, object);
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if(!isValidGuard(npc))
			return;

		if(GUARDS.contains(npc))
			return;

		GUARDS.add(npc);
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		if(!isValidGuard(npc))
			return;

        GUARDS.remove(npc);
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (flashAlpha > 0f)
		{
			flashAlpha -= (float) config.notifierFlashSpeed();
			if (flashAlpha < 0f)
			{
				flashAlpha = 0f;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		//System.out.println(client.getSelectedSceneTile().getWorldLocation());
		// Update guard watching status and track timing
		for (StallTypes stall : StallTypes.values())
		{
			boolean wasWatching = STALLS.get(stall).IsWatched;
			boolean isWatching = isAnyGuardAtPosition(STALLS.get(stall).WatchPoints);
			STALLS.get(stall).IsWatched = isWatching;

			// guard just left
			if(!isWatching && wasWatching)
			{
				STALLS.get(stall).ThievingTickCounter = 0;
			}
			// guard just arrived
			if (isWatching && !wasWatching)
			{
				STALLS.get(stall).WatchedTicksRemaining = GUARD_WATCH_DURATION;
				STALLS.get(stall).UnwatchedTicksRemaining = 0;
			}
			// guard is currently watching
			else if (isWatching && wasWatching)
			{
				int ticksRemaining = STALLS.get(stall).WatchedTicksRemaining;
				if (ticksRemaining > 0)
				{
					STALLS.get(stall).WatchedTicksRemaining = ticksRemaining - 1;
				}
				if(ticksRemaining == 2)
				{
					STALLS.get(stall).UnwatchedTicksRemaining = 4;
				}
			}
			// guard is not watching
			else if (!isWatching)
			{
				STALLS.get(stall).WatchedTicksRemaining = 0;
				STALLS.get(stall).ThievingTickCounter += 1;
				if(STALLS.get(stall).ThievingTickCounter >= THIEVING_DURATION)
				{
					int ticksRemaining = STALLS.get(stall).UnwatchedTicksRemaining;
					if (ticksRemaining > 0)
					{
						STALLS.get(stall).UnwatchedTicksRemaining = ticksRemaining - 1;
					}
					STALLS.get(stall).ThievingTickCounter = 0;
				}
			}

			if(config.primaryStall() != PRThievingHelperConfig.StallSelection.NONE)
			{
				if(config.notifyForPrimary())
				{
					checkNotificationTrigger(getPrimaryStallType());
				}
			}

			if(config.secondaryStall() != PRThievingHelperConfig.StallSelection.NONE)
			{
				if(config.notifyForSecondary())
				{
					checkNotificationTrigger(getSecondaryStallType());
				}
			}
		}
	}

	public float getFlashAlpha()
	{
		return flashAlpha;
	}

	public TileObject getPrimaryStallToHighlight()
	{
		return getStallObjectToHighlight(config.primaryStall());
	}

	public TileObject getSecondaryStallToHighlight()
	{
		return getStallObjectToHighlight(config.secondaryStall());
	}

	public StallTypes getPrimaryStallType()
	{
		return configStallToStallType(config.primaryStall());
	}

	public StallTypes getSecondaryStallType()
	{
		return configStallToStallType(config.secondaryStall());
	}

	public int GetStallUnwatchedTicksRemaining(StallTypes stallType)
	{
		return STALLS.get(stallType).UnwatchedTicksRemaining;
	}

	/**
	 * Checks if a stall is safe to highlight for clicking.
	 * A stall is safe if:
	 * - No guard is watching it, OR
	 * - A guard is watching but will leave within SAFE_BUFFER_TICKS ticks
	 *   This gives players time to click right before the guard leaves
	 */
	public boolean isStallSafeToClick(StallTypes stall)
	{
		if (stall == null)
		{
			return false;
		}

		if (!STALLS.get(stall).IsWatched)
		{
			// No guard watching, definitely safe
			return true;
		}

		// Only highlight when guard is about to leave (≤ SAFE_BUFFER_TICKS remaining)
		// This ensures the guard leaves before/as the player clicks
		return STALLS.get(stall).WatchedTicksRemaining == SAFE_BUFFER_TICKS;
	}

	/**
	 * Gets the tile object to highlight for a given stall selection.
	 * Returns the object if it's safe to click (not watched, or guard leaving soon).
	 */
	private TileObject getStallObjectToHighlight(PRThievingHelperConfig.StallSelection stallSelection)
	{
		if (!config.enableStallHighlighting())
		{
			return null;
		}

		StallTypes stallType = configStallToStallType(stallSelection);
		if (stallType == null)
		{
			return null;
		}

		Integer objectId = stallObjectIds.get(stallType);
		if (objectId != null)
		{
			return stallObjects.get(objectId);
		}
		
		return null;
	}

	private boolean isValidGuard(NPC npc)
	{
		String npcName = npc.getName();
		if(npcName == null)
			return false;

		int npcId = npc.getId();
        return npcName.equals(GUARD_NAME) && GUARD_IDS.contains(npcId);
    }

	private boolean isAnyGuardAtPosition(List<WorldPoint> wps)
	{
		for(NPC npc: GUARDS)
		{
			WorldPoint nwp = npc.getWorldLocation();
			int x = nwp.getX();
			int y = nwp.getY();

			for(WorldPoint wp: wps)
			{
				if(x == wp.getX() && y == wp.getY())
				{
					return true;
				}
			}
		}

		return false;
	}

	private void triggerFlash()
	{
		flashAlpha = 1f; // fully opaque at start
	}

	private void checkNotificationTrigger(StallTypes stallType)
	{
		// do all unwatched notifs once the safe buffer ticks is reached
		if(STALLS.get(stallType).WatchedTicksRemaining == SAFE_BUFFER_TICKS)
		{
			if(!STALLS.get(stallType).UnwatchedNotifier)
			{
				if(config.flashForUnwatched())
				{
					triggerFlash();
				}

				if(config.notifyForUnwatched())
				{
					notifier.notify("Stall Unwatched!");
				}

				if(config.soundForUnwatched())
				{
					client.playSoundEffect(SOUND_ID_UNWATCHED);
				}

				STALLS.get(stallType).UnwatchedNotifier = true;
			}

			STALLS.get(stallType).WatchNotifier = false;
		}

		// do all watched notifs if no ticks left for unwatched
		if(STALLS.get(stallType).UnwatchedTicksRemaining == 0)
		{
			if(!STALLS.get(stallType).WatchNotifier)
			{
				if(config.soundForWatched())
				{
					client.playSoundEffect(SOUND_ID_WATCHED);
				}

				STALLS.get(stallType).WatchNotifier = true;
			}

			STALLS.get(stallType).UnwatchedNotifier = false;
		}
	}

	private StallTypes configStallToStallType(PRThievingHelperConfig.StallSelection selection)
	{
		switch (selection)
		{
			case CANNONBALL:return StallTypes.CANNON;
			case VEG: return StallTypes.VEG;
			case ORE: return StallTypes.ORE;
			case FISH: return StallTypes.FISH;
			case SPICE: return StallTypes.SPICE;
			case SILVER: return StallTypes.SILVER;
			case GEM: return StallTypes.GEM;
			case FUR: return StallTypes.FUR;
			case SILK: return StallTypes.SILK;
			case NONE:
			default: return null;
		}
	}

	@Provides
	PRThievingHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PRThievingHelperConfig.class);
	}
}
