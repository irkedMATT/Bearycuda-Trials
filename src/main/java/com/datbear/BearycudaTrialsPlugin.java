package com.datbear;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.datbear.data.*;
import com.datbear.debug.BoatPathHelper;
import com.datbear.debug.BoatPathOverlay;
import com.datbear.ui.*;
import com.google.common.base.Strings;
import com.google.inject.Provides;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.InterfaceID.SailingSidepanel;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j @PluginDescriptor(name = "Bearycuda Trials", description = "Show info to help with barracuda trials", tags = { "overlay", "sailing", "barracuda", "trials" })
public class BearycudaTrialsPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private Notifier notifier;

    @Inject
    private BearycudaTrialsConfig config;

    @Inject
    private BearycudaTrialsOverlay overlay;

    @Inject
    private ObstacleOutlineOverlay obstacleOutlineOverlay;

    @Inject
    private BoatPathOverlay boatPathOverlay;

    @Inject
    private BoostOverlay boostOverlay;

    @Inject
    private BearycudaTrialsPanel panel;

    @Provides
    BearycudaTrialsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BearycudaTrialsConfig.class);
    }

    private final Set<Integer> TRIAL_CRATE_ANIMS = Set.of(8867);
    private final Set<Integer> SPEED_BOOST_ANIMS = Set.of(13159, 13160, 13163);
    private final Set<Integer> DECORATION_ANIMS = Set.of(1071, 13537, 13538, 13539);

    private final String TRIM_AVAILABLE_TEXT = "you feel a gust of wind.";
    private final String TRIM_SUCCESS_TEXT = "you trim the sails";
    private final String TRIM_FAIL_TEXT = "the wind dies down";
    private final String WIND_MOTE_RELEASED_TEXT = "you release the wind mote for a burst of speed";

    private final String MENU_OPTION_START_PREVIOUS_RANK = "start-previous";
    private final String MENU_OPTION_QUICK_RESET = "quick-reset";
    private final String MENU_OPTION_STOP_NAVIGATING = "stop-navigating";
    private final String MENU_OPTION_UNSET = "un-set";

    private static final int VISIT_TOLERANCE = 10;

    private static final int WIND_MOTE_INACTIVE_SPRITE_ID = 7076;
    private static final int WIND_MOTE_ACTIVE_SPRITE_ID = 7075;

    private List<String> FirstMenuEntries = new ArrayList<String>();
    private List<String> DeprioritizeMenuEntriesDuringTrial = new ArrayList<String>(List.of("release-mote", "open"));
    private List<String> RemoveMenuEntriesDuringTrial = new ArrayList<String>();

    @Getter(AccessLevel.PACKAGE)
    private TrialInfo currentTrial = null;

    @Getter(AccessLevel.PACKAGE)
    private boolean needsTrim = false;

    @Getter(AccessLevel.PACKAGE)
    private int lastVisitedIndex = -1;

    @Getter(AccessLevel.PACKAGE)
    private int toadsThrown = 0;

    // Number of consecutive game ticks where TrialInfo.getCurrent(client) returned null
    // Used to allow a grace period before clearing currentTrial during transient nulls
    private int nullTrialConsecutiveTicks = 0;

    private final Set<Integer> TRIAL_BOAT_GAMEOBJECT_IDS = Set.of(
            ObjectID.SAILING_BT_TEMPOR_TANTRUM_NORTH_LOC_PARENT,
            ObjectID.SAILING_BT_TEMPOR_TANTRUM_SOUTH_LOC_PARENT,
            ObjectID.SAILING_BT_JUBBLY_JIVE_TOAD_SUPPLIES_PARENT);

    private final Map<Integer, List<GameObject>> toadFlagsById = new HashMap<>();
    @Getter(AccessLevel.PACKAGE)
    private final Map<Integer, GameObject> trialCratesById = new HashMap<>();
    @Getter(AccessLevel.PACKAGE)
    private final Map<Integer, List<GameObject>> trialBoostsById = new HashMap<>();
    @Getter(AccessLevel.PACKAGE)
    private GameObject sailGameObject = null;

    @Getter(AccessLevel.PACKAGE)
    private final Set<WorldPoint> obstacleWorldPoints = new HashSet<>();

    @Getter(AccessLevel.PACKAGE)
    private Map<Integer, GameObject> trialBoatsById = new HashMap<>();

    @Getter(AccessLevel.PACKAGE)
    private Point lastMenuCanvasPosition = null;
    @Getter(AccessLevel.PACKAGE)
    private WorldPoint lastMenuCanvasWorldPoint = null;

    //varbits...
    @Getter(AccessLevel.PACKAGE)
    private int boatSpawnedAngle;

    @Getter(AccessLevel.PACKAGE)
    private int boatSpawnedFineX;

    @Getter(AccessLevel.PACKAGE)
    private int boatSpawnedFineZ;

    @Getter(AccessLevel.PACKAGE)
    private int boatBaseSpeed;

    @Getter(AccessLevel.PACKAGE)
    private int boatSpeedCap;

    @Getter(AccessLevel.PACKAGE)
    private int boatSpeedBoostDuration;

    @Getter(AccessLevel.PACKAGE)
    private int boatAcceleration;

    @Getter(AccessLevel.PACKAGE)
    private int isInTrial;

    @Getter(AccessLevel.PUBLIC)
    private int boardedBoat;

    @Getter(AccessLevel.PACKAGE)
    private Directions currentHeadingDirection = Directions.North;

    @Getter(AccessLevel.PACKAGE)
    private Directions requestedHeadingDirection = currentHeadingDirection;

    @Getter(AccessLevel.PACKAGE)
    private Directions hoveredHeadingDirection = Directions.North;

    @Getter(AccessLevel.PACKAGE)
    private int windMoteReleasedTick;

    @Getter(AccessLevel.PACKAGE)
    private Widget windMoteButtonWidget;

    @Getter(AccessLevel.PACKAGE)
    private double lastCratePickupDistance;

    @Getter(AccessLevel.PACKAGE)
    private double minCratePickupDistance;

    @Getter(AccessLevel.PACKAGE)
    private double maxCratePickupDistance;

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        overlayManager.add(obstacleOutlineOverlay);
        overlayManager.add(boatPathOverlay);
        overlayManager.add(boostOverlay);
        overlayManager.add(panel);

        //menu entries
        if (config.enableStartPreviousRankLeftClick()) {
            FirstMenuEntries.add(MENU_OPTION_START_PREVIOUS_RANK);
        }
        if (config.enableQuickResetLeftClick()) {
            FirstMenuEntries.add(MENU_OPTION_QUICK_RESET);
        }
        if (config.disableStopNavigating()) {
            DeprioritizeMenuEntriesDuringTrial.add(MENU_OPTION_STOP_NAVIGATING);
        }
        if (config.disableUnsetSail()) {
            DeprioritizeMenuEntriesDuringTrial.add(MENU_OPTION_UNSET);
        }
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        overlayManager.remove(obstacleOutlineOverlay);
        overlayManager.remove(boatPathOverlay);
        overlayManager.remove(boostOverlay);
        overlayManager.remove(panel);
        reset();
    }

    @Subscribe
    public void onClientTick(ClientTick clientTick) {
        if (!config.enableBoatPathDebug()) {
            return;
        }

        var localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }

        var tick = client.getTickCount();
        var position = BoatLocation.fromLocal(client, localPlayer.getLocalLocation());
        if (tick <= 0 || position == null) {
            return;
        }

        if (BoatPathHelper.HasTickData(tick)) {
            //log.info("Adding visited point for tick {}: {}", tick, position);
            BoatPathHelper.AddVisitedPoint(tick, position);
        } else {
            BoatPathHelper.StartNewTick(tick, position, currentHeadingDirection);
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

        updateFromVarbits();
        updateCurrentTrial();
        updateCurrentHeading();

        updateWindMoteButtonWidget();

        final var player = client.getLocalPlayer();
        var boatLocation = BoatLocation.fromLocal(client, player.getLocalLocation());

        if (boatLocation == null)
            return;

        var active = getActiveTrialRoute();
        if (active != null) {
            markNextWaypointVisited(boatLocation, active, VISIT_TOLERANCE);
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        if (event.getVarbitId() == VarbitID.SAILING_BOAT_SPAWNED_ANGLE) {
            boatSpawnedAngle = event.getValue();
            updateCurrentHeadingFromVarbit(boatSpawnedAngle);
        }

        if (event.getVarbitId() == VarbitID.SAILING_BT_IN_TRIAL) {
            updateToadsThrown(currentTrial);
        }

        trackCratePickups(event);
    }

    private void updateCurrentHeadingFromVarbit(int value) {
        var ordinal = value / 128;
        var directions = Directions.values();
        if (ordinal < 0 || ordinal >= directions.length) {
            return;
        }
        var newDir = directions[ordinal];
        currentHeadingDirection = newDir;
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        var obj = event.getGameObject();
        if (obj == null) {
            return;
        }
        var id = obj.getId();

        var isToadFlag = ToadFlagGameObject.All.stream().anyMatch(t -> t.GameObjectIds.contains(id));
        if (isToadFlag) {
            toadFlagsById.computeIfAbsent(id, k -> new ArrayList<>()).add(obj);
        }

        var isTrialBoat = TRIAL_BOAT_GAMEOBJECT_IDS.contains(id);
        if (isTrialBoat) {
            trialBoatsById.put(id, obj);
            //log.info("Tracked trial boat gameobject id {} at {} - {}", id, obj.getWorldLocation(), BoatLocation.fromLocal(client, obj.getLocalLocation()));

        }

        var isObstacle = ObstacleTracking.OBSTACLE_GAMEOBJECT_IDS.contains(id);
        if (isObstacle && config.showObstacleOutlines()) {
            // Add world points for all tiles covered by this obstacle's footprint
            try {
                var worldView = client.getTopLevelWorldView();
                var scene = worldView != null ? worldView.getScene() : null;
                if (scene != null) {
                    var min = obj.getSceneMinLocation();
                    var max = obj.getSceneMaxLocation();
                    if (min != null && max != null) {
                        var plane = worldView.getPlane();
                        for (var x = min.getX(); x <= max.getX(); x++) {
                            for (var y = min.getY(); y <= max.getY(); y++) {
                                WorldPoint wp = WorldPoint.fromScene(worldView, x, y, plane);
                                obstacleWorldPoints.add(wp);
                            }
                        }
                    } else {
                        obstacleWorldPoints.add(obj.getWorldLocation());
                    }
                } else {
                    obstacleWorldPoints.add(obj.getWorldLocation());
                }
            } catch (Exception ex) {
                obstacleWorldPoints.add(obj.getWorldLocation());
            }
            if (currentTrial != null) {
                removeGameObjectFromScene(obj);
            }
        }

        var isSail = AllSails.GAMEOBJECT_IDS.contains(id);
        if (isSail) {
            sailGameObject = obj;
        }

        var renderable = obj.getRenderable();
        if (renderable != null) {
            if (renderable instanceof DynamicObject) {
                var dynObj = (DynamicObject) renderable;
                var anim = dynObj.getAnimation();
                var animId = anim != null ? anim.getId() : -1;
                if (TRIAL_CRATE_ANIMS.contains(animId)) {
                    trialCratesById.put(id, obj);
                } else if (SPEED_BOOST_ANIMS.contains(animId)) {
                    trialBoostsById.computeIfAbsent(id, k -> new ArrayList<>()).add(obj);
                } else if (DECORATION_ANIMS.contains(animId)) {
                    if (config.hideDecorations()) {
                        removeGameObjectFromScene(obj);
                    }
                }
            }
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event) {
        var obj = event.getGameObject();
        if (obj == null)
            return;

        var id = obj.getId();

        var flagList = toadFlagsById.get(id);
        if (flagList != null) {
            flagList.removeIf(x -> x == null || x == obj);
            if (flagList.isEmpty()) {
                toadFlagsById.remove(id);
            }
        }

        if (trialBoatsById.get(id) == obj) {
            trialBoatsById.remove(id);
        }

        if (trialCratesById.get(id) == obj) {
            trialCratesById.remove(id);
        }

        var boostList = trialBoostsById.get(id);
        if (boostList != null) {
            boostList.removeIf(x -> x == null || x == obj);
            if (boostList.isEmpty()) {
                trialBoostsById.remove(id);
            }
        }

        if (sailGameObject == obj) {
            sailGameObject = null;
        }
    }

    @Subscribe
    public void onWorldViewUnloaded(WorldViewUnloaded event) {
        for (var toadFlagList : toadFlagsById.values()) {
            toadFlagList.removeIf(obj -> event.getWorldView() == obj.getWorldView());
        }
        toadFlagsById.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        trialBoatsById.entrySet().removeIf(entry -> event.getWorldView() == entry.getValue().getWorldView());
        trialCratesById.entrySet().removeIf(entry -> event.getWorldView() == entry.getValue().getWorldView());

        for (var boostList : trialBoostsById.values()) {
            boostList.removeIf(obj -> event.getWorldView() == obj.getWorldView());
        }
        trialBoostsById.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        if (sailGameObject != null && event.getWorldView() == sailGameObject.getWorldView()) {
            sailGameObject = null;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            // on region changes the tiles and gameobjects get set to null
            reset();
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {

        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        RouteModificationHelper.handleMenuOptionClicked(event, client, getActiveTrialRoute(), lastMenuCanvasPosition, lastVisitedIndex);
        handleHeadingClicks(event);

        if (!config.showDebugMenuCopyTileOptions()) {
            return;
        }

        final var copyOption = "Copy worldpoint";
        final var copyTileOption = "Copy tile worldpoint";
        if (event.getMenuOption() != null && event.getMenuOption().equals(copyOption)) {
            var player = client.getLocalPlayer();
            if (player == null)
                return;

            var wp = BoatLocation.fromLocal(client, player.getLocalLocation());
            if (wp == null)
                return;

            var toCopy = String.format("new WorldPoint(%d, %d, %d),", wp.getX(), wp.getY(), wp.getPlane());

            try {
                var sel = new java.awt.datatransfer.StringSelection(toCopy);
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                notifier.notify("Copied worldpoint to clipboard: " + toCopy);
            } catch (Exception ex) {
                log.warn("Failed to copy worldpoint to clipboard: {}", ex.toString());
            }

            event.consume();
        } else if (event.getMenuOption() != null && event.getMenuOption().equals(copyTileOption)) {
            var mouse = lastMenuCanvasPosition != null ? lastMenuCanvasPosition : client.getMouseCanvasPosition();
            lastMenuCanvasWorldPoint = null;

            try {
                var worldView = client.getTopLevelWorldView();
                var scene = worldView.getScene();
                var z = worldView.getPlane();
                var tiles = scene.getTiles();

                if (tiles != null && z >= 0 && z < tiles.length) {
                    var plane = tiles[z];
                    for (var x = 0; x < plane.length; x++) {
                        for (var y = 0; y < plane[x].length; y++) {
                            var tile = plane[x][y];
                            if (tile == null)
                                continue;
                            var lp = tile.getLocalLocation();
                            var poly = net.runelite.api.Perspective.getCanvasTilePoly(client, lp);
                            if (poly == null || mouse == null)
                                continue;
                            if (poly.contains(mouse.getX(), mouse.getY())) {
                                lastMenuCanvasWorldPoint = WorldPoint.fromLocalInstance(client, lp);
                                break;
                            }
                        }
                        if (lastMenuCanvasWorldPoint != null)
                            break;
                    }
                }
            } catch (Throwable ex) {
                // fall back to null
            }

            var worldPoint = lastMenuCanvasWorldPoint == null ? client.getLocalPlayer() == null ? null
                    : client.getLocalPlayer().getWorldLocation() : lastMenuCanvasWorldPoint;
            if (worldPoint == null)
                return;

            var toCopy = String.format("new WorldPoint(%d, %d, %d),", worldPoint.getX(), worldPoint.getY(), worldPoint.getPlane());
            try {
                var sel = new java.awt.datatransfer.StringSelection(toCopy);
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                notifier.notify("Copied tile worldpoint to clipboard: " + toCopy);
            } catch (Exception ex) {
                log.warn("Failed to copy tile worldpoint to clipboard: {}", ex.toString());
            }

            event.consume();
            // Clear the stored menu-open position so we don't reuse it on subsequent clicks
            lastMenuCanvasPosition = null;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event == null) {
            return;
        }

        if (!event.getGroup().equals("bearycudaTrials")) {
            return;
        }

        if (config.enableStartPreviousRankLeftClick()) {
            if (FirstMenuEntries.stream().noneMatch(x -> x.equals(MENU_OPTION_START_PREVIOUS_RANK))) {
                FirstMenuEntries.add(MENU_OPTION_START_PREVIOUS_RANK);
            }
        } else {
            if (FirstMenuEntries.stream().anyMatch(x -> x.equals(MENU_OPTION_START_PREVIOUS_RANK))) {
                FirstMenuEntries.remove(MENU_OPTION_START_PREVIOUS_RANK);
            }
        }

        if (config.enableQuickResetLeftClick()) {
            if (FirstMenuEntries.stream().noneMatch(x -> x.equals(MENU_OPTION_QUICK_RESET))) {
                FirstMenuEntries.add(MENU_OPTION_QUICK_RESET);
            }
        } else {
            if (FirstMenuEntries.stream().anyMatch(x -> x.equals(MENU_OPTION_QUICK_RESET))) {
                FirstMenuEntries.remove(MENU_OPTION_QUICK_RESET);
            }
        }

        if (config.disableStopNavigating()) {
            if (DeprioritizeMenuEntriesDuringTrial.stream().noneMatch(x -> x.equals(MENU_OPTION_STOP_NAVIGATING))) {
                DeprioritizeMenuEntriesDuringTrial.add(MENU_OPTION_STOP_NAVIGATING);
            }
        } else {
            if (DeprioritizeMenuEntriesDuringTrial.stream().anyMatch(x -> x.equals(MENU_OPTION_STOP_NAVIGATING))) {
                DeprioritizeMenuEntriesDuringTrial.remove(MENU_OPTION_STOP_NAVIGATING);
            }
        }

        if (config.disableUnsetSail()) {
            if (DeprioritizeMenuEntriesDuringTrial.stream().noneMatch(x -> x.equals(MENU_OPTION_UNSET))) {
                DeprioritizeMenuEntriesDuringTrial.add(MENU_OPTION_UNSET);
            }
        } else {
            if (DeprioritizeMenuEntriesDuringTrial.stream().anyMatch(x -> x.equals(MENU_OPTION_UNSET))) {
                DeprioritizeMenuEntriesDuringTrial.remove(MENU_OPTION_UNSET);
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage e) {
        if (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM) {
            return;
        }

        var msg = e.getMessage().toLowerCase();
        if (msg == null || msg.isEmpty()) {
            return;
        }

        if (msg.contains(WIND_MOTE_RELEASED_TEXT)) {
            windMoteReleasedTick = client.getTickCount();
        }
        if (msg.contains(TRIM_AVAILABLE_TEXT)) {
            needsTrim = true;
        } else if (msg.contains(TRIM_SUCCESS_TEXT) || msg.contains(TRIM_FAIL_TEXT)) {
            needsTrim = false;
        }
    }

    @Subscribe(priority = -1)
    public void onPostMenuSort(PostMenuSort e) {
        manageHeadingHovers(e);

        if (client.isMenuOpen()) {
            return;
        }
        var entries = client.getMenuEntries();

        entries = swapMenuEntries(entries);
        entries = addDebugMenuEntries(entries);

        if (currentTrial != null && !DeprioritizeMenuEntriesDuringTrial.isEmpty()) {
            entries = deprioritizeMenuEntries(entries, DeprioritizeMenuEntriesDuringTrial);
        }
        if (currentTrial != null && !RemoveMenuEntriesDuringTrial.isEmpty()) {
            entries = removeMenuEntries(entries, RemoveMenuEntriesDuringTrial);
        }

        if (currentTrial != null && config.showDebugRouteModificationOptions()) {
            entries = RouteModificationHelper.addRouteModificationEntries(client, entries, getActiveTrialRoute());
        }

        client.setMenuEntries(entries);
    }

    private void updateCurrentTrial() {
        var newTrialInfo = TrialInfo.getCurrent(client);
        // Allow a grace period of 18 game ticks where TrialInfo may be null (e.g., region/load transitions) before clearing currentTrial.
        // We only apply the null after 18 consecutive null ticks.
        if (newTrialInfo != null) {
            // If trial info reappears, clear any null-grace countdown
            nullTrialConsecutiveTicks = 0;

            // If the trial changed (location/rank/reset time), reset route state
            if (currentTrial == null || currentTrial.Location != newTrialInfo.Location || currentTrial.Rank != newTrialInfo.Rank || newTrialInfo.CurrentTimeSeconds < currentTrial.CurrentTimeSeconds) {
                resetRouteData();
            }

            updateToadsThrown(newTrialInfo);
            currentTrial = newTrialInfo;
        } else {
            if (currentTrial != null) {
                nullTrialConsecutiveTicks += 1;
                if (nullTrialConsecutiveTicks >= 18) {
                    resetRouteData();
                    currentTrial = null;
                }
            } else {
                nullTrialConsecutiveTicks = 0;
            }
        }
    }

    private MenuEntry[] removeMenuEntries(MenuEntry[] entries, Collection<String> toRemove) {
        if (entries == null || entries.length == 0 || toRemove == null || toRemove.isEmpty()) {
            return entries;
        }
        var entryList = new ArrayList<MenuEntry>(Arrays.asList(entries));
        var it = entryList.iterator();
        while (it.hasNext()) {
            var menuEntry = it.next();
            if (menuEntry == null) {
                continue;
            }
            var opt = menuEntry.getOption();
            if (opt == null) {
                continue;
            }
            if (toRemove.stream().anyMatch(x -> opt.toLowerCase().contains(x))) {
                it.remove();
            }
        }
        return entryList.toArray(new MenuEntry[0]);
    }

    private MenuEntry[] deprioritizeMenuEntries(MenuEntry[] entries, Collection<String> toRemove) {
        if (entries == null || entries.length == 0 || toRemove == null || toRemove.isEmpty()) {
            return entries;
        }
        var walkHereEntry = Arrays.stream(entries)
                .filter(x -> x != null && x.getOption().toLowerCase().equals("walk here") || x.getOption().toLowerCase().equals("set heading"))
                .findFirst().orElse(null);
        var entryList = new ArrayList<MenuEntry>(Arrays.asList(entries));
        var it = entryList.iterator();
        while (it.hasNext()) {
            var menuEntry = it.next();
            if (menuEntry == null) {
                continue;
            }
            var opt = menuEntry.getOption();
            if (opt == null) {
                continue;
            }
            if (toRemove.stream().anyMatch(x -> opt.toLowerCase().contains(x))) {
                if (walkHereEntry != null) {
                    var walkIdx = entryList.indexOf(walkHereEntry);
                    var currIdx = entryList.indexOf(menuEntry);
                    entryList.set(walkIdx, menuEntry);
                    entryList.set(currIdx, walkHereEntry);
                }
            }
        }
        return entryList.toArray(new MenuEntry[0]);
    }

    private MenuEntry[] swapMenuEntries(MenuEntry[] entries) {
        if (entries == null || entries.length == 0) {
            return entries;
        }
        var toMove = new ArrayList<MenuEntry>();
        var entriesAsList = new ArrayList<>(Arrays.asList(entries));
        var iterator = entriesAsList.iterator();
        while (iterator.hasNext()) {
            var menuEntry = iterator.next();
            if (menuEntry == null) {
                continue;
            }
            var opt = menuEntry.getOption();
            if (opt == null) {
                continue;
            }
            if (FirstMenuEntries.stream().anyMatch(x -> opt.toLowerCase().contains(x))) {
                toMove.add(menuEntry);
                iterator.remove();
            }
        }
        if (!toMove.isEmpty()) {
            entriesAsList.addAll(toMove);
        }
        entries = entriesAsList.toArray(new MenuEntry[0]);
        return entries;
    }

    private MenuEntry[] addDebugMenuEntries(MenuEntry[] entries) {
        if (!config.showDebugMenuCopyTileOptions()) {
            return entries;
        }

        var player = client.getLocalPlayer();
        if (player == null) {
            return entries;
        }

        var hasCopyPlayerLocation = false;
        var hasCopyTileLocation = false;
        if (entries != null) {
            for (var entry : entries) {
                if (entry == null) {
                    continue;
                }
                if (entry.getOption().equals("Copy worldpoint")) {
                    hasCopyPlayerLocation = true;
                }
                if (entry.getOption().equals("Copy tile worldpoint")) {
                    hasCopyTileLocation = true;
                }
            }
        }

        var list = new ArrayList<MenuEntry>();

        if (!hasCopyTileLocation) {
            var copyTile = client.getMenu().createMenuEntry(-1).setOption("Copy tile worldpoint")
                    .setTarget("").setType(MenuAction.RUNELITE);
            list.add(copyTile);
        }

        if (!hasCopyPlayerLocation) {
            var copyPlayer = client.getMenu().createMenuEntry(-1).setOption("Copy worldpoint")
                    .setTarget("").setType(MenuAction.RUNELITE);
            list.add(copyPlayer);
        }

        // Capture the menu-open canvas position so we can later use that exact location for "Copy tile worldpoint" when the menu item is clicked.
        lastMenuCanvasPosition = client.getMouseCanvasPosition();
        if (entries != null) {
            list.addAll(Arrays.asList(entries));
        }
        return list.toArray(new MenuEntry[0]);
    }

    private void resetRouteData() {
        lastVisitedIndex = -1;
        toadsThrown = 0;
    }

    private void reset() {
        requestedHeadingDirection = currentHeadingDirection;
    }

    private void updateToadsThrown(TrialInfo newTrialInfo) {
        if (currentTrial == null || isInTrial == 0 || newTrialInfo.CurrentTimeSeconds < currentTrial.CurrentTimeSeconds || newTrialInfo.CurrentTimeSeconds == 0) {
            toadsThrown = 0;
            return;
        }
        if (newTrialInfo.ToadCount < currentTrial.ToadCount) {
            toadsThrown += 1;
        }
    }

    public void markNextWaypointVisited(final WorldPoint player, final TrialRoute route, final int tolerance) {
        if (player == null || route == null || route.Points == null || route.Points.isEmpty()) {
            return;
        }
        var nextIdx = lastVisitedIndex + 1;
        if (nextIdx >= route.Points.size()) {
            return; // finished route
        }
        var target = route.Points.get(nextIdx);
        if (target == null) {
            return;
        }
        var dist = Math.hypot(player.getX() - target.getX(), player.getY() - target.getY());
        if (dist <= tolerance) {
            lastVisitedIndex = nextIdx;
            //log.info("Visited waypoint {} / {} for route {}", lastVisitedIndex, route.Points.size() - 1, route.Rank);
        }
    }

    public List<Integer> getNextIndicesAfterLastVisited(final TrialRoute route, final int limit) {
        if (route == null || route.Points == null || route.Points.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        var start = Math.max(0, lastVisitedIndex);
        if (start >= route.Points.size()) {
            return Collections.emptyList();
        }
        var out = new ArrayList<Integer>(limit);
        var nextPortal = route.PortalDirections.stream()
                .filter(x -> x.Index >= lastVisitedIndex)
                .min((a, b) -> Integer.compare(a.Index, b.Index))
                .orElse(null);
        for (var i = start; i < route.Points.size() && out.size() < limit; i++) {
            if (nextPortal != null && i > nextPortal.Index) {
                break;
            }
            out.add(i);
        }
        return out;
    }

    public List<WorldPoint> getVisibleLineForRoute(final WorldPoint player, final TrialRoute route, final int limit) {
        if (player == null || route == null || lastVisitedIndex == -1) {
            return Collections.emptyList();
        }

        final List<Integer> nextIdx = getNextIndicesAfterLastVisited(route, limit);
        if (nextIdx.isEmpty()) {
            return Collections.emptyList();
        }

        List<WorldPoint> out = new ArrayList<>();
        for (var idx : nextIdx) {
            var real = route.Points.get(idx);
            out.add(real);
        }
        return out;
    }

    public PortalDirection getVisiblePortalDirection(TrialRoute route) {
        var portalDirection = route.PortalDirections.stream()
                .filter(x -> x.Index - 1 == lastVisitedIndex || x.Index == lastVisitedIndex || x.Index + 1 == lastVisitedIndex)
                .min((a, b) -> Integer.compare(a.Index, b.Index))
                .orElse(null);

        return portalDirection;
    }

    public List<GameObject> getToadFlagGameObjectsForIds(Set<Integer> ids) {
        var out = new ArrayList<GameObject>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        for (var id : ids) {
            var list = toadFlagsById.get(id);
            if (list != null && !list.isEmpty()) {
                out.addAll(list);
            }
        }
        return out;
    }

    public TrialRoute getActiveTrialRoute() {
        if (currentTrial == null)
            return null;

        for (var route : TrialRoute.AllTrialRoutes) {
            if (route == null) {
                continue;
            }

            if (route.Location == currentTrial.Location && route.Rank == currentTrial.Rank) {
                return route;
            }
        }
        return null;
    }

    public List<WorldPoint> getVisibleActiveLineForPlayer(final WorldPoint player, final int limit) {
        var route = getActiveTrialRoute();
        if (route == null) {
            return Collections.emptyList();
        }

        return getVisibleLineForRoute(player, route, limit);
    }

    public List<Integer> getNextUnvisitedIndicesForActiveRoute(final int limit) {
        var route = getActiveTrialRoute();
        if (route == null) {
            return Collections.emptyList();
        }
        return getNextIndicesAfterLastVisited(route, limit);
    }

    public int getHighlightedToadFlagIndex() {
        var route = getActiveTrialRoute();
        if (route == null || currentTrial == null) {
            return 0;
        }
        return getHighlightedToadFlagIndex(route);
    }

    private int getHighlightedToadFlagIndex(TrialRoute route) {
        return toadsThrown < route.ToadOrder.size() ? toadsThrown : 0;
    }

    public List<GameObject> getToadFlagToHighlight() {
        if (currentTrial == null || currentTrial.Location != TrialLocations.JubblyJive || currentTrial.ToadCount <= 0) {
            return Collections.emptyList();
        }

        var route = getActiveTrialRoute();
        if (route == null || route.ToadOrder == null || route.ToadOrder.isEmpty()) {
            return Collections.emptyList();
        }

        var nextToadIdx = getHighlightedToadFlagIndex(route);
        if (nextToadIdx >= 0 && nextToadIdx < route.ToadOrder.size()) {
            var nextToadColor = route.ToadOrder.get(nextToadIdx);
            var nextToadGameObject = ToadFlagGameObject.getByColor(nextToadColor);
            var cached = getToadFlagGameObjectsForIds(nextToadGameObject.GameObjectIds);
            if (!cached.isEmpty()) {
                return cached;
            }
        }

        return Collections.emptyList();
    }

    public Collection<GameObject> getTrialBoatsToHighlight() {
        var route = getActiveTrialRoute();
        if (route == null || currentTrial == null || trialBoatsById.isEmpty()) {
            return Collections.emptyList();
        }

        if (route.Location == TrialLocations.JubblyJive && !currentTrial.HasToads) {
            return trialBoatsById.values();
        }

        if (route.Location == TrialLocations.TemporTantrum) {
            if (currentTrial.HasRum) {
                var boat = trialBoatsById.get(ObjectID.SAILING_BT_TEMPOR_TANTRUM_NORTH_LOC_PARENT);
                if (boat != null) {
                    return List.of(boat);
                }
            } else {
                var boat = trialBoatsById.get(ObjectID.SAILING_BT_TEMPOR_TANTRUM_SOUTH_LOC_PARENT);
                if (boat != null) {
                    return List.of(boat);
                }
            }
        }

        return Collections.emptyList();
    }

    private void handleHeadingClicks(MenuOptionClicked event) {
        if (event.getMenuAction() != MenuAction.SET_HEADING) {
            return;
        }
        requestedHeadingDirection = Directions.values()[event.getId()];
    }

    private void updateCurrentHeading() {
        if (currentHeadingDirection == null) {
            currentHeadingDirection = Directions.South;
        }

        if (requestedHeadingDirection == null) {
            requestedHeadingDirection = currentHeadingDirection;
            return;
        }

        if (currentHeadingDirection == requestedHeadingDirection) {
            return;
        }

        var all = Directions.values();
        var n = all.length;
        var currentIndex = currentHeadingDirection.ordinal();
        var targetIndex = requestedHeadingDirection.ordinal();

        var forwardSteps = (targetIndex - currentIndex + n) % n;
        var backwardSteps = (currentIndex - targetIndex + n) % n;

        if (forwardSteps == 0) {
            return;
        }

        if (forwardSteps <= backwardSteps) {
            currentIndex = (currentIndex + 1) % n;
        } else {
            currentIndex = (currentIndex - 1 + n) % n;
        }

        currentHeadingDirection = all[currentIndex];
    }

    private void manageHeadingHovers(PostMenuSort event) {
        var entries = client.getMenuEntries();
        var headingEntry = Arrays.stream(entries)
                .filter(e -> e.getOption().equals("Set heading"))
                .findFirst().orElse(null);
        if (headingEntry != null) {
            hoveredHeadingDirection = Directions.values()[headingEntry.getIdentifier()];
        }
    }

    private void updateFromVarbits() {
        //todo check VarbitID.SAILING_BOAT_TIME_TILL_TRIM and VarbitID.SAILING_BOAT_TIME_TRIM_WINDOW to see if they're working in the future
        boatSpawnedAngle = client.getVarbitValue(VarbitID.SAILING_BOAT_SPAWNED_ANGLE);
        boatSpawnedFineX = client.getVarbitValue(VarbitID.SAILING_BOAT_SPAWNED_FINEX);
        boatSpawnedFineZ = client.getVarbitValue(VarbitID.SAILING_BOAT_SPAWNED_FINEZ);
        boatBaseSpeed = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_BASESPEED);
        boatSpeedCap = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_SPEEDCAP);
        boatSpeedBoostDuration = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_SPEEDBOOST_DURATION);
        boatAcceleration = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_ACCELERATION);
        isInTrial = client.getVarbitValue(VarbitID.SAILING_BT_IN_TRIAL);
        boardedBoat = client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT);
    }

    private void removeGameObjectFromScene(GameObject gameObject) {
        if (gameObject != null) {
            var renderable = gameObject == null ? null : gameObject.getRenderable();
            if (renderable != null) {
                var model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
                if (model != null) {
                    var scene = client.getTopLevelWorldView().getScene();
                    if (scene != null) {
                        scene.removeGameObject(gameObject);
                    }
                    var playerWv = client.getLocalPlayer().getWorldView();
                    var playerScene = playerWv != null ? playerWv.getScene() : null;
                    if (playerScene != null) {
                        playerScene.removeGameObject(gameObject);
                    }
                }
            }
        }
    }

    private void updateWindMoteButtonWidget() {
        if (boardedBoat == 0) {
            windMoteButtonWidget = null;
            return;
        }

        if (windMoteButtonWidget != null && !windMoteButtonWidget.isHidden()) {
            return;
        }

        var widget = client.getWidget(SailingSidepanel.FACILITIES_ROWS);
        if (widget == null) {
            //log.info("updateWindMoteButtonWidget: FACILITIES_ROWS widget is null");
            return;
        }

        var facilityChildren = widget.getChildren();
        Widget button = null;
        if (facilityChildren != null) {
            for (var childWidget : facilityChildren) {
                if (childWidget != null && (childWidget.getSpriteId() == WIND_MOTE_INACTIVE_SPRITE_ID || childWidget.getSpriteId() == WIND_MOTE_ACTIVE_SPRITE_ID)) {
                    button = childWidget;
                    break;
                }
            }
        }
        if (button != null) {
            if (windMoteButtonWidget == null) {
                windMoteButtonWidget = button;
            }
        }
    }

    private void trackCratePickups(VarbitChanged event) {
        if (!config.enableCratePickupDebug()) {
            return;
        }

        if (event.getVarbitId() >= VarbitID.SAILING_BT_OBJECTIVE0 && event.getVarbitId() <= VarbitID.SAILING_BT_OBJECTIVE95) {
            if (!config.enableCratePickupDebug()) {
                return;
            }
            var closestCrate = getClosestTrialCrate();
            if (closestCrate != null) {
                var player = client.getLocalPlayer();
                if (player != null) {
                    var playerPoint = BoatLocation.fromLocal(client, player.getLocalLocation());
                    if (playerPoint != null) {
                        var cratePoint = closestCrate.getWorldLocation();
                        lastCratePickupDistance = Math.hypot(Math.abs(playerPoint.getX() - cratePoint.getX()), Math.abs(playerPoint.getY() - cratePoint.getY()));
                        log.info("Picked up crate from distance: {}", lastCratePickupDistance);
                        if (minCratePickupDistance == 0 || lastCratePickupDistance < minCratePickupDistance) {
                            minCratePickupDistance = lastCratePickupDistance;
                        }
                        if (lastCratePickupDistance > maxCratePickupDistance) {
                            maxCratePickupDistance = lastCratePickupDistance;
                        }
                    }
                }
            }
        }
    }

    private GameObject getClosestTrialCrate() {
        if (!config.enableCratePickupDebug()) {
            return null;
        }

        GameObject closest = null;
        var closestDist = Double.MAX_VALUE;

        var player = client.getLocalPlayer();
        if (player == null) {
            return null;
        }
        var playerPoint = BoatLocation.fromLocal(client, player.getLocalLocation());
        if (playerPoint == null) {
            return null;
        }

        for (var crateEntry : trialCratesById.entrySet()) {
            var crate = crateEntry.getValue();
            if (crate == null) {
                continue;
            }
            var cratePoint = crate.getWorldLocation();
            var dist = playerPoint.distanceTo(cratePoint);
            if (dist < closestDist) {
                closestDist = dist;
                closest = crate;
            }
        }
        return closest;
    }

    private void logCrateAndBoostSpawns(GameObjectSpawned event) {
        var gameObject = event.getGameObject();
        if (gameObject == null) {
            return;
        }

        var renderable = gameObject.getRenderable();
        if (!(renderable instanceof net.runelite.api.DynamicObject)) {
            return; // not an animating dynamic object
        }

        var dyn = (net.runelite.api.DynamicObject) renderable;
        var anim = dyn.getAnimation();
        if (anim == null) {
            return;
        }

        final var animId = anim.getId();
        final var isCrateAnim = TRIAL_CRATE_ANIMS.contains(animId);
        final var isSpeedAnim = SPEED_BOOST_ANIMS.contains(animId);

        if (!isCrateAnim && !isSpeedAnim) {
            return; // ignore unrelated animations
        }

        var wp = gameObject.getWorldLocation();

        var objectComposition = client.getObjectDefinition(gameObject.getId());
        if (objectComposition.getImpostorIds() == null) {
            var name = objectComposition.getName();
            if (Strings.isNullOrEmpty(name) || name.equals("null")) {
                return;
            }
        }

        var minLocation = gameObject.getSceneMinLocation();
        var poly = gameObject.getCanvasTilePoly();

        var type = isCrateAnim ? "CRATE" : "SPEED BOOST";
        if (wp != null) {
            if (isCrateAnim) {
                log.info("[SPAWN] {} -> GameObject id={} world={} (hash={}) minLocation={} poly={}", type, animId, gameObject.getId(), wp, gameObject.getHash(), minLocation, poly);
            }

        } else {
            log.info("[SPAWN] {} -> GameObject id={} (no world point available)", type, gameObject.getId());
        }
    }

}
