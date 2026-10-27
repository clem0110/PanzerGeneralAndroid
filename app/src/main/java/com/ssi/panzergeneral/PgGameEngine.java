package com.ssi.panzergeneral;

import android.content.Context;
import java.io.*;
import java.util.*;
import lombok.*;

public class PgGameEngine {
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CombatPrediction implements Serializable {
        public int expectedAttackerLosses;
        public int expectedDefenderLosses;
        public int effectiveAttackerIni;
        public int effectiveDefenderIni;
        public boolean ruggedDefenseChance;
        public boolean artillerySupport;
        public boolean fighterInterceptor;
        public String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CombatReport implements Serializable {
        public String attackerName;
        public String defenderName;
        public int attackerId;
        public int defenderId;
        public int attackerIconId;
        public int defenderIconId;
        public int attackerStartStr;
        public int defenderStartStr;
        public int attackerLosses;
        public int defenderLosses;
        public int defenderSuppressed;
        public boolean defenderDestroyed;
        public boolean attackerDestroyed;
        public boolean defenderRetreated;
        public boolean defenderSurrendered;
        public boolean ruggedDefense;
        public boolean overrun;
        public boolean surpriseContact;
        public boolean submarineDived;
        public PgDatabase.ActiveUnit supportUnit;
        public PgDatabase.ActiveUnit interceptorUnit;
        public int targetCol, targetRow;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UndoMoveState {
        public PgDatabase.ActiveUnit unit;
        public int prevCol;
        public int prevRow;
        public int prevFuel;
        public int prevMovePointsUsed;
        public boolean prevMoved;
        public int prevEntrenchment;
        public boolean prevFacingLeft;
        public int destCol;
        public int destRow;
        public int prevDestOwnerNation;
        public int prevAxisPrestige;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SaveState implements Serializable {
        public boolean isCampaign;
        public int campaignEntryIndex;
        public int currentScenarioNumber;
        public int turn;
        public int maxTurns;
        public int weather; // 0 = Clear, 1 = Overcast, 2 = Rain/Snow
        public int groundCondition; // 0 = Dry, 1 = Mud, 2 = Frozen
        public int wetness;
        public int axisPrestige;
        public int alliedPrestige;
        @Builder.Default
        public boolean supplyEnabled = true;
        @Builder.Default
        public boolean weatherEnabled = true;
        @Builder.Default
        public boolean fogOfWarEnabled = true;
        @Builder.Default
        public int prestigeModifier = 100; // 50%, 75%, 100%, 150%, 200%
        @Builder.Default
        public List<PgDatabase.ActiveUnit> units = new ArrayList<>();
        @Builder.Default
        public List<PgDatabase.ActiveUnit> coreRoster = new ArrayList<>();
        public int[][] hexOwners;
        public boolean[][] fogVisible;
        public String saveTimestamp;
        public String scenarioTitle;
    }

    public final PgDatabase db;
    public PgDatabase.ScenarioData scenario;
    public boolean isCampaignMode = true;
    public int campaignIndex = 0; // 0=1939, 1=1940, 2=1941, 3=1942, 4=1943W, 5=1943E
    public int currentScenarioNumber = 1;
    public int turn = 1;
    public int maxTurns = 15;
    public int weather = 0; // 0=Clear, 1=Overcast, 2=Storm
    public int groundCondition = 0; // 0=Dry, 1=Mud, 2=Snow
    public int axisPrestige = 400;
    public int alliedPrestige = 400;

    // Original Panzer General Preferences / Game Rules
    public boolean supplyEnabled = true;     // When OFF, units never run out of Fuel or Ammo
    public boolean weatherEnabled = true;    // When OFF, weather is always Clear & Dry
    public boolean fogOfWarEnabled = true;   // When OFF, all hexes and enemy units are visible
    public boolean showHexGrid = true;       // Hex grid overlay toggle
    public boolean showUnitStrength = true;  // Unit strength & experience badges toggle
    public int prestigeModifier = 100;       // Axis Prestige handicap % (50, 75, 100, 125, 150, 200)
    public int wetness = 0; // 0..5 internal moisture accumulator per Strategy Guide
    public final Set<Integer> axisCapturedHexes = new HashSet<>();
    public final Set<Integer> alliedCapturedHexes = new HashSet<>();
    public final Set<Integer> axisLostHexes = new HashSet<>();
    public final Set<Integer> alliedLostHexes = new HashSet<>();

    public boolean isPlayerTurn = true;
    public int scenarioOutcome = 0; // 0=Ongoing, 1=Decisive Victory, 2=Marginal Victory, 3=Loss

    public final List<PgDatabase.ActiveUnit> units = new ArrayList<>();
    public final List<PgDatabase.ActiveUnit> coreUnits = new ArrayList<>();
    public boolean[][] visibleHexes;
    public UndoMoveState lastUndoState = null;
    public CombatReport lastSurpriseReport = null;
    private final Random rng = new Random();
    private int nextInstanceId = 100;

    public static final String[] CAMPAIGN_NAMES = {
        "1939 Poland (Grand Campaign)",
        "1940 West (Low Countries & France)",
        "1941 Barbarossa (Eastern Front)",
        "1941 North Africa (Afrika Korps)",
        "1943 Husky (Defense of Italy)",
        "1943 Kursk (Citadel & East)"
    };
    public static final int[] CAMPAIGN_START_SCENARIOS = {1, 4, 23, 7, 13, 30};
    public static final int[] CAMPAIGN_START_PRESTIGE = {400, 800, 1200, 900, 1500, 1800};

    public PgGameEngine(PgDatabase db) {
        this.db = db;
    }

    public void startCampaign(int campIdx) throws IOException {
        this.isCampaignMode = true;
        this.campaignIndex = campIdx;
        this.coreUnits.clear();
        int startScen = CAMPAIGN_START_SCENARIOS[campIdx % CAMPAIGN_START_SCENARIOS.length];
        this.axisPrestige = (CAMPAIGN_START_PRESTIGE[campIdx % CAMPAIGN_START_PRESTIGE.length] * prestigeModifier) / 100;
        loadScenario(startScen, true);
    }

    public void startSingleScenario(int scenNum) throws IOException {
        this.isCampaignMode = false;
        this.coreUnits.clear();
        loadScenario(scenNum, false);
    }

    public void loadScenario(int scenNum, boolean carryOverCore) throws IOException {
        this.currentScenarioNumber = scenNum;
        this.scenario = db.loadScenario(scenNum);
        this.turn = 1;
        this.maxTurns = Math.max(10, scenario.meta.turns);
        this.weather = 0;
        this.groundCondition = 0;
        this.wetness = 0;
        this.axisCapturedHexes.clear();
        this.alliedCapturedHexes.clear();
        this.axisLostHexes.clear();
        this.alliedLostHexes.clear();
        this.isPlayerTurn = true;
        this.scenarioOutcome = 0;
        this.lastUndoState = null;
        this.lastSurpriseReport = null;

        if (!carryOverCore) {
            this.axisPrestige = (scenario.meta.axisPrestige * prestigeModifier) / 100;
        } else {
            this.axisPrestige += (Math.max(250, scenario.meta.axisPrestige / 2) * prestigeModifier) / 100;
        }
        this.alliedPrestige = scenario.meta.alliedPrestige;

        this.units.clear();
        for (PgDatabase.ActiveUnit u : scenario.initialUnits) {
            this.units.add(u);
            if (u.instanceId >= nextInstanceId) nextInstanceId = u.instanceId + 1;
            if (isCampaignMode && u.isAxis && u.isCore && coreUnits.size() < 40 && !carryOverCore) {
                coreUnits.add(u);
            }
        }

        // If carrying over core units from previous campaign battle, place them on deployment/friendly city hexes
        if (carryOverCore && !coreUnits.isEmpty()) {
            List<int[]> deploySpots = getAvailableDeployHexes();
            int spotIdx = 0;
            for (PgDatabase.ActiveUnit core : coreUnits) {
                PgDatabase.UnitDef def = db.equipment.get(core.unitDefId);
                core.movePointsUsed = 0;
                core.moved = false;
                core.attacked = false;
                core.suppression = 0;
                core.mountedInTransport = false;
                core.inSeaTransport = false;
                core.inAirTransport = false;
                core.entrenchment = 0;
                core.fuel = def.maxFuel > 0 ? def.maxFuel : 99;
                core.ammo = def.maxAmmo > 0 ? def.maxAmmo : 99;
                if (core.strength < 10) core.strength = Math.min(10, core.strength + 3);
                if (spotIdx < deploySpots.size()) {
                    int[] pos = deploySpots.get(spotIdx++);
                    core.col = pos[0];
                    core.row = pos[1];
                    this.units.add(core);
                }
            }
        }

        visibleHexes = new boolean[scenario.cols][scenario.rows];
        recalculateFogOfWar();
    }

    public boolean isUnitOnAirLayer(PgDatabase.ActiveUnit u) {
        if (u == null) return false;
        if (u.inAirTransport) return true;
        return db.equipment.get(u.unitDefId).isAir();
    }

    public PgDatabase.UnitDef getAirTransportDef() {
        if (89 < db.equipment.size() && db.equipment.get(89).unitClass == PgDatabase.CLASS_AIR_TRANSPORT) {
            return db.equipment.get(89);
        }
        for (PgDatabase.UnitDef d : db.equipment) {
            if (d.unitClass == PgDatabase.CLASS_AIR_TRANSPORT) return d;
        }
        return db.equipment.get(1);
    }

    public PgDatabase.UnitDef getSeaTransportDef() {
        return getSeaTransportDef(true);
    }

    public PgDatabase.UnitDef getSeaTransportDef(boolean isAxis) {
        int preferId = isAxis ? 299 : 291;
        if (preferId < db.equipment.size() && db.equipment.get(preferId).unitClass == PgDatabase.CLASS_SEA_TRANSPORT) {
            return db.equipment.get(preferId);
        }
        for (PgDatabase.UnitDef d : db.equipment) {
            if (d.unitClass == PgDatabase.CLASS_SEA_TRANSPORT && d.iconId > 0) return d;
        }
        return db.equipment.get(Math.min(91, db.equipment.size() - 1));
    }

    public PgDatabase.UnitDef getEffectiveUnitDef(PgDatabase.ActiveUnit u) {
        if (u == null) return null;
        if (u.inAirTransport) return getAirTransportDef();
        if (u.inSeaTransport) return getSeaTransportDef(u.isAxis);
        if (u.mountedInTransport && u.transportDefId > 0 && u.transportDefId < db.equipment.size()) {
            return db.equipment.get(u.transportDefId);
        }
        return db.equipment.get(u.unitDefId);
    }

    public int getEffectiveIconId(PgDatabase.ActiveUnit u) {
        PgDatabase.UnitDef eff = getEffectiveUnitDef(u);
        return eff != null ? eff.iconId : 0;
    }

    public boolean hasFriendlyBridgeEngineer(int col, int row, boolean isAxis) {
        PgDatabase.ActiveUnit occ = getUnitAt(col, row, false);
        if (occ != null && occ.isAxis == isAxis && occ.strength > 0) {
            PgDatabase.UnitDef def = db.equipment.get(occ.unitDefId);
            return def.isBridgeEngineer();
        }
        return false;
    }

    public List<int[]> getAvailableDeployHexes() {
        return getAvailableDeployHexes(0);
    }

    public List<int[]> getAvailableDeployHexes(int unitDefId) {
        List<int[]> res = new ArrayList<>();
        if (scenario == null) return res;

        boolean isAir = (unitDefId > 0 && unitDefId < db.equipment.size())
                && db.equipment.get(unitDefId).isAir();
        boolean[][] added = new boolean[scenario.cols][scenario.rows];

        // 1. Check every city / supply point / objective on the map
        for (int c = 0; c < scenario.cols; c++) {
            for (int r = 0; r < scenario.rows; r++) {
                PgDatabase.HexCell cell = scenario.grid[c][r];
                if (cell.terrainType == 8 || cell.isSupplyPoint() || cell.isObjective) {
                    // Ensure any city currently occupied by an Axis ground unit is marked Axis-controlled
                    PgDatabase.ActiveUnit gndOcc = getUnitAt(c, r, false);
                    if (gndOcc != null && gndOcc.isAxis && !db.isAxisNation(cell.ownerNation, scenario)) {
                        cell.ownerNation = gndOcc.nation;
                    }
                    if (db.isAxisNation(cell.ownerNation, scenario)) {
                        // Allow deploying directly on the controlled city/supply hex itself
                        if (!added[c][r] && canDeployAtHex(c, r, isAir)) {
                            added[c][r] = true;
                            res.add(new int[]{c, r});
                        }
                        // Allow deploying in ANY of the 6 hexagons adjacent to the controlled city
                        for (int[] nb : getNeighbors(c, r)) {
                            int nc = nb[0], nr = nb[1];
                            if (!added[nc][nr] && canDeployAtHex(nc, nr, isAir)) {
                                added[nc][nr] = true;
                                res.add(new int[]{nc, nr});
                            }
                        }
                    }
                }
            }
        }

        // 2. Also include initial scenario deployment hexes
        for (int[] d : scenario.deployHexes) {
            int dc = d[0], dr = d[1];
            if (dc >= 0 && dc < scenario.cols && dr >= 0 && dr < scenario.rows) {
                if (!added[dc][dr] && canDeployAtHex(dc, dr, isAir)) {
                    added[dc][dr] = true;
                    res.add(new int[]{dc, dr});
                }
            }
        }
        return res;
    }

    private boolean canDeployAtHex(int col, int row, boolean isAir) {
        if (col < 0 || col >= scenario.cols || row < 0 || row >= scenario.rows) return false;
        // Target layer (air vs. ground) must be unoccupied
        if (getUnitAt(col, row, isAir) != null) return false;
        // Cannot deploy onto a hex that has an enemy unit on the other layer
        PgDatabase.ActiveUnit otherLayerUnit = getUnitAt(col, row, !isAir);
        if (otherLayerUnit != null && !otherLayerUnit.isAxis) return false;
        // Ground units cannot deploy onto deep ocean hexes
        if (!isAir && scenario.grid[col][row].terrainType == 2) return false;
        return true;
    }

    // Odd-q vertical hex layout neighbors (standard Panzer General hex grid)
    public List<int[]> getNeighbors(int col, int row) {
        int[][] deltas = ((col & 1) == 0)
                ? new int[][]{{0, -1}, {1, -1}, {1, 0}, {0, 1}, {-1, 0}, {-1, -1}}
                : new int[][]{{0, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
        List<int[]> list = new ArrayList<>(6);
        for (int[] d : deltas) {
            int nc = col + d[0];
            int nr = row + d[1];
            if (nc >= 0 && nc < scenario.cols && nr >= 0 && nr < scenario.rows) {
                list.add(new int[]{nc, nr});
            }
        }
        return list;
    }

    public static int hexDistance(int c1, int r1, int c2, int r2) {
        int x1 = c1;
        int z1 = r1 - (c1 - (c1 & 1)) / 2;
        int y1 = -x1 - z1;

        int x2 = c2;
        int z2 = r2 - (c2 - (c2 & 1)) / 2;
        int y2 = -x2 - z2;

        return (Math.abs(x1 - x2) + Math.abs(y1 - y2) + Math.abs(z1 - z2)) / 2;
    }

    public PgDatabase.ActiveUnit getUnitAt(int col, int row, boolean airLayer) {
        for (PgDatabase.ActiveUnit u : units) {
            if (u.strength <= 0) continue;
            if (u.col == col && u.row == row) {
                if (isUnitOnAirLayer(u) == airLayer) return u;
            }
        }
        return null;
    }

    public PgDatabase.ActiveUnit getTopUnitAt(int col, int row, boolean preferAir) {
        PgDatabase.ActiveUnit first = getUnitAt(col, row, preferAir);
        if (first != null) return first;
        return getUnitAt(col, row, !preferAir);
    }

    public boolean isUnitVisibleToPlayer(PgDatabase.ActiveUnit u) {
        if (u == null || u.strength <= 0) return false;
        if (!fogOfWarEnabled || u.isAxis) return true;
        if (visibleHexes == null || !visibleHexes[u.col][u.row]) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (def.unitClass == PgDatabase.CLASS_SUBMARINE) {
            // Authentic Panzer General Rule (Strategy Guide p. 2484):
            // Adjacent submarines are always spotted.
            // At range > 1, 50% chance of remaining submerged/unseen unless an adjacent friendly unit exists.
            boolean hasAdjacentFriendly = false;
            for (int[] nb : getNeighbors(u.col, u.row)) {
                PgDatabase.ActiveUnit adj = getUnitAt(nb[0], nb[1], false);
                if (adj != null && adj.isAxis && adj.strength > 0) {
                    hasAdjacentFriendly = true;
                    break;
                }
            }
            if (!hasAdjacentFriendly) {
                int hash = (turn * 37 + u.instanceId * 23 + u.col * 7 + u.row) & 0x7FFFFFFF;
                if ((hash % 100) >= 50) return false;
            }
        }
        return true;
    }

    public Set<Integer> getSpottedEnemyIds() {
        Set<Integer> set = new HashSet<>();
        if (scenario == null || visibleHexes == null) return set;
        for (PgDatabase.ActiveUnit u : units) {
            if (!u.isAxis && u.strength > 0 && isUnitVisibleToPlayer(u)) {
                set.add(u.instanceId);
            }
        }
        return set;
    }

    public void recalculateFogOfWar() {
        if (scenario == null) return;
        for (int c = 0; c < scenario.cols; c++) {
            for (int r = 0; r < scenario.rows; r++) {
                if (!fogOfWarEnabled) {
                    visibleHexes[c][r] = true;
                } else {
                    PgDatabase.HexCell cell = scenario.grid[c][r];
                    visibleHexes[c][r] = db.isAxisNation(cell.ownerNation, scenario);
                }
            }
        }
        if (!fogOfWarEnabled) return;
        for (PgDatabase.ActiveUnit u : units) {
            if (!u.isAxis || u.strength <= 0) continue;
            PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
            int spot = def.spotting;
            if (weatherEnabled) {
                if (weather == 1) { // Overcast: Air spotting halved, ground spotting -1
                    if (def.isAir() || u.inAirTransport) {
                        spot = Math.max(1, (spot + 1) / 2);
                    } else {
                        spot = Math.max(1, spot - 1);
                    }
                } else if (weather == 2) { // Rain / Snow: Air spotting 1, ground spotting halved
                    if (def.isAir() || u.inAirTransport) {
                        spot = 1;
                    } else {
                        spot = Math.max(1, spot / 2);
                    }
                }
            }
            for (int c = Math.max(0, u.col - spot - 1); c <= Math.min(scenario.cols - 1, u.col + spot + 1); c++) {
                for (int r = Math.max(0, u.row - spot - 1); r <= Math.min(scenario.rows - 1, u.row + spot + 1); r++) {
                    if (hexDistance(u.col, u.row, c, r) <= spot) {
                        visibleHexes[c][r] = true;
                    }
                }
            }
        }
    }

    public int getMaxMovementPoints(PgDatabase.ActiveUnit u) {
        if (u == null || u.strength <= 0) return 0;
        if (u.inAirTransport) {
            return Math.max(10, getAirTransportDef().movement);
        }
        if (u.inSeaTransport) {
            return Math.max(4, getSeaTransportDef(u.isAxis).movement);
        }
        if (u.mountedInTransport && u.transportDefId > 0 && u.transportDefId < db.equipment.size()) {
            PgDatabase.UnitDef trp = db.equipment.get(u.transportDefId);
            return trp.movement;
        }
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        return def.movement;
    }

    public int getRemainingMovementPoints(PgDatabase.ActiveUnit u) {
        if (u == null || u.moved || u.strength <= 0) return 0;
        PgDatabase.UnitDef effDef = getEffectiveUnitDef(u);
        int remMove = Math.max(0, getMaxMovementPoints(u) - u.movePointsUsed);
        if (supplyEnabled && effDef != null && effDef.maxFuel > 0 && !u.inAirTransport && !u.inSeaTransport) {
            remMove = Math.min(remMove, u.fuel);
        }
        return remMove;
    }

    public Map<Integer, Integer> getReachableHexes(PgDatabase.ActiveUnit u) {
        Map<Integer, Integer> distMap = new HashMap<>();
        if (u == null || u.moved || u.strength <= 0) return distMap;
        PgDatabase.UnitDef def = getEffectiveUnitDef(u);
        int maxMove = getRemainingMovementPoints(u);
        if (maxMove <= 0) return distMap;

        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[2]));
        int startKey = u.row * scenario.cols + u.col;
        distMap.put(startKey, 0);
        pq.add(new int[]{u.col, u.row, 0});

        boolean isAir = isUnitOnAirLayer(u);

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int c = cur[0], r = cur[1], costSoFar = cur[2];
            if (costSoFar > distMap.getOrDefault(r * scenario.cols + c, 999)) continue;

            // Check Enemy Zone of Control (ZOC): entering an enemy ZOC halts further movement along that path
            // Air units do not suffer from or exert ZOC
            if (!isAir && (c != u.col || r != u.row) && isInEnemyZoc(c, r, u)) {
                continue;
            }

            for (int[] nb : getNeighbors(c, r)) {
                int nc = nb[0], nr = nb[1];
                PgDatabase.HexCell cell = scenario.grid[nc][nr];
                int stepCost = getUnitStepMoveCost(u, def, cell);
                if (stepCost >= 99) continue;

                // Cannot move through visible enemy units on the same layer
                PgDatabase.ActiveUnit blocker = getUnitAt(nc, nr, isAir);
                if (blocker != null && blocker.isAxis != u.isAxis && (!fogOfWarEnabled || visibleHexes[nc][nr])) {
                    continue;
                }

                boolean curIsWater = scenario.grid[c][r].isWater();
                boolean nextIsWater = cell.isWater();

                // 1. Sea Transport disembarkation: moving from water onto land consumes all MP and halts further movement
                if (u.inSeaTransport && curIsWater && !nextIsWater) {
                    if (getTerrainMoveCost(def, cell, false) >= 99) continue;
                    int nKey = nr * scenario.cols + nc;
                    distMap.put(nKey, maxMove);
                    continue; // Disembarking ends movement along this path
                }

                // 2. Friendly Port embarkation: moving from friendly port into adjacent water
                boolean startInFriendlyPort = (c == u.col && r == u.row)
                        && scenario.grid[c][r].isPort()
                        && db.isAxisNation(scenario.grid[c][r].ownerNation, scenario) == u.isAxis
                        && !def.isAir() && !def.isNaval();
                if (!u.inSeaTransport && startInFriendlyPort && nextIsWater) {
                    int nKey = nr * scenario.cols + nc;
                    distMap.put(nKey, maxMove);
                    continue; // Embarking ends movement along this path
                }

                int newCost = costSoFar + stepCost;
                // Allow mobile units to always make at least 1 step into an adjacent passable hex
                if (costSoFar == 0 && maxMove >= 1 && stepCost < 99 && newCost > maxMove) {
                    newCost = maxMove;
                }

                int nKey = nr * scenario.cols + nc;
                if (newCost <= maxMove && newCost < distMap.getOrDefault(nKey, 999)) {
                    distMap.put(nKey, newCost);
                    pq.add(new int[]{nc, nr, newCost});
                }
            }
        }
        // Remove hexes currently occupied by friendly units on the same layer
        Iterator<Map.Entry<Integer, Integer>> it = distMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> entry = it.next();
            int key = entry.getKey();
            int hc = key % scenario.cols;
            int hr = key / scenario.cols;
            PgDatabase.ActiveUnit occ = getUnitAt(hc, hr, isAir);
            if (occ != null && occ != u) {
                it.remove();
            }
        }
        return distMap;
    }

    public boolean isInEnemyZoc(int col, int row, PgDatabase.ActiveUnit mover) {
        if (mover == null || isUnitOnAirLayer(mover)) return false;
        PgDatabase.UnitDef mDef = db.equipment.get(mover.unitDefId);
        for (int[] nb : getNeighbors(col, row)) {
            PgDatabase.ActiveUnit adj = getUnitAt(nb[0], nb[1], false);
            if (adj != null && adj.isAxis != mover.isAxis && adj.strength > 0) {
                if (!fogOfWarEnabled || visibleHexes[nb[0]][nb[1]]) {
                    // Submarine rule: Submarines ignore surface ship ZOC unless adjacent to a Destroyer
                    if (mDef.unitClass == PgDatabase.CLASS_SUBMARINE) {
                        PgDatabase.UnitDef adjDef = db.equipment.get(adj.unitDefId);
                        if (adjDef.unitClass != PgDatabase.CLASS_DESTROYER) continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isInEnemyZoc(int col, int row, boolean moverIsAxis) {
        for (int[] nb : getNeighbors(col, row)) {
            PgDatabase.ActiveUnit adj = getUnitAt(nb[0], nb[1], false);
            if (adj != null && adj.isAxis != moverIsAxis && adj.strength > 0) {
                if (!fogOfWarEnabled || visibleHexes[nb[0]][nb[1]]) {
                    return true;
                }
            }
        }
        return false;
    }

    private int getUnitStepMoveCost(PgDatabase.ActiveUnit u, PgDatabase.UnitDef def, PgDatabase.HexCell cell) {
        if (u.inAirTransport || def.isAir()) return 1;
        if (u.inSeaTransport) {
            if (cell.isWater() || cell.isPort()) return 1;
            // Entering land to disembark: check if ground terrain is passable
            if (getTerrainMoveCost(def, cell, false) < 99) return 1;
            return 99;
        }
        if (def.isNaval()) {
            return (cell.isWater() || cell.isPort()) ? 1 : 99;
        }
        // Check Bridge Engineer pontoon bridge on River hexes
        if (cell.terrainType == 4 && hasFriendlyBridgeEngineer(cell.col, cell.row, u.isAxis)) {
            return 1;
        }
        if (cell.isWater()) return 99;
        return getTerrainMoveCost(def, cell, u.mountedInTransport);
    }

    private int getTerrainMoveCost(PgDatabase.UnitDef def, PgDatabase.HexCell cell, boolean mounted) {
        if (def.isAir()) return 1;
        if (def.isNaval()) {
            return (cell.isWater() || cell.isPort()) ? 1 : 99;
        }
        if (cell.isWater()) return 99; // Water impassable without naval transport
        if (cell.roadFlags != 0) return 1; // Road / permanent bridge movement
        int mudPenalty = (weatherEnabled && groundCondition == 1) ? 1 : 0;
        switch (cell.terrainType) {
            case 0: case 1: case 3: case 6: case 8: case 11:
                return 1 + mudPenalty;
            case 4: // Unbridged River
                return (def.unitClass == PgDatabase.CLASS_INFANTRY && !mounted) ? 2 : 3;
            case 9: case 10: case 12: case 13: // Rough, Forest, Fort, Bocage
                return (def.unitClass == PgDatabase.CLASS_INFANTRY && !mounted) ? 1 + mudPenalty : 2 + mudPenalty;
            case 7: // Swamp
                return (def.unitClass == PgDatabase.CLASS_INFANTRY && !mounted) ? 2 : 4;
            case 5: // Mountain
                return (def.unitClass == PgDatabase.CLASS_INFANTRY && !mounted) ? 2 : 99;
            default:
                return 1 + mudPenalty;
        }
    }

    public List<PgDatabase.ActiveUnit> getValidTargets(PgDatabase.ActiveUnit attacker) {
        List<PgDatabase.ActiveUnit> targets = new ArrayList<>();
        if (attacker == null || attacker.attacked || attacker.strength <= 0
                || attacker.inAirTransport || attacker.inSeaTransport
                || (supplyEnabled && attacker.ammo <= 0)) {
            return targets;
        }
        PgDatabase.UnitDef aDef = getEffectiveUnitDef(attacker);
        boolean aAir = isUnitOnAirLayer(attacker);

        for (PgDatabase.ActiveUnit u : units) {
            if (u.strength <= 0 || u.isAxis == attacker.isAxis) continue;
            if (fogOfWarEnabled && !isUnitVisibleToPlayer(u)) continue;
            int dist = hexDistance(attacker.col, attacker.row, u.col, u.row);
            boolean dAir = isUnitOnAirLayer(u);
            boolean dNaval = u.inSeaTransport || db.equipment.get(u.unitDefId).isNaval();
            PgDatabase.UnitDef dDef = getEffectiveUnitDef(u);
            if (canAttackTarget(attacker, aDef, aAir, u, dDef, dAir, dNaval, dist)) {
                targets.add(u);
            }
        }
        return targets;
    }

    public boolean canAttackTarget(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef, boolean aAir,
                                   PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef, boolean dAir, boolean dNaval, int dist) {
        if (weatherEnabled && weather == 2 && (aAir || dAir)) {
            // Storm grounds air attacks when Weather Rules are ON
            return false;
        }
        if (attacker.inAirTransport || attacker.inSeaTransport) return false;
        if (aDef.unitClass == PgDatabase.CLASS_AIR_TRANSPORT
                || aDef.unitClass == PgDatabase.CLASS_SEA_TRANSPORT) {
            return false;
        }
        // If mounted in an unarmed transport (e.g. truck), it cannot attack
        if (aDef.softAttack <= 0 && aDef.hardAttack <= 0 && aDef.airAttack <= 0 && aDef.navalAttack <= 0) {
            return false;
        }

        if (dAir) {
            // Target is in the air (enemy plane or air transport)
            if (aAir) {
                // Plane vs Plane: Fighters and Tac Bombers attack adjacent air hexes
                if (aDef.unitClass == PgDatabase.CLASS_LEVEL_BOMBER) return false;
                return dist == 1 && aDef.airAttack > 0;
            } else if (aDef.unitClass == PgDatabase.CLASS_AIR_DEFENSE) {
                // Ground Air Defense (Flak): attacks planes overhead (dist 0) or within range (dist <= range)
                return dist <= Math.max(1, aDef.range) && aDef.airAttack > 0;
            } else if (aDef.unitClass == PgDatabase.CLASS_ANTI_AIR) {
                // Ground Mobile Anti-Air: attacks planes overhead (dist 0) or adjacent (dist 1)
                return dist <= 1 && aDef.airAttack > 0;
            }
            return false;
        } else {
            // Target is on ground or sea
            // Authentic ASW Rule (Chapter 4): Only Destroyers and Tactical Bombers can attack Submarines!
            if (dDef.unitClass == PgDatabase.CLASS_SUBMARINE) {
                if (aAir) {
                    return aDef.unitClass == PgDatabase.CLASS_TAC_BOMBER && dist == 0;
                } else {
                    return aDef.unitClass == PgDatabase.CLASS_DESTROYER && dist == 1;
                }
            }
            if (aAir) {
                // CRITICAL RULE: Planes can ONLY attack ground/naval units directly BENEATH them (dist == 0),
                // and CANNOT attack ground units next to them (dist >= 1)
                if (dist != 0) return false;
                if (dNaval) {
                    if (defender.inSeaTransport) return aDef.navalAttack > 0 || aDef.softAttack > 0 || aDef.hardAttack > 0;
                    return aDef.navalAttack > 0;
                }
                return aDef.softAttack > 0 || aDef.hardAttack > 0;
            }

            // Attacker is ground or naval
            if (aDef.unitClass == PgDatabase.CLASS_AIR_DEFENSE) {
                return false; // Air defense only fires at air units
            }
            if (aDef.unitClass == PgDatabase.CLASS_SUBMARINE) {
                return dist == 1 && dNaval && aDef.navalAttack > 0;
            }
            if (aDef.unitClass == PgDatabase.CLASS_DESTROYER) {
                if (dNaval) return dist == 1 && aDef.navalAttack > 0;
                return dist == 1 && (aDef.softAttack > 0 || aDef.hardAttack > 0);
            }
            if (aDef.unitClass == PgDatabase.CLASS_CAPITAL_SHIP) {
                if (dDef.unitClass == PgDatabase.CLASS_SUBMARINE) return false;
                return dist >= 1 && dist <= Math.max(1, aDef.range);
            }
            if (aDef.unitClass == PgDatabase.CLASS_ARTILLERY || aDef.unitClass == PgDatabase.CLASS_FORT) {
                return dist >= 1 && dist <= Math.max(1, aDef.range);
            }
            // Infantry, Tanks, Recon, Anti-Tank, Mobile AA: attack adjacent ground units or adjacent sea transports
            if (dNaval && !defender.inSeaTransport) return false;
            return dist == 1 && (aDef.softAttack > 0 || aDef.hardAttack > 0 || aDef.navalAttack > 0);
        }
    }

    public boolean canUndoLastMove() {
        return isPlayerTurn && lastUndoState != null && lastUndoState.unit != null
                && units.contains(lastUndoState.unit) && !lastUndoState.unit.attacked;
    }

    public PgDatabase.ActiveUnit undoLastMove() {
        if (!canUndoLastMove()) return null;
        PgDatabase.ActiveUnit u = lastUndoState.unit;
        u.col = lastUndoState.prevCol;
        u.row = lastUndoState.prevRow;
        u.fuel = lastUndoState.prevFuel;
        u.movePointsUsed = lastUndoState.prevMovePointsUsed;
        u.moved = lastUndoState.prevMoved;
        u.entrenchment = lastUndoState.prevEntrenchment;
        u.facingLeft = lastUndoState.prevFacingLeft;
        scenario.grid[lastUndoState.destCol][lastUndoState.destRow].ownerNation = lastUndoState.prevDestOwnerNation;
        axisPrestige = lastUndoState.prevAxisPrestige;
        lastUndoState = null;
        recalculateFogOfWar();
        return u;
    }

    public boolean moveUnit(PgDatabase.ActiveUnit u, int destCol, int destRow) {
        lastSurpriseReport = null;
        Map<Integer, Integer> reach = getReachableHexes(u);
        int key = destRow * scenario.cols + destCol;
        if (!reach.containsKey(key)) return false;

        int cost = reach.get(key);
        PgDatabase.UnitDef def = getEffectiveUnitDef(u);
        boolean airLayer = isUnitOnAirLayer(u);

        // Check for Surprise Contact (Ambush!) with hidden enemy unit at destination
        PgDatabase.ActiveUnit hidden = getUnitAt(destCol, destRow, airLayer);
        if (hidden != null && hidden.isAxis != u.isAxis) {
            lastUndoState = null;
            u.movePointsUsed += Math.max(1, cost);
            u.moved = true;
            recalculateFogOfWar();
            // Hidden enemy ambushes the moving unit with Surprise Contact!
            CombatReport ambush = executeCombatInternal(u, hidden, true);
            lastSurpriseReport = ambush;
            return true;
        }

        Set<Integer> prevSpotted = getSpottedEnemyIds();

        UndoMoveState undo = UndoMoveState.builder()
                .unit(u)
                .prevCol(u.col)
                .prevRow(u.row)
                .prevFuel(u.fuel)
                .prevMovePointsUsed(u.movePointsUsed)
                .prevMoved(u.moved)
                .prevEntrenchment(u.entrenchment)
                .prevFacingLeft(u.facingLeft)
                .destCol(destCol)
                .destRow(destRow)
                .prevDestOwnerNation(scenario.grid[destCol][destRow].ownerNation)
                .prevAxisPrestige(axisPrestige)
                .build();

        boolean wasSeaTransport = u.inSeaTransport;
        PgDatabase.HexCell destCell = scenario.grid[destCol][destRow];

        if (destCol < u.col) u.facingLeft = true;
        else if (destCol > u.col) u.facingLeft = false;

        u.col = destCol;
        u.row = destRow;
        u.movePointsUsed += Math.max(1, cost);
        u.entrenchment = 0;
        if (supplyEnabled && def.maxFuel > 0 && !u.inAirTransport && !u.inSeaTransport) {
            u.fuel = Math.max(0, u.fuel - Math.max(1, cost));
        }

        // Disembarking onto land / port hex
        if (wasSeaTransport && !destCell.isWater()) {
            u.inSeaTransport = false;
            u.moved = true;
            u.attacked = true; // landing ends turn
            u.movePointsUsed = getMaxMovementPoints(u);
        } else if (!wasSeaTransport && !airLayer && !def.isNaval() && destCell.isWater()) {
            // Embarking from port into water
            u.inSeaTransport = true;
            u.mountedInTransport = false;
            u.moved = true;
            u.attacked = true; // embarking ends turn
            u.movePointsUsed = getMaxMovementPoints(u);
        }

        // Capture city / objective / airfield / port if ground unit moves onto it
        if (!airLayer && !u.inSeaTransport && !def.isNaval()) {
            onHexCaptured(destCol, destRow, u);
        }

        recalculateFogOfWar();
        Set<Integer> afterSpotted = getSpottedEnemyIds();
        boolean revealedNewEnemy = ! prevSpotted.containsAll(afterSpotted);
        if (u.isAxis && !revealedNewEnemy && !u.attacked) {
            lastUndoState = undo;
        } else {
            lastUndoState = null;
        }

        // Every unit can move once per turn
        u.moved = true;
        checkVictoryConditions();
        return true;
    }

    public boolean isRiverHex(int col, int row) {
        if (scenario == null || col < 0 || col >= scenario.cols || row < 0 || row >= scenario.rows) return false;
        PgDatabase.HexCell cell = scenario.grid[col][row];
        return cell.terrainType == 4 && cell.roadFlags == 0;
    }

    public void onHexCaptured(int col, int row, PgDatabase.ActiveUnit capturer) {
        if (scenario == null || col < 0 || col >= scenario.cols || row < 0 || row >= scenario.rows || capturer == null) return;
        PgDatabase.HexCell cell = scenario.grid[col][row];
        boolean isCityPortAirfield = (cell.terrainType == 8 || cell.terrainType == 6 || cell.isPort());
        if (!cell.isObjective && !isCityPortAirfield && cell.ownerNation == 0) return;

        boolean wasEnemy = db.isAxisNation(cell.ownerNation, scenario) != capturer.isAxis;
        cell.ownerNation = capturer.nation;

        // Strategy Guide Chapter 6 Initial Capture Prestige (+160/+40 to capturer, -80/-20 to loser)
        if (wasEnemy) {
            int hexKey = (col << 8) | row;
            if (capturer.isAxis) {
                if (!axisCapturedHexes.contains(hexKey)) {
                    axisCapturedHexes.add(hexKey);
                    int gain = cell.isObjective ? 160 : 40;
                    axisPrestige += gain;
                }
                if (!alliedLostHexes.contains(hexKey)) {
                    alliedLostHexes.add(hexKey);
                    int loss = cell.isObjective ? 80 : 20;
                    alliedPrestige = Math.max(0, alliedPrestige - loss);
                }
            } else {
                if (!alliedCapturedHexes.contains(hexKey)) {
                    alliedCapturedHexes.add(hexKey);
                    int gain = cell.isObjective ? 160 : 40;
                    alliedPrestige += gain;
                }
                if (!axisLostHexes.contains(hexKey)) {
                    axisLostHexes.add(hexKey);
                    int loss = cell.isObjective ? 80 : 20;
                    axisPrestige = Math.max(0, axisPrestige - loss);
                }
            }
        }
    }

    public int getStrategyGuideAttackGrade(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef,
                                          PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef,
                                          int dist, boolean stiffDefense) {
        if (supplyEnabled && attacker.ammo <= 0) return 0;
        if (weatherEnabled && weather == 2 && (isUnitOnAirLayer(attacker) || isUnitOnAirLayer(defender))) return 0;
        if (aDef.unitClass == PgDatabase.CLASS_AIR_DEFENSE && !isUnitOnAirLayer(defender)) return 0;

        int base;
        if (isUnitOnAirLayer(defender)) {
            base = aDef.airAttack;
        } else if (defender.inSeaTransport || dDef.isNaval()) {
            base = Math.max(aDef.navalAttack, defender.inSeaTransport ? Math.max(aDef.softAttack, aDef.hardAttack) : 0);
        } else {
            base = (dDef.targetType == PgDatabase.TARGET_HARD) ? aDef.hardAttack : aDef.softAttack;
        }
        if (base <= 0) return 0;

        // +1 per experience star
        int grade = base + attacker.getStars();

        // +4 if defender is on unbridged river hex (and not an airplane)
        if (!isUnitOnAirLayer(defender) && !defender.inSeaTransport && !dDef.isNaval()
                && isRiverHex(defender.col, defender.row)
                && !dDef.isBridgeEngineer()
                && !hasFriendlyBridgeEngineer(defender.col, defender.row, defender.isAxis)) {
            grade += 4;
        }

        // +4 if mounting stiff defense
        if (stiffDefense) {
            grade += 4;
        }

        return grade;
    }

    public int getStrategyGuideDefenseGrade(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef,
                                           PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef,
                                           int dist, boolean stiffDefense) {
        int def;
        if (isUnitOnAirLayer(attacker) || isUnitOnAirLayer(defender)) {
            def = dDef.airDefense;
        } else if (aDef.unitClass == PgDatabase.CLASS_SUBMARINE && (dDef.isNaval() || defender.inSeaTransport)) {
            // Authentic Torpedo Defense: ships defend against torpedoes using Close Defense
            def = Math.max(1, dDef.closeDefense);
        } else {
            PgDatabase.HexCell cell = scenario.grid[defender.col][defender.row];
            boolean closeCombat = (cell.isCloseDefenseTerrain() && aDef.unitClass == PgDatabase.CLASS_INFANTRY && dDef.unitClass != PgDatabase.CLASS_INFANTRY)
                    || stiffDefense;
            def = closeCombat ? Math.max(1, dDef.closeDefense) : dDef.groundDefense;
        }

        // Defensive Grade Modifiers (Cumulative) per Strategy Guide Chapter 5:
        // + entrenchment: full entrenchment if attacker is non-infantry, 1/2 entrenchment if non-engineer infantry, 0 if pioneer/engineer
        // Mounted units receive 0 entrenchment bonus
        if (!isUnitOnAirLayer(defender) && !isUnitOnAirLayer(attacker) && !defender.mountedInTransport) {
            if (aDef.isPioneerOrEngineer()) {
                // Pioneers/Engineers ignore entrenchment
            } else if (aDef.unitClass == PgDatabase.CLASS_INFANTRY) {
                def += defender.entrenchment / 2;
            } else {
                def += defender.entrenchment;
            }
        }

        // +8 for all ground units versus naval units
        if (!isUnitOnAirLayer(defender) && (aDef.isNaval() || attacker.inSeaTransport)) {
            def += 8;
        }

        // +3 versus ranged attacks when it's raining or snowing
        if (weatherEnabled && weather == 2 && dist > 1) {
            def += 3;
        }

        // +1 for each level of experience the unit enjoys
        def += defender.getStars();

        // +4 if the opponent (attacker) is not an airplane and is on an unbridged river hex
        if (!isUnitOnAirLayer(attacker) && !attacker.inSeaTransport && !aDef.isNaval()
                && isRiverHex(attacker.col, attacker.row)
                && !aDef.isBridgeEngineer()
                && !hasFriendlyBridgeEngineer(attacker.col, attacker.row, attacker.isAxis)) {
            def += 4;
        }

        // +4 if the unit is defending in stiff defense
        if (stiffDefense) {
            def += 4;
        }

        // +2 for infantry versus anti-tank guns
        if (dDef.unitClass == PgDatabase.CLASS_INFANTRY && aDef.unitClass == PgDatabase.CLASS_ANTI_TANK) {
            def += 2;
        }

        return Math.max(1, def);
    }

    public int calculateEffectiveDifference(int attackGrade, int defenseGrade) {
        int diff = attackGrade - defenseGrade;
        if (diff > 4) {
            return 4 + ((diff - 4) * 2) / 5;
        }
        return diff;
    }

    public int getEffectiveFiringStrength(PgDatabase.ActiveUnit u, PgDatabase.UnitDef def, boolean isAttacker) {
        int str = Math.max(0, u.strength - u.suppression);
        if (str <= 0) return 0;

        // Out of fuel halves vehicle combat strength (Chapter 3)
        if (supplyEnabled && def.maxFuel > 0 && !u.inAirTransport && !u.inSeaTransport && u.fuel <= 0) {
            str = (str + 1) / 2;
        }

        // Overcast halves air combat strengths (Chapter 3)
        if (weatherEnabled && weather == 1 && (def.isAir() || u.inAirTransport)) {
            str = (str + 1) / 2;
        }

        // Mud halves ground attacking strength (Chapter 3)
        if (weatherEnabled && groundCondition == 1 && isAttacker && !def.isAir() && !u.inAirTransport) {
            str = (str + 1) / 2;
        }

        return Math.max(1, str);
    }

    public boolean isBombardment(PgDatabase.UnitDef def, int dist) {
        if (def == null) return false;
        return def.unitClass == PgDatabase.CLASS_ARTILLERY
                || def.unitClass == PgDatabase.CLASS_LEVEL_BOMBER
                || def.unitClass == PgDatabase.CLASS_FORT
                || def.isNaval()
                || dist > 1;
    }

    public double getD20HitProbability(int effDiff, boolean isBombardment) {
        if (isBombardment) {
            int minRoll = Math.max(1, Math.min(21, 19 - effDiff));
            return (21 - minRoll) / 20.0;
        } else {
            int minRoll = Math.max(1, Math.min(21, 13 - effDiff));
            return (21 - minRoll) / 20.0;
        }
    }

    public double getD20SuppProbability(int effDiff, boolean isBombardment) {
        if (isBombardment) {
            int minRoll = Math.max(1, Math.min(21, 11 - effDiff));
            int maxRoll = Math.max(0, Math.min(20, 18 - effDiff));
            return (maxRoll >= minRoll) ? (maxRoll - minRoll + 1) / 20.0 : 0.0;
        } else {
            int minRoll = Math.max(1, Math.min(21, 11 - effDiff));
            int maxRoll = Math.max(0, Math.min(20, 12 - effDiff));
            return (maxRoll >= minRoll) ? (maxRoll - minRoll + 1) / 20.0 : 0.0;
        }
    }

    public boolean canReturnFire(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef,
                                 PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef, int dist) {
        if (supplyEnabled && dDef.maxAmmo > 0 && defender.ammo <= 0) return false;
        // Submarines: when submarine attacks, only Destroyers can return fire; Capital Ships, Carriers, Transports cannot return fire!
        if (aDef.unitClass == PgDatabase.CLASS_SUBMARINE && dDef.unitClass != PgDatabase.CLASS_DESTROYER) {
            return false;
        }
        if (dDef.unitClass == PgDatabase.CLASS_SUBMARINE && aDef.unitClass != PgDatabase.CLASS_DESTROYER) {
            return false;
        }
        if (dist > 1) {
            return aDef.unitClass == PgDatabase.CLASS_CAPITAL_SHIP && dDef.unitClass == PgDatabase.CLASS_CAPITAL_SHIP;
        }
        boolean aAir = isUnitOnAirLayer(attacker);
        boolean dAir = isUnitOnAirLayer(defender);
        if (aAir && !dAir) {
            return dist == 0 && dDef.airAttack > 0;
        }
        if (!aAir && dAir) {
            return false;
        }
        if (aAir && dAir) {
            return dist == 1 && dDef.airAttack > 0 && dDef.unitClass != PgDatabase.CLASS_LEVEL_BOMBER;
        }
        if (aDef.unitClass == PgDatabase.CLASS_ARTILLERY) {
            return false;
        }
        int returnAtk = (aDef.targetType == PgDatabase.TARGET_HARD) ? dDef.hardAttack : dDef.softAttack;
        if (returnAtk <= 0 && dDef.airAttack <= 0) {
            return false;
        }
        return dist == 1;
    }

    private int[] resolveD20Fire(int firingStr, int effDiff, boolean isBombardment) {
        int hits = 0;
        int supp = 0;
        for (int i = 0; i < firingStr; i++) {
            int roll = rng.nextInt(20) + 1;
            int total = roll + effDiff;
            if (isBombardment) {
                if (total >= 19) hits++;
                else if (total >= 11) supp++;
            } else {
                if (total >= 13) hits++;
                else if (total >= 11) supp++;
            }
        }
        return new int[]{hits, supp};
    }

    public CombatPrediction predictCombat(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        PgDatabase.UnitDef aDef = getEffectiveUnitDef(attacker);
        PgDatabase.UnitDef dDef = getEffectiveUnitDef(defender);
        int dist = hexDistance(attacker.col, attacker.row, defender.col, defender.row);

        boolean isGroundClose = (dist == 1 && !isUnitOnAirLayer(attacker) && !isUnitOnAirLayer(defender));
        boolean ruggedDefenseChance = (isGroundClose && defender.entrenchment >= 1 && !aDef.isPioneerOrEngineer());

        int atkAttackGrade = getStrategyGuideAttackGrade(attacker, aDef, defender, dDef, dist, false);
        int defDefenseGrade = getStrategyGuideDefenseGrade(attacker, aDef, defender, dDef, dist, false);

        boolean defenderCanReturnFire = canReturnFire(attacker, aDef, defender, dDef, dist);
        int defAttackGrade = 0;
        int atkDefenseGrade = 0;
        if (defenderCanReturnFire) {
            defAttackGrade = getStrategyGuideAttackGrade(defender, dDef, attacker, aDef, dist, false);
            atkDefenseGrade = getStrategyGuideDefenseGrade(defender, dDef, attacker, aDef, dist, false);
            if (dDef.unitClass == PgDatabase.CLASS_ARTILLERY) {
                atkDefenseGrade += 3;
            }
        }

        int atkEffDiff = calculateEffectiveDifference(atkAttackGrade, defDefenseGrade);
        int defEffDiff = calculateEffectiveDifference(defAttackGrade, atkDefenseGrade);

        int atkStr = getEffectiveFiringStrength(attacker, aDef, true);
        int defStr = getEffectiveFiringStrength(defender, dDef, false);

        PgDatabase.HexCell defCell = scenario.grid[defender.col][defender.row];
        int iniCap = (dist == 1 && !aDef.isAir() && !dDef.isAir()) ? defCell.getInitiativeCap() : 99;
        int aIni = Math.min(iniCap, aDef.initiative) + attacker.getStars();
        int dIni = Math.min(iniCap, dDef.initiative) + defender.getStars();
        if (dist == 1 && defCell.isCloseDefenseTerrain()) {
            if (aDef.unitClass == PgDatabase.CLASS_INFANTRY) aIni += 2;
            if (dDef.unitClass == PgDatabase.CLASS_INFANTRY) dIni += 2;
        }
        if (aDef.unitClass == PgDatabase.CLASS_SUBMARINE) {
            aIni += 99; // Submarines shoot first when attacking on their turn
        }

        boolean aBomb = isBombardment(aDef, dist);
        boolean dBomb = isBombardment(dDef, dist);

        double atkPHit = (atkAttackGrade > 0) ? getD20HitProbability(atkEffDiff, aBomb) : 0.0;
        double atkPSupp = (atkAttackGrade > 0) ? getD20SuppProbability(atkEffDiff, aBomb) : 0.0;
        double defPHit = (defAttackGrade > 0) ? getD20HitProbability(defEffDiff, dBomb) : 0.0;

        int iniDiff = aIni - dIni;
        double expDefLosses = 0.0;
        double expAtkLosses = 0.0;

        if (iniDiff > 0) {
            expDefLosses = Math.min(defender.strength, atkStr * atkPHit);
            double defSuppressed = atkStr * atkPSupp;
            double remDefStr = Math.max(0.0, defStr - expDefLosses - defSuppressed);
            if (defenderCanReturnFire) {
                expAtkLosses = Math.min(attacker.strength, remDefStr * defPHit);
            }
        } else if (iniDiff < 0) {
            if (defenderCanReturnFire) {
                expAtkLosses = Math.min(attacker.strength, defStr * defPHit);
                double remAtkStr = Math.max(0.0, atkStr - expAtkLosses);
                expDefLosses = Math.min(defender.strength, remAtkStr * atkPHit);
            } else {
                expDefLosses = Math.min(defender.strength, atkStr * atkPHit);
            }
        } else {
            expDefLosses = Math.min(defender.strength, atkStr * atkPHit);
            if (defenderCanReturnFire) {
                expAtkLosses = Math.min(attacker.strength, defStr * defPHit);
            }
        }

        if (dDef.unitClass == PgDatabase.CLASS_SUBMARINE) {
            double evadeFactor = (aDef.unitClass == PgDatabase.CLASS_TAC_BOMBER) ? 0.25 : 0.50;
            expDefLosses *= evadeFactor;
        }

        int expectedDefenderLosses = (int) Math.round(expDefLosses);
        int expectedAttackerLosses = (int) Math.round(expAtkLosses);
        boolean artillerySupport = (findDefensiveSupport(attacker, defender) != null);
        boolean fighterInterceptor = (findAirInterceptorOrFlak(attacker, defender) != null);
        String summary = String.format(Locale.ROOT, "Expected: Atk -%d vs Def -%d (Ini %d:%d)%s%s",
                expectedAttackerLosses, expectedDefenderLosses,
                aIni, dIni,
                ruggedDefenseChance ? " [Rugged Def Risk]" : "",
                fighterInterceptor ? " [Interceptor]" : (artillerySupport ? " [Artillery Sup]" : ""));

        return CombatPrediction.builder()
                .effectiveAttackerIni(aIni)
                .effectiveDefenderIni(dIni)
                .ruggedDefenseChance(ruggedDefenseChance)
                .expectedDefenderLosses(expectedDefenderLosses)
                .expectedAttackerLosses(expectedAttackerLosses)
                .artillerySupport(artillerySupport)
                .fighterInterceptor(fighterInterceptor)
                .summary(summary)
                .build();
    }

    public CombatReport executeCombat(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        lastUndoState = null;
        return executeCombatInternal(attacker, defender, false);
    }

    private CombatReport executeCombatInternal(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender, boolean surpriseAmbush) {
        PgDatabase.UnitDef aDef = getEffectiveUnitDef(attacker);
        PgDatabase.UnitDef dDef = getEffectiveUnitDef(defender);
        CombatReport rep = CombatReport.builder()
                .attackerName(aDef.name)
                .defenderName(dDef.name)
                .attackerId(attacker.instanceId)
                .defenderId(defender.instanceId)
                .attackerIconId(getEffectiveIconId(attacker))
                .defenderIconId(getEffectiveIconId(defender))
                .attackerStartStr(attacker.strength)
                .defenderStartStr(defender.strength)
                .targetCol(defender.col)
                .targetRow(defender.row)
                .surpriseContact(surpriseAmbush)
                .build();

        if (defender.col < attacker.col) {
            attacker.facingLeft = true;
            defender.facingLeft = false;
        } else if (defender.col > attacker.col) {
            attacker.facingLeft = false;
            defender.facingLeft = true;
        }

        int dist = hexDistance(attacker.col, attacker.row, defender.col, defender.row);

        // 1. Check Fighter Interceptor / Flak protection against Air Attacks
        PgDatabase.ActiveUnit interceptor = findAirInterceptorOrFlak(attacker, defender);
        if (interceptor != null) {
            rep.interceptorUnit = interceptor;
            if (supplyEnabled) {
                interceptor.ammo = Math.max(0, interceptor.ammo - 1);
            }
            int intLoss = 1 + rng.nextInt(2);
            attacker.strength = Math.max(0, attacker.strength - intLoss);
            rep.attackerLosses += intLoss;
        }

        // 2. Check Defensive Artillery Support against ground close assault
        PgDatabase.ActiveUnit support = findDefensiveSupport(attacker, defender);
        if (support != null && dist == 1 && !isUnitOnAirLayer(attacker) && !isUnitOnAirLayer(defender)) {
            rep.supportUnit = support;
            if (supplyEnabled) {
                support.ammo = Math.max(0, support.ammo - 1);
            }
            int supHits = 1 + rng.nextInt(2);
            attacker.suppression = Math.min(attacker.strength, attacker.suppression + supHits);
        }

        // 3. Stiff Defense (Rugged Defense) Chance (Chapter 5)
        boolean isGroundClose = (dist == 1 && !isUnitOnAirLayer(attacker) && !isUnitOnAirLayer(defender));
        boolean stiffDefense = false;
        if (surpriseAmbush) {
            stiffDefense = true;
        } else if (isGroundClose && defender.entrenchment >= 1 && !aDef.isPioneerOrEngineer()) {
            double expRatio = (defender.getStars() + 2.0) / (attacker.getStars() + 2.0);
            double entrRatio = (dDef.entrenchmentRate + 1.0) / (aDef.entrenchmentRate + 1.0);
            double ambushChance = defender.entrenchment * expRatio * entrRatio * 5.0;
            if (rng.nextInt(100) < (int) Math.round(ambushChance)) {
                stiffDefense = true;
            }
        }
        rep.ruggedDefense = stiffDefense;

        // Submarine Evasion by Diving (Strategy Guide Chapter 4: 50% vs DD, 75% vs Tac Bomber)
        if (dDef.unitClass == PgDatabase.CLASS_SUBMARINE && !surpriseAmbush) {
            int diveChance = (aDef.unitClass == PgDatabase.CLASS_TAC_BOMBER) ? 75 : 50;
            if (rng.nextInt(100) < diveChance) {
                rep.submarineDived = true;
                attacker.attacked = true;
                if (supplyEnabled && aDef.maxAmmo > 0) attacker.ammo = Math.max(0, attacker.ammo - 1);
                return rep;
            }
        }

        // 4. Attack and Defense Grades per Strategy Guide Chapter 5
        int atkAttackGrade = getStrategyGuideAttackGrade(attacker, aDef, defender, dDef, dist, false);
        int defDefenseGrade = getStrategyGuideDefenseGrade(attacker, aDef, defender, dDef, dist, stiffDefense);

        boolean defenderCanReturnFire = canReturnFire(attacker, aDef, defender, dDef, dist);
        int defAttackGrade = 0;
        int atkDefenseGrade = 0;
        if (defenderCanReturnFire) {
            defAttackGrade = getStrategyGuideAttackGrade(defender, dDef, attacker, aDef, dist, stiffDefense);
            atkDefenseGrade = getStrategyGuideDefenseGrade(defender, dDef, attacker, aDef, dist, false);
            if (dDef.unitClass == PgDatabase.CLASS_ARTILLERY) {
                atkDefenseGrade += 3;
            }
        }

        int atkEffDiff = calculateEffectiveDifference(atkAttackGrade, defDefenseGrade);
        int defEffDiff = calculateEffectiveDifference(defAttackGrade, atkDefenseGrade);

        int atkStr = getEffectiveFiringStrength(attacker, aDef, true);
        int defStr = getEffectiveFiringStrength(defender, dDef, false);

        PgDatabase.HexCell defCell = scenario.grid[defender.col][defender.row];
        int iniCap = (dist == 1 && !aDef.isAir() && !dDef.isAir()) ? defCell.getInitiativeCap() : 99;
        int aIni = Math.min(iniCap, aDef.initiative) + attacker.getStars() + rng.nextInt(3) + 1;
        int dIni = Math.min(iniCap, dDef.initiative) + defender.getStars() + rng.nextInt(3) + 1;
        if (dist == 1 && defCell.isCloseDefenseTerrain()) {
            if (aDef.unitClass == PgDatabase.CLASS_INFANTRY) aIni += 2;
            if (dDef.unitClass == PgDatabase.CLASS_INFANTRY) dIni += 2;
        }
        if (aDef.unitClass == PgDatabase.CLASS_SUBMARINE) {
            aIni += 99; // Submarines shoot first when attacking on their turn
        }
        if (stiffDefense) {
            dIni += 99; // Defender fires first in stiff defense
        }

        boolean aBomb = isBombardment(aDef, dist);
        boolean dBomb = isBombardment(dDef, dist);

        int atkLoss = 0;
        int defLoss = 0;
        int atkSupp = 0;
        int defSupp = 0;

        // 5. Fire Resolution using D20 CRT (Chapter 5)
        if (aIni > dIni) {
            if (atkAttackGrade > 0 && atkStr > 0) {
                int[] res = resolveD20Fire(atkStr, atkEffDiff, aBomb);
                defLoss = Math.min(defender.strength, res[0]);
                defSupp = Math.min(defender.strength - defLoss, res[1]);
            }
            if (defenderCanReturnFire && defAttackGrade > 0) {
                int remDefStr = Math.max(0, defStr - defLoss - defSupp);
                if (remDefStr > 0) {
                    int[] res = resolveD20Fire(remDefStr, defEffDiff, dBomb);
                    atkLoss = Math.min(attacker.strength, res[0]);
                    atkSupp = Math.min(attacker.strength - atkLoss, res[1]);
                }
            }
        } else if (dIni > aIni) {
            if (defenderCanReturnFire && defAttackGrade > 0 && defStr > 0) {
                int[] res = resolveD20Fire(defStr, defEffDiff, dBomb);
                atkLoss = Math.min(attacker.strength, res[0]);
                atkSupp = Math.min(attacker.strength - atkLoss, res[1]);
            }
            if (atkAttackGrade > 0) {
                int remAtkStr = Math.max(0, atkStr - atkLoss - atkSupp);
                if (remAtkStr > 0) {
                    int[] res = resolveD20Fire(remAtkStr, atkEffDiff, aBomb);
                    defLoss = Math.min(defender.strength, res[0]);
                    defSupp = Math.min(defender.strength - defLoss, res[1]);
                }
            }
        } else {
            // Simultaneous fire
            if (atkAttackGrade > 0 && atkStr > 0) {
                int[] res = resolveD20Fire(atkStr, atkEffDiff, aBomb);
                defLoss = Math.min(defender.strength, res[0]);
                defSupp = Math.min(defender.strength - defLoss, res[1]);
            }
            if (defenderCanReturnFire && defAttackGrade > 0 && defStr > 0) {
                int[] res = resolveD20Fire(defStr, defEffDiff, dBomb);
                atkLoss = Math.min(attacker.strength, res[0]);
                atkSupp = Math.min(attacker.strength - atkLoss, res[1]);
            }
        }

        // Apply suppression & entrenchment effects
        if (aDef.unitClass == PgDatabase.CLASS_LEVEL_BOMBER && !isUnitOnAirLayer(defender)) {
            defender.suppression = Math.min(defender.strength, defender.suppression + defSupp + 1);
            rep.defenderSuppressed = defSupp + 1;
            defender.entrenchment = Math.max(0, defender.entrenchment - 2);
            if (supplyEnabled) {
                defender.fuel = (defender.fuel * 3) / 4;
                defender.ammo = (defender.ammo * 3) / 4;
            }
        } else if (dist > 1 || aDef.unitClass == PgDatabase.CLASS_ARTILLERY) {
            defender.suppression = Math.min(defender.strength, defender.suppression + defSupp);
            rep.defenderSuppressed = defSupp;
            if (!isUnitOnAirLayer(defender) && defender.entrenchment > 0) {
                defender.entrenchment = Math.max(0, defender.entrenchment - 1);
            }
        } else if (!isUnitOnAirLayer(defender) && defender.entrenchment > 0) {
            int entStrip = aDef.isPioneerOrEngineer() ? 2 : 1;
            defender.entrenchment = Math.max(0, defender.entrenchment - entStrip);
            rep.defenderSuppressed = defSupp;
        }

        atkLoss = Math.min(attacker.strength, atkLoss);
        defLoss = Math.min(defender.strength, defLoss);

        attacker.strength -= atkLoss;
        defender.strength -= defLoss;
        attacker.attacked = true; // Every unit can attack once per turn
        if (supplyEnabled && aDef.maxAmmo > 0) attacker.ammo = Math.max(0, attacker.ammo - 1);
        if (supplyEnabled && defenderCanReturnFire && dDef.maxAmmo > 0 && defender.ammo > 0) {
            defender.ammo = Math.max(0, defender.ammo - 1);
        }

        attacker.experience = Math.min(500, attacker.experience + defLoss * 12 + 5);
        defender.experience = Math.min(500, defender.experience + atkLoss * 12 + 5);

        rep.attackerLosses += atkLoss;
        rep.defenderLosses = defLoss;
        rep.attackerDestroyed = (attacker.strength <= 0);
        rep.defenderDestroyed = (defender.strength <= 0);

        int defOrigCol = defender.col;
        int defOrigRow = defender.row;

        // Combat Prestige calculation per Strategy Guide Chapter 6
        int enemyBasePrestige = Math.max(1, dDef.cost / 10);
        int fullReward = (enemyBasePrestige * (defender.getStars() + 1)) / 2;

        if (rep.defenderDestroyed) {
            units.remove(defender);
            if (attacker.isAxis) axisPrestige += fullReward;
            else alliedPrestige += fullReward;

            // Panzer General Tank Overrun Rule:
            // If a Tank destroys a weak ground defender in close combat with 0 total attacker losses, it OVERRUNS!
            if (dist == 1 && aDef.unitClass == PgDatabase.CLASS_TANK && !isUnitOnAirLayer(defender)
                    && rep.attackerLosses == 0 && !rep.attackerDestroyed && rep.defenderStartStr <= 6
                    && getUnitStepMoveCost(attacker, aDef, scenario.grid[defOrigCol][defOrigRow]) < 99) {
                rep.overrun = true;
                attacker.col = defOrigCol;
                attacker.row = defOrigRow;
                onHexCaptured(defOrigCol, defOrigRow, attacker);
            } else if (!attacker.isAxis && dist == 1 && !aDef.isAir() && !dDef.isAir() && !rep.attackerDestroyed
                    && getTerrainMoveCost(aDef, scenario.grid[defOrigCol][defOrigRow], false) < 99) {
                // AI automatically advances into vacated hex on close assault
                attacker.col = defOrigCol;
                attacker.row = defOrigRow;
                onHexCaptured(defOrigCol, defOrigRow, attacker);
            }
        } else if (dist == 1 && !isUnitOnAirLayer(attacker) && !isUnitOnAirLayer(defender)
                && (defLoss >= 3 && defLoss > atkLoss || defender.getEffectiveStrength() == 0)) {
            // Defender is forced to retreat
            boolean foundRetreat = false;
            for (int[] nb : getNeighbors(defender.col, defender.row)) {
                if (hexDistance(attacker.col, attacker.row, nb[0], nb[1]) > 1
                        && getUnitAt(nb[0], nb[1], false) == null
                        && !isInEnemyZoc(nb[0], nb[1], defender)
                        && getUnitStepMoveCost(defender, dDef, scenario.grid[nb[0]][nb[1]]) < 99) {
                    defender.col = nb[0];
                    defender.row = nb[1];
                    defender.entrenchment = 0;
                    rep.defenderRetreated = true;
                    foundRetreat = true;
                    break;
                }
            }
            if (!foundRetreat) {
                for (int[] nb : getNeighbors(defender.col, defender.row)) {
                    if (hexDistance(attacker.col, attacker.row, nb[0], nb[1]) > 1
                            && getUnitAt(nb[0], nb[1], false) == null
                            && getUnitStepMoveCost(defender, dDef, scenario.grid[nb[0]][nb[1]]) < 99) {
                        defender.col = nb[0];
                        defender.row = nb[1];
                        defender.entrenchment = 0;
                        rep.defenderRetreated = true;
                        foundRetreat = true;
                        break;
                    }
                }
            }
            if (foundRetreat) {
                if (attacker.isAxis) axisPrestige += Math.max(1, fullReward / 4);
                else alliedPrestige += Math.max(1, fullReward / 4);
            } else {
                // No retreat path -> Unit SURRENDERS!
                rep.defenderSurrendered = true;
                rep.defenderDestroyed = true;
                rep.defenderLosses = rep.defenderStartStr;
                defender.strength = 0;
                units.remove(defender);
                if (attacker.isAxis) axisPrestige += fullReward;
                else alliedPrestige += fullReward;
            }
        } else if (defLoss > atkLoss) {
            // Damage lead prestige reward
            if (attacker.isAxis) axisPrestige += Math.max(1, fullReward / 8);
            else alliedPrestige += Math.max(1, fullReward / 8);
        }

        if (rep.attackerDestroyed) {
            units.remove(attacker);
            int atkBasePrestige = Math.max(1, aDef.cost / 10);
            int atkFullReward = (atkBasePrestige * (attacker.getStars() + 1)) / 2;
            if (defender.isAxis) axisPrestige += atkFullReward;
            else alliedPrestige += atkFullReward;
        }

        recalculateFogOfWar();
        checkVictoryConditions();
        return rep;
    }

    private PgDatabase.ActiveUnit findAirInterceptorOrFlak(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        if (!isUnitOnAirLayer(attacker)) return null;
        PgDatabase.UnitDef dDef = getEffectiveUnitDef(defender);
        if (!isUnitOnAirLayer(defender) || dDef.unitClass != PgDatabase.CLASS_FIGHTER) {
            for (int[] nb : getNeighbors(defender.col, defender.row)) {
                PgDatabase.ActiveUnit adjAir = getUnitAt(nb[0], nb[1], true);
                if (adjAir != null && adjAir.isAxis == defender.isAxis && adjAir.strength > 0
                        && (!supplyEnabled || (adjAir.ammo > 0 && adjAir.fuel > 0))) {
                    PgDatabase.UnitDef fDef = getEffectiveUnitDef(adjAir);
                    if (fDef.unitClass == PgDatabase.CLASS_FIGHTER) {
                        return adjAir;
                    }
                }
            }
        }
        if (!isUnitOnAirLayer(defender)) {
            for (int[] nb : getNeighbors(defender.col, defender.row)) {
                PgDatabase.ActiveUnit adjGnd = getUnitAt(nb[0], nb[1], false);
                if (adjGnd != null && adjGnd.isAxis == defender.isAxis && adjGnd.strength > 0
                        && (!supplyEnabled || adjGnd.ammo > 0)) {
                    PgDatabase.UnitDef adDef = getEffectiveUnitDef(adjGnd);
                    if (adDef.unitClass == PgDatabase.CLASS_AIR_DEFENSE && adDef.range >= 2) {
                        return adjGnd;
                    }
                }
            }
        }
        return null;
    }

    private PgDatabase.ActiveUnit findDefensiveSupport(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        if (!isUnitOnAirLayer(attacker) && !isUnitOnAirLayer(defender)) {
            for (int[] nb : getNeighbors(defender.col, defender.row)) {
                PgDatabase.ActiveUnit adj = getUnitAt(nb[0], nb[1], false);
                if (adj != null && adj.isAxis == defender.isAxis && (!supplyEnabled || adj.ammo > 0) && adj.strength > 0) {
                    PgDatabase.UnitDef adjDef = getEffectiveUnitDef(adj);
                    if (adjDef.unitClass == PgDatabase.CLASS_ARTILLERY && adjDef.range >= 2) {
                        return adj;
                    }
                }
            }
        }
        return null;
    }

    public boolean resupplyUnit(PgDatabase.ActiveUnit u) {
        if (u == null || u.moved || u.movePointsUsed > 0 || u.attacked || u.strength <= 0) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        int maxA = def.maxAmmo > 0 ? def.maxAmmo : 99;
        int maxF = def.maxFuel > 0 ? def.maxFuel : 99;
        if (u.ammo >= maxA && u.fuel >= maxF) return false;

        // Panzer General Supply Rule:
        // Friendly Supply Point = 100%, Open terrain = 75%, In Enemy ZOC = 25%
        PgDatabase.HexCell cell = scenario.grid[u.col][u.row];
        boolean inFriendlySupplyHex = cell.isSupplyPoint() && db.isAxisNation(cell.ownerNation, scenario) == u.isAxis;
        boolean inZoc = isInEnemyZoc(u.col, u.row, u);
        int factor = inZoc ? 25 : (inFriendlySupplyHex ? 100 : 75);

        int addAmmo = Math.max(1, ((maxA - u.ammo) * factor) / 100);
        int addFuel = Math.max(1, ((maxF - u.fuel) * factor) / 100);
        u.ammo = Math.min(maxA, u.ammo + addAmmo);
        u.fuel = Math.min(maxF, u.fuel + addFuel);
        u.moved = true;
        u.attacked = true;
        lastUndoState = null;
        return true;
    }

    public boolean canReinforceUnit(PgDatabase.ActiveUnit u, boolean elite) {
        if (u == null || !u.isAxis || !isPlayerTurn || u.moved || u.movePointsUsed > 0 || u.attacked || u.strength <= 0) {
            return false;
        }
        if (!elite) {
            return u.strength < 10;
        } else {
            // Elite Replacements allow Overstrength up to (10 + stars) when not in enemy ZOC!
            int maxAllowed = isInEnemyZoc(u.col, u.row, u) ? 10 : u.getMaxOverstrength();
            return u.strength < maxAllowed;
        }
    }

    public boolean reinforceUnit(PgDatabase.ActiveUnit u, boolean elite) {
        if (!canReinforceUnit(u, elite)) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        boolean inZoc = isInEnemyZoc(u.col, u.row, u);
        int maxTarget = elite ? (inZoc ? 10 : u.getMaxOverstrength()) : 10;
        int needed = maxTarget - u.strength;
        if (inZoc) {
            // Enemy ZOC restricts replacements to half
            needed = Math.max(1, needed / 2);
        }
        // Overstrength points above 10 cost double elite replacement rate
        int costPerPoint = (u.strength >= 10)
                ? Math.max(20, def.cost / 5)
                : Math.max(8, def.cost / (elite ? 10 : 20));
        int affordable = Math.min(needed, axisPrestige / costPerPoint);
        if (affordable <= 0) return false;

        axisPrestige -= affordable * costPerPoint;
        if (!elite) {
            u.experience = (u.experience * u.strength) / (u.strength + affordable);
        }
        u.strength += affordable;
        if (u.strength <= 10) {
            u.ammo = def.maxAmmo > 0 ? def.maxAmmo : 99;
            u.fuel = def.maxFuel > 0 ? def.maxFuel : 99;
        }
        u.moved = true;
        u.attacked = true;
        lastUndoState = null;
        return true;
    }

    // Panzer General Manual: Upgrade Unit in friendly city/port/airfield with no adjacent enemies
    public boolean canUpgradeUnit(PgDatabase.ActiveUnit u) {
        if (u == null || !u.isAxis || !isPlayerTurn || u.moved || u.movePointsUsed > 0 || u.attacked || u.strength <= 0) {
            return false;
        }
        if (u.inAirTransport || u.inSeaTransport) return false;
        if (isInEnemyZoc(u.col, u.row, u)) return false;
        PgDatabase.HexCell cell = scenario.grid[u.col][u.row];
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (def.isAir()) {
            return cell.terrainType == 6 && db.isAxisNation(cell.ownerNation, scenario);
        }
        return cell.isSupplyPoint() && db.isAxisNation(cell.ownerNation, scenario);
    }

    public boolean upgradeUnit(PgDatabase.ActiveUnit u, int newUnitDefId, int newTransportDefId) {
        if (!canUpgradeUnit(u)) return false;
        if (newUnitDefId <= 0 || newUnitDefId >= db.equipment.size()) return false;
        PgDatabase.UnitDef oldDef = db.equipment.get(u.unitDefId);
        PgDatabase.UnitDef newDef = db.equipment.get(newUnitDefId);
        int upgradeCost = Math.max(20, newDef.cost - (oldDef.cost / 2));
        if (newTransportDefId > 0 && newTransportDefId != u.transportDefId && newTransportDefId < db.equipment.size()) {
            upgradeCost += db.equipment.get(newTransportDefId).cost / 2;
        }
        if (axisPrestige < upgradeCost) return false;

        axisPrestige -= upgradeCost;
        u.unitDefId = newUnitDefId;
        u.transportDefId = newTransportDefId;
        u.strength = Math.min(10, u.strength);
        u.fuel = newDef.maxFuel > 0 ? newDef.maxFuel : 99;
        u.ammo = newDef.maxAmmo > 0 ? newDef.maxAmmo : 99;
        u.moved = true;
        u.attacked = true;
        lastUndoState = null;
        recalculateFogOfWar();
        return true;
    }

    // Panzer General Manual: Disband Unit to recover Prestige
    public boolean canDisbandUnit(PgDatabase.ActiveUnit u) {
        return u != null && u.isAxis && isPlayerTurn && !u.moved && u.movePointsUsed == 0 && !u.attacked && u.strength > 0;
    }

    public int getDisbandRefund(PgDatabase.ActiveUnit u) {
        if (u == null) return 0;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        int base = def.cost;
        if (u.transportDefId > 0 && u.transportDefId < db.equipment.size()) {
            base += db.equipment.get(u.transportDefId).cost / 2;
        }
        return Math.max(10, (base * u.strength) / 20);
    }

    public int disbandUnit(PgDatabase.ActiveUnit u) {
        if (!canDisbandUnit(u)) return 0;
        int refund = getDisbandRefund(u);
        axisPrestige += refund;
        units.remove(u);
        coreUnits.remove(u);
        lastUndoState = null;
        recalculateFogOfWar();
        return refund;
    }

    // Panzer General Manual: Embark / Disembark Air Transport (Paratroops), Sea Transport (Ports), and Organic Transport
    public String getTransportActionLabel(PgDatabase.ActiveUnit u) {
        if (u == null || !u.isAxis) return "MOUNT";
        if (u.inAirTransport) return "PARADROP";
        if (u.inSeaTransport) return "DISEMBARK";
        PgDatabase.HexCell cell = scenario.grid[u.col][u.row];
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (!def.isAir() && !def.isNaval() && !u.moved && u.movePointsUsed == 0 && !u.attacked) {
            if (cell.terrainType == 6 && db.isAxisNation(cell.ownerNation, scenario)
                    && (def.unitClass == PgDatabase.CLASS_INFANTRY || def.isParatroop())
                    && getUnitAt(u.col, u.row, true) == null) {
                return "AIRLIFT";
            }
            if (cell.isPort() && db.isAxisNation(cell.ownerNation, scenario)) {
                return "SEA EMBARK";
            }
        }
        return u.mountedInTransport ? "UNMOUNT" : "MOUNT";
    }

    public boolean canUseTransportAction(PgDatabase.ActiveUnit u) {
        if (u == null || !u.isAxis || !isPlayerTurn || u.strength <= 0) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        PgDatabase.HexCell cell = scenario.grid[u.col][u.row];
        if (u.inAirTransport) {
            return getUnitAt(u.col, u.row, false) == null && getTerrainMoveCost(def, cell, false) < 99;
        }
        if (u.inSeaTransport) {
            if (getTerrainMoveCost(def, cell, false) < 99) return true;
            for (int[] nb : getNeighbors(u.col, u.row)) {
                if (getUnitAt(nb[0], nb[1], false) == null
                        && getTerrainMoveCost(def, scenario.grid[nb[0]][nb[1]], false) < 99) {
                    return true;
                }
            }
            return false;
        }
        if (!u.moved && u.movePointsUsed == 0 && !u.attacked && !def.isAir() && !def.isNaval()) {
            if (cell.terrainType == 6 && db.isAxisNation(cell.ownerNation, scenario)
                    && (def.unitClass == PgDatabase.CLASS_INFANTRY || def.isParatroop())
                    && getUnitAt(u.col, u.row, true) == null) {
                return true;
            }
            if (cell.isPort() && db.isAxisNation(cell.ownerNation, scenario)) {
                return true;
            }
        }
        return !u.moved && u.movePointsUsed == 0 && u.transportDefId > 0;
    }

    public String executeTransportAction(PgDatabase.ActiveUnit u) {
        if (!canUseTransportAction(u)) return null;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        PgDatabase.HexCell cell = scenario.grid[u.col][u.row];
        lastUndoState = null;

        if (u.inAirTransport) {
            u.inAirTransport = false;
            u.moved = true;
            u.attacked = true;
            u.movePointsUsed = getMaxMovementPoints(u);
            int origCol = u.col;
            int origRow = u.row;
            boolean scattered = false;
            // Strategy Guide Chapter 3 Paratroop Scatter: 50% on target, 50% scatters 1 hex
            if (rng.nextInt(100) < 50) {
                List<int[]> validScatters = new ArrayList<>();
                for (int[] nb : getNeighbors(origCol, origRow)) {
                    PgDatabase.HexCell nbCell = scenario.grid[nb[0]][nb[1]];
                    if (!nbCell.isWater() && getUnitAt(nb[0], nb[1], false) == null
                            && getTerrainMoveCost(def, nbCell, false) < 99) {
                        validScatters.add(nb);
                    }
                }
                if (!validScatters.isEmpty()) {
                    int[] chosen = validScatters.get(rng.nextInt(validScatters.size()));
                    u.col = chosen[0];
                    u.row = chosen[1];
                    scattered = true;
                }
            }
            onHexCaptured(u.col, u.row, u);
            recalculateFogOfWar();
            checkVictoryConditions();
            return def.name + " paradropped / landed at (" + u.col + "," + u.row + ")" + (scattered ? " [Scattered!]" : "!");
        }
        if (u.inSeaTransport) {
            if (cell.isWater()) {
                for (int[] nb : getNeighbors(u.col, u.row)) {
                    PgDatabase.HexCell nbCell = scenario.grid[nb[0]][nb[1]];
                    if (!nbCell.isWater() && getUnitAt(nb[0], nb[1], false) == null
                            && getTerrainMoveCost(def, nbCell, false) < 99) {
                        u.col = nb[0];
                        u.row = nb[1];
                        break;
                    }
                }
            }
            u.inSeaTransport = false;
            u.moved = true;
            u.attacked = true;
            u.movePointsUsed = getMaxMovementPoints(u);
            onHexCaptured(u.col, u.row, u);
            recalculateFogOfWar();
            checkVictoryConditions();
            return def.name + " disembarked at (" + u.col + "," + u.row + ")!";
        }
        if (!u.moved && u.movePointsUsed == 0 && !u.attacked && !def.isAir() && !def.isNaval()) {
            if (cell.terrainType == 6 && db.isAxisNation(cell.ownerNation, scenario)
                    && (def.unitClass == PgDatabase.CLASS_INFANTRY || def.isParatroop())
                    && getUnitAt(u.col, u.row, true) == null) {
                u.inAirTransport = true;
                u.mountedInTransport = false;
                u.moved = true;
                u.attacked = true;
                u.movePointsUsed = getMaxMovementPoints(u);
                return def.name + " embarked into Ju 52 Air Transport!";
            }
            if (cell.isPort() && db.isAxisNation(cell.ownerNation, scenario)) {
                u.inSeaTransport = true;
                u.mountedInTransport = false;
                u.moved = true;
                u.attacked = true;
                u.movePointsUsed = getMaxMovementPoints(u);
                return def.name + " embarked into Naval Transport!";
            }
        }
        if (u.transportDefId > 0) {
            u.mountedInTransport = !u.mountedInTransport;
            if (u.mountedInTransport) {
                u.entrenchment = 0;
            }
            PgDatabase.UnitDef trp = db.equipment.get(u.transportDefId);
            return def.name + (u.mountedInTransport ? " mounted in " + trp.name + " (" + trp.movement + " MP)." : " dismounted for foot combat (" + def.movement + " MP).");
        }
        return null;
    }

    public PgDatabase.ActiveUnit purchaseUnit(int unitDefId, int transportDefId, int col, int row) {
        if (unitDefId <= 0 || unitDefId >= db.equipment.size()) return null;
        PgDatabase.UnitDef def = db.equipment.get(unitDefId);
        int totalCost = def.cost;
        if (transportDefId > 0 && transportDefId < db.equipment.size()) {
            totalCost += db.equipment.get(transportDefId).cost / 2;
        }
        if (axisPrestige < totalCost) return null;
        if (getUnitAt(col, row, def.isAir()) != null) return null;

        axisPrestige -= totalCost;
        int curId = nextInstanceId++;
        PgDatabase.ActiveUnit u = PgDatabase.ActiveUnit.builder()
                .instanceId(curId)
                .unitDefId(unitDefId)
                .transportDefId(transportDefId)
                .nation(scenario.axisNations[0] > 0 ? scenario.axisNations[0] : 8)
                .isAxis(true)
                .isCore(true)
                .col(col)
                .row(row)
                .strength(10)
                .entrenchment(0)
                .experience(100)
                .fuel(def.maxFuel > 0 ? def.maxFuel : 99)
                .ammo(def.maxAmmo > 0 ? def.maxAmmo : 99)
                .movePointsUsed(0)
                .moved(false)
                .attacked(false)
                .facingLeft(false)
                .customTitle(curId + ". " + def.name)
                .build();
        units.add(u);
        if (isCampaignMode) coreUnits.add(u);
        lastUndoState = null;
        recalculateFogOfWar();
        return u;
    }

    public List<int[]> getAvailableAiDeployHexes() {
        List<int[]> res = new ArrayList<>();
        if (scenario == null) return res;
        for (int c = 0; c < scenario.cols; c++) {
            for (int r = 0; r < scenario.rows; r++) {
                PgDatabase.HexCell cell = scenario.grid[c][r];
                if ((cell.terrainType == 8 || cell.isSupplyPoint() || cell.isObjective)
                        && cell.ownerNation > 0 && !db.isAxisNation(cell.ownerNation, scenario)) {
                    if (getUnitAt(c, r, false) == null) {
                        res.add(new int[]{c, r});
                    }
                }
            }
        }
        return res;
    }

    public PgDatabase.ActiveUnit purchaseAiUnit(int unitDefId, int col, int row) {
        if (unitDefId <= 0 || unitDefId >= db.equipment.size()) return null;
        PgDatabase.UnitDef def = db.equipment.get(unitDefId);
        if (alliedPrestige < def.cost) return null;
        if (getUnitAt(col, row, def.isAir()) != null) return null;

        alliedPrestige -= def.cost;
        int curId = nextInstanceId++;
        int nat = (scenario.alliedNations != null && scenario.alliedNations.length > 0 && scenario.alliedNations[0] > 0)
                ? scenario.alliedNations[0] : 16;
        PgDatabase.ActiveUnit u = PgDatabase.ActiveUnit.builder()
                .instanceId(curId)
                .unitDefId(unitDefId)
                .nation(nat)
                .isAxis(false)
                .isCore(false)
                .col(col)
                .row(row)
                .strength(10)
                .entrenchment(0)
                .experience(0)
                .fuel(def.maxFuel > 0 ? def.maxFuel : 99)
                .ammo(def.maxAmmo > 0 ? def.maxAmmo : 99)
                .movePointsUsed(0)
                .moved(false)
                .attacked(false)
                .facingLeft(true)
                .customTitle(curId + ". " + def.name)
                .aiOrder(scenario.grid[col][row].isObjective ? 1 : 0)
                .build();
        units.add(u);
        return u;
    }

    public boolean takeAiReplacements(PgDatabase.ActiveUnit u) {
        if (u == null || u.strength >= 10 || alliedPrestige < 20) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        int needed = 10 - u.strength;
        int costPerPoint = Math.max(5, def.cost / 15);
        int cost = needed * costPerPoint;
        if (alliedPrestige < cost) {
            cost = alliedPrestige;
            needed = Math.max(1, cost / costPerPoint);
        }
        alliedPrestige -= cost;
        u.strength += needed;
        u.moved = true;
        u.attacked = true;
        u.movePointsUsed = getMaxMovementPoints(u);
        return true;
    }

    public List<CombatReport> endPlayerTurnAndRunAi() {
        List<CombatReport> aiReports = new ArrayList<>();
        isPlayerTurn = false;
        lastUndoState = null;

        // Reset AI units & clear suppression
        for (PgDatabase.ActiveUnit u : units) {
            if (!u.isAxis && u.strength > 0) {
                u.movePointsUsed = 0;
                u.moved = false;
                u.attacked = false;
                u.suppression = Math.max(0, u.suppression - 2);
                PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
                if (u.ammo == 0) u.ammo = def.maxAmmo > 0 ? def.maxAmmo : 99;
                if (u.fuel == 0) u.fuel = def.maxFuel > 0 ? def.maxFuel : 99;
            }
        }

        List<PgDatabase.ActiveUnit> aiList = new ArrayList<>();
        for (PgDatabase.ActiveUnit u : units) {
            if (!u.isAxis && u.strength > 0) aiList.add(u);
        }

        // =========================================================================
        // PHASE 1: Tactical Air Operations (Fighters intercept, Bombers suppress/strike)
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !units.contains(ai) || ai.attacked) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (!def.isAir()) continue;

            List<PgDatabase.ActiveUnit> targets = getValidTargets(ai);
            if (!targets.isEmpty()) {
                targets.sort((t1, t2) -> {
                    PgDatabase.UnitDef d1 = db.equipment.get(t1.unitDefId);
                    PgDatabase.UnitDef d2 = db.equipment.get(t2.unitDefId);
                    int p1 = (def.unitClass == PgDatabase.CLASS_FIGHTER && (d1.unitClass == PgDatabase.CLASS_LEVEL_BOMBER || d1.unitClass == PgDatabase.CLASS_TAC_BOMBER)) ? 10 : 0;
                    int p2 = (def.unitClass == PgDatabase.CLASS_FIGHTER && (d2.unitClass == PgDatabase.CLASS_LEVEL_BOMBER || d2.unitClass == PgDatabase.CLASS_TAC_BOMBER)) ? 10 : 0;
                    if (def.unitClass == PgDatabase.CLASS_LEVEL_BOMBER) {
                        p1 += t1.entrenchment * 2 + (d1.unitClass == PgDatabase.CLASS_ARTILLERY ? 5 : 0);
                        p2 += t2.entrenchment * 2 + (d2.unitClass == PgDatabase.CLASS_ARTILLERY ? 5 : 0);
                    }
                    return Integer.compare(p2, p1);
                });
                aiReports.add(executeCombat(ai, targets.get(0)));
            } else if (!ai.moved) {
                PgDatabase.ActiveUnit priorityTarget = null;
                int bestDist = 999;
                for (PgDatabase.ActiveUnit pUnit : units) {
                    if (pUnit.isAxis && pUnit.strength > 0) {
                        int d = hexDistance(ai.col, ai.row, pUnit.col, pUnit.row);
                        if (d < bestDist && d <= def.movement) {
                            bestDist = d;
                            priorityTarget = pUnit;
                        }
                    }
                }
                if (priorityTarget != null) {
                    Map<Integer, Integer> reach = getReachableHexes(ai);
                    int bestC = ai.col, bestR = ai.row, minDist = bestDist;
                    for (int key : reach.keySet()) {
                        int mc = key % scenario.cols, mr = key / scenario.cols;
                        int d = hexDistance(mc, mr, priorityTarget.col, priorityTarget.row);
                        if (d < minDist) {
                            minDist = d;
                            bestC = mc;
                            bestR = mr;
                        }
                    }
                    if (bestC != ai.col || bestR != ai.row) {
                        moveUnit(ai, bestC, bestR);
                        List<PgDatabase.ActiveUnit> postTargets = getValidTargets(ai);
                        if (!postTargets.isEmpty()) {
                            aiReports.add(executeCombat(ai, postTargets.get(0)));
                        }
                    }
                }
            }
        }

        // =========================================================================
        // PHASE 2: Ranged Artillery Bombardment (Strip entrenchment before assault)
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !units.contains(ai) || ai.attacked) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (def.range > 1) {
                List<PgDatabase.ActiveUnit> targets = getValidTargets(ai);
                if (!targets.isEmpty()) {
                    targets.sort((t1, t2) -> Integer.compare(t2.entrenchment, t1.entrenchment));
                    aiReports.add(executeCombat(ai, targets.get(0)));
                }
            }
        }

        // =========================================================================
        // PHASE 3: Submarine & Naval Combat
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !units.contains(ai) || ai.attacked) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (!def.isNaval()) continue;

            List<PgDatabase.ActiveUnit> targets = getValidTargets(ai);
            if (!targets.isEmpty()) {
                if (def.unitClass == PgDatabase.CLASS_SUBMARINE) {
                    targets.sort((t1, t2) -> {
                        PgDatabase.UnitDef d1 = db.equipment.get(t1.unitDefId);
                        PgDatabase.UnitDef d2 = db.equipment.get(t2.unitDefId);
                        int p1 = (d1.unitClass == PgDatabase.CLASS_CAPITAL_SHIP || d1.unitClass == PgDatabase.CLASS_CARRIER) ? 10 : 0;
                        int p2 = (d2.unitClass == PgDatabase.CLASS_CAPITAL_SHIP || d2.unitClass == PgDatabase.CLASS_CARRIER) ? 10 : 0;
                        return Integer.compare(p2, p1);
                    });
                }
                aiReports.add(executeCombat(ai, targets.get(0)));
            }
        }

        // =========================================================================
        // PHASE 4: Ground Assault & Tank Overrun Exploitation
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !units.contains(ai) || ai.attacked) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (def.isAir() || def.isNaval() || def.range > 1) {
                if (ai.attacked) continue;
            }

            // AI Transport Management: mount up when moving over distance, dismount when near frontline
            if (ai.transportDefId > 0 && !ai.moved && ai.movePointsUsed == 0) {
                boolean nearEnemy = false;
                for (PgDatabase.ActiveUnit e : units) {
                    if (e.isAxis && e.strength > 0 && hexDistance(ai.col, ai.row, e.col, e.row) <= 2) {
                        nearEnemy = true;
                        break;
                    }
                }
                if (nearEnemy && ai.mountedInTransport) {
                    ai.mountedInTransport = false;
                } else if (!nearEnemy && !ai.mountedInTransport && ai.entrenchment == 0) {
                    ai.mountedInTransport = true;
                }
            }

            PgDatabase.HexCell curCell = scenario.grid[ai.col][ai.row];

            // 1. Static Guard Order: units holding an objective/fort hold position
            if (ai.aiOrder == 1 && curCell.isObjective && ai.entrenchment >= 2) {
                List<PgDatabase.ActiveUnit> targets = getValidTargets(ai);
                if (!targets.isEmpty()) {
                    CombatPrediction p = predictCombat(ai, targets.get(0));
                    if (p.expectedDefenderLosses >= p.expectedAttackerLosses) {
                        aiReports.add(executeCombat(ai, targets.get(0)));
                    }
                }
                continue;
            }

            // 2. Tactical Fallback: Depleted units (<= 4 str) pull back out of harm's way
            if (ai.strength <= 4) {
                boolean adjacentToEnemy = false;
                for (int[] nb : getNeighbors(ai.col, ai.row)) {
                    PgDatabase.ActiveUnit adj = getUnitAt(nb[0], nb[1], false);
                    if (adj != null && adj.isAxis && adj.strength > 0) {
                        adjacentToEnemy = true;
                        break;
                    }
                }
                if (adjacentToEnemy) {
                    Map<Integer, Integer> reach = getReachableHexes(ai);
                    int safeC = ai.col, safeR = ai.row, maxEnemyDist = 0;
                    for (int key : reach.keySet()) {
                        int mc = key % scenario.cols, mr = key / scenario.cols;
                        int minD = 99;
                        for (PgDatabase.ActiveUnit enemy : units) {
                            if (enemy.isAxis && enemy.strength > 0) {
                                minD = Math.min(minD, hexDistance(mc, mr, enemy.col, enemy.row));
                            }
                        }
                        if (minD > maxEnemyDist) {
                            maxEnemyDist = minD;
                            safeC = mc;
                            safeR = mr;
                        }
                    }
                    if (safeC != ai.col || safeR != ai.row) {
                        moveUnit(ai, safeC, safeR);
                    }
                }
                continue;
            }

            // 3. Immediate favorable attack
            List<PgDatabase.ActiveUnit> immediateTargets = getValidTargets(ai);
            if (!immediateTargets.isEmpty()) {
                PgDatabase.ActiveUnit bestTarget = null;
                for (PgDatabase.ActiveUnit t : immediateTargets) {
                    CombatPrediction p = predictCombat(ai, t);
                    if (p.expectedDefenderLosses >= p.expectedAttackerLosses) {
                        bestTarget = t;
                        break;
                    }
                }
                if (bestTarget != null) {
                    CombatReport rep = executeCombat(ai, bestTarget);
                    aiReports.add(rep);
                    if (rep.overrun && ai.strength > 0 && !ai.attacked) {
                        List<PgDatabase.ActiveUnit> overrunTargets = getValidTargets(ai);
                        if (!overrunTargets.isEmpty()) {
                            aiReports.add(executeCombat(ai, overrunTargets.get(0)));
                        }
                    }
                    continue;
                }
            }

            // 4. Advance towards nearest enemy or threatened objective
            PgDatabase.ActiveUnit nearestEnemy = null;
            int bestDist = 999;
            for (PgDatabase.ActiveUnit pUnit : units) {
                if (pUnit.isAxis && pUnit.strength > 0) {
                    int d = hexDistance(ai.col, ai.row, pUnit.col, pUnit.row);
                    if (d < bestDist) {
                        bestDist = d;
                        nearestEnemy = pUnit;
                    }
                }
            }
            if (nearestEnemy != null && bestDist <= 8 && !ai.moved) {
                Map<Integer, Integer> reach = getReachableHexes(ai);
                int bestMoveCol = ai.col, bestMoveRow = ai.row;
                int minTargetDist = bestDist;
                for (int key : reach.keySet()) {
                    int mc = key % scenario.cols;
                    int mr = key / scenario.cols;
                    int d = hexDistance(mc, mr, nearestEnemy.col, nearestEnemy.row);
                    if (d < minTargetDist) {
                        minTargetDist = d;
                        bestMoveCol = mc;
                        bestMoveRow = mr;
                    }
                }
                if (bestMoveCol != ai.col || bestMoveRow != ai.row) {
                    moveUnit(ai, bestMoveCol, bestMoveRow);
                    List<PgDatabase.ActiveUnit> postMoveTargets = getValidTargets(ai);
                    if (!postMoveTargets.isEmpty()) {
                        CombatPrediction p = predictCombat(ai, postMoveTargets.get(0));
                        if (p.expectedDefenderLosses >= p.expectedAttackerLosses) {
                            CombatReport rep = executeCombat(ai, postMoveTargets.get(0));
                            aiReports.add(rep);
                            if (rep.overrun && ai.strength > 0 && !ai.attacked) {
                                List<PgDatabase.ActiveUnit> overrunTargets = getValidTargets(ai);
                                if (!overrunTargets.isEmpty()) {
                                    aiReports.add(executeCombat(ai, overrunTargets.get(0)));
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // PHASE 5: Replacements for Safe Wounded AI Units (Strategy Guide pp. 991-995)
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength > 0 && ai.strength <= 6 && !ai.attacked && alliedPrestige >= 30) {
                PgDatabase.HexCell cell = scenario.grid[ai.col][ai.row];
                boolean isSupply = cell.isSupplyPoint() || cell.isObjective || cell.terrainType == 8;
                boolean safeFromEnemy = true;
                for (int[] nb : getNeighbors(ai.col, ai.row)) {
                    PgDatabase.ActiveUnit adj = getUnitAt(nb[0], nb[1], false);
                    if (adj != null && adj.isAxis && adj.strength > 0) {
                        safeFromEnemy = false;
                        break;
                    }
                }
                if (isSupply || safeFromEnemy) {
                    takeAiReplacements(ai);
                }
            }
        }

        // =========================================================================
        // PHASE 6: AI Reinforcements / Prestige Spending
        // =========================================================================
        if (alliedPrestige >= 150) {
            List<int[]> deploySpots = getAvailableAiDeployHexes();
            if (!deploySpots.isEmpty()) {
                int chosenDefId = -1;
                for (PgDatabase.ActiveUnit u : scenario.initialUnits) {
                    if (!u.isAxis && u.unitDefId > 0 && u.unitDefId < db.equipment.size()) {
                        PgDatabase.UnitDef d = db.equipment.get(u.unitDefId);
                        if (!d.isAir() && !d.isNaval() && d.cost <= alliedPrestige) {
                            if (chosenDefId == -1 || d.cost > db.equipment.get(chosenDefId).cost) {
                                chosenDefId = u.unitDefId;
                            }
                        }
                    }
                }
                if (chosenDefId > 0) {
                    int[] spot = deploySpots.get(0);
                    purchaseAiUnit(chosenDefId, spot[0], spot[1]);
                }
            }
        }

        // Advance turn counter and prepare Player units
        turn++;
        updateWeather();

        // Strategy Guide Chapter 6: Continuing Control of Hexes
        // Offensive player: +4 per objective, +1 per normal city/port/airfield
        // Defending player: +8 per objective, +4 per normal city/port/airfield
        if (scenario != null) {
            boolean axisDefending = scenario.isDefensiveScenario;
            for (int c = 0; c < scenario.cols; c++) {
                for (int r = 0; r < scenario.rows; r++) {
                    PgDatabase.HexCell cell = scenario.grid[c][r];
                    boolean isCityPortAirfield = (cell.terrainType == 8 || cell.terrainType == 6 || cell.isPort());
                    if (isCityPortAirfield || cell.isObjective) {
                        boolean isAxisOwner = db.isAxisNation(cell.ownerNation, scenario);
                        if (isAxisOwner) {
                            if (cell.isObjective) {
                                axisPrestige += axisDefending ? 8 : 4;
                            } else {
                                axisPrestige += axisDefending ? 4 : 1;
                            }
                        } else if (cell.ownerNation > 0) {
                            if (cell.isObjective) {
                                alliedPrestige += axisDefending ? 4 : 8;
                            } else {
                                alliedPrestige += axisDefending ? 1 : 4;
                            }
                        }
                    }
                }
            }
        }

        Iterator<PgDatabase.ActiveUnit> it = units.iterator();
        while (it.hasNext()) {
            PgDatabase.ActiveUnit u = it.next();
            if (u.strength <= 0) continue;
            PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
            if (u.movePointsUsed == 0 && !u.moved && !u.attacked && !isUnitOnAirLayer(u) && !u.inSeaTransport && !def.isNaval()) {
                int maxEnt = scenario.grid[u.col][u.row].getBaseEntrenchment() + 3;
                if (u.entrenchment < maxEnt) u.entrenchment++;
            }
            if (u.isAxis) {
                u.movePointsUsed = 0;
                u.moved = false;
                u.attacked = false;
                u.suppression = Math.max(0, u.suppression - 3);
                // Automatic air resupply when over friendly airfield or adjacent to friendly Carrier
                if (!supplyEnabled) {
                    u.fuel = def.maxFuel > 0 ? def.maxFuel : 99;
                    u.ammo = def.maxAmmo > 0 ? def.maxAmmo : 99;
                } else if (def.isAir()) {
                    boolean onFriendlyAirfield = (scenario.grid[u.col][u.row].terrainType == 6
                            && db.isAxisNation(scenario.grid[u.col][u.row].ownerNation, scenario));
                    boolean nearCarrier = false;
                    for (int[] nb : getNeighbors(u.col, u.row)) {
                        PgDatabase.ActiveUnit sea = getUnitAt(nb[0], nb[1], false);
                        if (sea != null && sea.isAxis && db.equipment.get(sea.unitDefId).unitClass == PgDatabase.CLASS_CARRIER) {
                            nearCarrier = true;
                            break;
                        }
                    }
                    if (onFriendlyAirfield || nearCarrier) {
                        u.fuel = def.maxFuel;
                        u.ammo = def.maxAmmo;
                    } else if (u.fuel <= 0) {
                        // Panzer General Air Fuel Rule: Air unit out of fuel away from airfield/carrier crashes
                        u.strength = 0;
                        it.remove();
                    }
                }
            }
        }

        isPlayerTurn = true;
        recalculateFogOfWar();
        checkVictoryConditions();
        return aiReports;
    }

    private void updateWeather() {
        if (!weatherEnabled) {
            weather = 0;
            groundCondition = 0;
            wetness = 0;
            return;
        }
        int roll = rng.nextInt(100);
        if (roll < 72) {
            weather = 0; // Clear
            wetness = Math.max(0, wetness - 2);
        } else if (roll < 90) {
            weather = 1; // Overcast
            wetness = Math.max(0, wetness - 1);
        } else {
            weather = 2; // Rain/Storm
            wetness = Math.min(5, wetness + 2);
        }

        // Strategy Guide Chapter 3: wetness >= 3 creates Mud/Snow; <= 2 is Dry
        if (wetness >= 3) {
            groundCondition = 1; // Mud / Snow
        } else {
            groundCondition = 0; // Dry
        }
    }

    public void checkVictoryConditions() {
        if (scenario == null || scenario.objectives.isEmpty()) return;
        boolean allAxis = true;
        int axisCount = 0;
        for (int[] obj : scenario.objectives) {
            PgDatabase.HexCell cell = scenario.grid[obj[0]][obj[1]];
            if (db.isAxisNation(cell.ownerNation, scenario)) {
                axisCount++;
            } else {
                allAxis = false;
            }
        }

        if (allAxis) {
            int turnsLeft = maxTurns - turn;
            scenarioOutcome = (turnsLeft >= scenario.meta.decisiveTurnsRemaining) ? 1 : 2;
        } else if (turn > maxTurns) {
            if (axisCount >= Math.max(1, (scenario.objectives.size() * 3) / 4)) {
                scenarioOutcome = 2; // Marginal Victory if most objectives held at time limit
            } else {
                scenarioOutcome = 3; // Loss
            }
        }
    }

    public String getWeatherLabel() {
        if (!weatherEnabled) return "Clear / Dry (Fixed)";
        String w = (weather == 0) ? "Clear" : (weather == 1 ? "Overcast" : "Storm");
        String g = (groundCondition == 0) ? "Dry" : (groundCondition == 1 ? "Mud" : "Frozen");
        return w + " / " + g;
    }

    public void saveGame(Context ctx, int slot) throws IOException {
        int[][] hexOwners = new int[scenario.cols][scenario.rows];
        for (int c = 0; c < scenario.cols; c++) {
            for (int r = 0; r < scenario.rows; r++) {
                hexOwners[c][r] = scenario.grid[c][r].ownerNation;
            }
        }
        String saveTimestamp = new java.text.SimpleDateFormat("MMM dd HH:mm", Locale.US).format(new Date());

        SaveState st = SaveState.builder()
                .isCampaign(isCampaignMode)
                .campaignEntryIndex(campaignIndex)
                .currentScenarioNumber(currentScenarioNumber)
                .turn(turn)
                .maxTurns(maxTurns)
                .weather(weather)
                .groundCondition(groundCondition)
                .wetness(wetness)
                .axisPrestige(axisPrestige)
                .alliedPrestige(alliedPrestige)
                .supplyEnabled(supplyEnabled)
                .weatherEnabled(weatherEnabled)
                .fogOfWarEnabled(fogOfWarEnabled)
                .prestigeModifier(prestigeModifier)
                .units(new ArrayList<>(units))
                .coreRoster(new ArrayList<>(coreUnits))
                .hexOwners(hexOwners)
                .scenarioTitle(scenario.meta.fullTitle)
                .saveTimestamp(saveTimestamp)
                .build();

        File f = new File(ctx.getFilesDir(), "pg_save_" + slot + ".dat");
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
            oos.writeObject(st);
        }
    }

    public boolean loadGame(Context ctx, int slot) {
        File f = new File(ctx.getFilesDir(), "pg_save_" + slot + ".dat");
        if (!f.exists()) return false;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            SaveState st = (SaveState) ois.readObject();
            this.isCampaignMode = st.isCampaign;
            this.campaignIndex = st.campaignEntryIndex;
            this.scenario = db.loadScenario(st.currentScenarioNumber);
            this.currentScenarioNumber = st.currentScenarioNumber;
            this.turn = st.turn;
            this.maxTurns = st.maxTurns;
            this.weather = st.weather;
            this.groundCondition = st.groundCondition;
            this.wetness = st.wetness;
            this.axisPrestige = st.axisPrestige;
            this.alliedPrestige = st.alliedPrestige;
            this.supplyEnabled = st.supplyEnabled;
            this.weatherEnabled = st.weatherEnabled;
            if (!this.weatherEnabled) {
                this.weather = 0;
                this.groundCondition = 0;
            }
            this.fogOfWarEnabled = st.fogOfWarEnabled;
            if (st.prestigeModifier > 0) this.prestigeModifier = st.prestigeModifier;
            this.units.clear();
            this.units.addAll(st.units);
            this.coreUnits.clear();
            this.coreUnits.addAll(st.coreRoster);
            for (int c = 0; c < scenario.cols && c < st.hexOwners.length; c++) {
                for (int r = 0; r < scenario.rows && r < st.hexOwners[c].length; r++) {
                    scenario.grid[c][r].ownerNation = st.hexOwners[c][r];
                }
            }
            this.scenarioOutcome = 0;
            this.visibleHexes = new boolean[scenario.cols][scenario.rows];
            recalculateFogOfWar();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getSaveSummary(Context ctx, int slot) {
        File f = new File(ctx.getFilesDir(), "pg_save_" + slot + ".dat");
        if (!f.exists()) return "Slot " + slot + ": [Empty]";
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            SaveState st = (SaveState) ois.readObject();
            return String.format(Locale.ROOT, "Slot %d: %s (Turn %d/%d) - %s",
                    slot, st.scenarioTitle, st.turn, st.maxTurns, st.saveTimestamp);
        } catch (Exception e) {
            return "Slot " + slot + ": [Saved Game]";
        }
    }
}
