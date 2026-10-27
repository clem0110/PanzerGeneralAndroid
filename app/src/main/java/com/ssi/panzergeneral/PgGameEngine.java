package com.ssi.panzergeneral;

import android.content.Context;
import java.io.*;
import java.util.*;

public class PgGameEngine {
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
        public PgDatabase.ActiveUnit supportUnit;
        public PgDatabase.ActiveUnit interceptorUnit;
        public int targetCol, targetRow;
    }

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

    public static class SaveState implements Serializable {
        public boolean isCampaign;
        public int campaignEntryIndex;
        public int currentScenarioNumber;
        public int turn;
        public int maxTurns;
        public int weather; // 0 = Clear, 1 = Overcast, 2 = Rain/Snow
        public int groundCondition; // 0 = Dry, 1 = Mud, 2 = Frozen
        public int axisPrestige;
        public int alliedPrestige;
        public boolean supplyEnabled = true;
        public boolean weatherEnabled = true;
        public boolean fogOfWarEnabled = true;
        public int prestigeModifier = 100; // 50%, 75%, 100%, 150%, 200%
        public List<PgDatabase.ActiveUnit> units = new ArrayList<>();
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

    public int getEffectiveIconId(PgDatabase.ActiveUnit u) {
        if (u == null) return 0;
        if (u.inAirTransport) return getAirTransportDef().iconId;
        if (u.inSeaTransport) return getSeaTransportDef(u.isAxis).iconId;
        if (u.mountedInTransport && u.transportDefId > 0) {
            return db.equipment.get(u.transportDefId).iconId;
        }
        return db.equipment.get(u.unitDefId).iconId;
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

    public Set<Integer> getSpottedEnemyIds() {
        Set<Integer> set = new HashSet<>();
        if (scenario == null || visibleHexes == null) return set;
        for (PgDatabase.ActiveUnit u : units) {
            if (!u.isAxis && u.strength > 0 && (!fogOfWarEnabled || visibleHexes[u.col][u.row])) {
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
            int spot = Math.max(1, def.spotting - ((weatherEnabled && weather > 0) ? 1 : 0));
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
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        int totalMove = def.movement;
        if (u.transportDefId > 0 && u.transportDefId < db.equipment.size()) {
            PgDatabase.UnitDef trp = db.equipment.get(u.transportDefId);
            totalMove = Math.max(totalMove, trp.movement);
        }
        return totalMove;
    }

    public int getRemainingMovementPoints(PgDatabase.ActiveUnit u) {
        if (u == null || u.moved || u.strength <= 0) return 0;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        int remMove = Math.max(0, getMaxMovementPoints(u) - u.movePointsUsed);
        if (supplyEnabled && def.maxFuel > 0 && !u.inAirTransport && !u.inSeaTransport) {
            remMove = Math.min(remMove, u.fuel);
        }
        return remMove;
    }

    public Map<Integer, Integer> getReachableHexes(PgDatabase.ActiveUnit u) {
        Map<Integer, Integer> distMap = new HashMap<>();
        if (u == null || u.moved || u.strength <= 0) return distMap;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
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
        boolean hasOrganicTransport = (u.transportDefId > 0 && u.transportDefId < db.equipment.size());
        return getTerrainMoveCost(def, cell, u.mountedInTransport || hasOrganicTransport);
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
        PgDatabase.UnitDef aDef = db.equipment.get(attacker.unitDefId);
        boolean aAir = isUnitOnAirLayer(attacker);

        for (PgDatabase.ActiveUnit u : units) {
            if (u.strength <= 0 || u.isAxis == attacker.isAxis) continue;
            if (fogOfWarEnabled && !visibleHexes[u.col][u.row]) continue;
            int dist = hexDistance(attacker.col, attacker.row, u.col, u.row);
            boolean dAir = isUnitOnAirLayer(u);
            boolean dNaval = u.inSeaTransport || db.equipment.get(u.unitDefId).isNaval();
            PgDatabase.UnitDef dDef = u.inAirTransport ? getAirTransportDef()
                    : (u.inSeaTransport ? getSeaTransportDef(u.isAxis) : db.equipment.get(u.unitDefId));
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
        if (aDef.unitClass == PgDatabase.CLASS_LAND_TRANSPORT
                || aDef.unitClass == PgDatabase.CLASS_AIR_TRANSPORT
                || aDef.unitClass == PgDatabase.CLASS_SEA_TRANSPORT) {
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
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
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

        UndoMoveState undo = new UndoMoveState();
        undo.unit = u;
        undo.prevCol = u.col;
        undo.prevRow = u.row;
        undo.prevFuel = u.fuel;
        undo.prevMovePointsUsed = u.movePointsUsed;
        undo.prevMoved = u.moved;
        undo.prevEntrenchment = u.entrenchment;
        undo.prevFacingLeft = u.facingLeft;
        undo.destCol = destCol;
        undo.destRow = destRow;
        undo.prevDestOwnerNation = scenario.grid[destCol][destRow].ownerNation;
        undo.prevAxisPrestige = axisPrestige;

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
            PgDatabase.HexCell cell = scenario.grid[destCol][destRow];
            if (cell.isObjective || cell.isSupplyPoint() || cell.ownerNation > 0) {
                boolean wasEnemy = db.isAxisNation(cell.ownerNation, scenario) != u.isAxis;
                cell.ownerNation = u.nation;
                if (wasEnemy && u.isAxis) {
                    if (cell.isObjective) axisPrestige += 100;
                    else if (cell.terrainType == 8) axisPrestige += 40;
                }
            }
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

    public CombatPrediction predictCombat(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        CombatPrediction pred = new CombatPrediction();
        PgDatabase.UnitDef aDef = attacker.inAirTransport ? getAirTransportDef()
                : (attacker.inSeaTransport ? getSeaTransportDef(attacker.isAxis) : db.equipment.get(attacker.unitDefId));
        PgDatabase.UnitDef dDef = defender.inAirTransport ? getAirTransportDef()
                : (defender.inSeaTransport ? getSeaTransportDef(defender.isAxis) : db.equipment.get(defender.unitDefId));
        int dist = hexDistance(attacker.col, attacker.row, defender.col, defender.row);

        int atkVal = getAttackStat(attacker, aDef, defender, dDef);
        int defVal = getDefenseStat(attacker, aDef, defender, dDef, defender.col, defender.row);

        // Experience & entrenchment modifiers
        atkVal += attacker.getStars();
        defVal += defender.getStars() + (isUnitOnAirLayer(defender) ? 0 : defender.entrenchment / 2);

        if (attacker.mountedInTransport || attacker.inSeaTransport || attacker.inAirTransport) {
            atkVal = Math.max(1, atkVal / 2);
        }
        if (defender.mountedInTransport || defender.inSeaTransport || defender.inAirTransport) {
            defVal = Math.max(1, defVal / 2);
        }

        // Out-of-fuel ground defense penalty (-3 GD when out of fuel)
        if (supplyEnabled && !isUnitOnAirLayer(defender) && dDef.maxFuel > 0 && defender.fuel <= 0) {
            defVal = Math.max(1, defVal - 3);
        }

        // Calculate Effective Initiative with Panzer General Manual Terrain Cap
        PgDatabase.HexCell defCell = scenario.grid[defender.col][defender.row];
        int iniCap = (dist == 1 && !aDef.isAir() && !dDef.isAir()) ? defCell.getInitiativeCap() : 99;
        int aIni = Math.min(iniCap, aDef.initiative) + attacker.getStars();
        int dIni = Math.min(iniCap, dDef.initiative) + defender.getStars();
        if (dist == 1 && defCell.isCloseDefenseTerrain()) {
            if (aDef.unitClass == PgDatabase.CLASS_INFANTRY) aIni += 2;
            if (dDef.unitClass == PgDatabase.CLASS_INFANTRY) dIni += 2;
        }
        pred.effectiveAttackerIni = aIni;
        pred.effectiveDefenderIni = dIni;

        int diff = atkVal - defVal;
        double killRate = Math.max(0.08, Math.min(0.85, 0.30 + diff * 0.045));
        double retRate = Math.max(0.05, Math.min(0.75, 0.22 - diff * 0.035));

        boolean isAirAttackingGround = aDef.isAir() && !isUnitOnAirLayer(defender);
        boolean isGroundAttackingAir = !aDef.isAir() && isUnitOnAirLayer(defender);

        if (dist > 1 || isGroundAttackingAir
                || (aDef.unitClass == PgDatabase.CLASS_LEVEL_BOMBER && dDef.airAttack <= 0)
                || (isAirAttackingGround && dDef.airAttack <= 0)) {
            // Ranged artillery / Ground AA vs Plane / Air vs Ground without AA takes no counter-attack
            retRate = 0.0;
            if (dist > 1) killRate *= 0.65;
        } else if (isAirAttackingGround && dDef.airAttack > 0) {
            // Ground defender returning fire against attacking plane uses its Air Attack vs Plane's Air Defense
            int defDiff = dDef.airAttack - aDef.airDefense;
            retRate = Math.max(0.05, Math.min(0.60, 0.20 + defDiff * 0.035));
        }

        double atkEffStr = attacker.getEffectiveStrength();
        double defEffStr = defender.getEffectiveStrength();
        // Out of ammo halves defender return fire
        if (supplyEnabled && dDef.maxAmmo > 0 && defender.ammo <= 0) {
            defEffStr *= 0.5;
        }

        // Initiative First-Strike modifier (20% per initiative lead) - applies to close combat dist <= 1 (including dist 0 overhead)
        int iniDiff = aIni - dIni;
        double rawDefLosses = atkEffStr * killRate * 0.65;
        double survivingDefStr = defEffStr;
        if (iniDiff > 0 && dist <= 1) {
            double firstStrikeRatio = Math.min(1.0, iniDiff * 0.20);
            survivingDefStr = Math.max(0.0, defEffStr - rawDefLosses * firstStrikeRatio);
        } else if (iniDiff < 0 && dist <= 1) {
            double defFirstStrike = Math.min(1.0, (-iniDiff) * 0.20);
            double earlyAtkLoss = defEffStr * retRate * 0.55 * defFirstStrike;
            rawDefLosses = Math.max(0.0, (atkEffStr - earlyAtkLoss)) * killRate * 0.65;
        }

        pred.expectedDefenderLosses = Math.min(defender.strength, Math.max(0, (int) Math.round(rawDefLosses)));
        pred.expectedAttackerLosses = Math.min(attacker.strength, Math.max(0, (int) Math.round(survivingDefStr * retRate * 0.55)));
        // Pioneers / Engineers ignore enemy Rugged Defense
        pred.ruggedDefenseChance = (!isUnitOnAirLayer(defender) && dist == 1 && defender.entrenchment >= 2
                && !aDef.isPioneerOrEngineer() && aDef.unitClass != PgDatabase.CLASS_INFANTRY);
        pred.artillerySupport = (findDefensiveSupport(attacker, defender) != null);
        pred.fighterInterceptor = (findAirInterceptorOrFlak(attacker, defender) != null);
        pred.summary = String.format(Locale.ROOT, "Expected: Atk -%d vs Def -%d (Ini %d:%d)%s%s",
                pred.expectedAttackerLosses, pred.expectedDefenderLosses,
                aIni, dIni,
                pred.ruggedDefenseChance ? " [Rugged Def Risk]" : "",
                pred.fighterInterceptor ? " [Interceptor]" : (pred.artillerySupport ? " [Artillery Sup]" : ""));
        return pred;
    }

    public CombatReport executeCombat(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        lastUndoState = null;
        return executeCombatInternal(attacker, defender, false);
    }

    private CombatReport executeCombatInternal(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender, boolean surpriseAmbush) {
        CombatReport rep = new CombatReport();
        PgDatabase.UnitDef aDef = db.equipment.get(attacker.unitDefId);
        PgDatabase.UnitDef dDef = db.equipment.get(defender.unitDefId);
        rep.attackerName = aDef.name;
        rep.defenderName = dDef.name;
        rep.attackerId = attacker.instanceId;
        rep.defenderId = defender.instanceId;
        rep.attackerIconId = getEffectiveIconId(attacker);
        rep.defenderIconId = getEffectiveIconId(defender);
        rep.attackerStartStr = attacker.strength;
        rep.defenderStartStr = defender.strength;
        rep.targetCol = defender.col;
        rep.targetRow = defender.row;
        rep.surpriseContact = surpriseAmbush;

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

        CombatPrediction pred = predictCombat(attacker, defender);
        int atkLoss = Math.max(0, pred.expectedAttackerLosses + rng.nextInt(3) - 1);
        int defLoss = Math.max(0, pred.expectedDefenderLosses + rng.nextInt(3) - 1);

        // Surprise Ambush or Rugged Defense
        if (surpriseAmbush) {
            atkLoss = Math.min(attacker.strength, atkLoss + 2);
            defLoss = Math.max(0, defLoss - 1);
        } else if (pred.ruggedDefenseChance && rng.nextInt(100) < (defender.entrenchment * 12)) {
            rep.ruggedDefense = true;
            atkLoss = Math.min(attacker.strength, atkLoss + 2);
            defLoss = Math.max(0, defLoss - 1);
        }

        // Ranged artillery & Level Bombers cause suppression and strip entrenchment/supply
        if (aDef.unitClass == PgDatabase.CLASS_LEVEL_BOMBER && !isUnitOnAirLayer(defender)) {
            int sup = 2 + rng.nextInt(3);
            defender.suppression = Math.min(defender.strength, defender.suppression + sup);
            rep.defenderSuppressed = sup;
            defender.entrenchment = Math.max(0, defender.entrenchment - 2);
            if (supplyEnabled) {
                defender.fuel = (defender.fuel * 3) / 4;
                defender.ammo = (defender.ammo * 3) / 4;
            }
        } else if (dist > 1 || aDef.unitClass == PgDatabase.CLASS_ARTILLERY) {
            int sup = 1 + rng.nextInt(3);
            defender.suppression = Math.min(defender.strength, defender.suppression + sup);
            rep.defenderSuppressed = sup;
            if (!isUnitOnAirLayer(defender) && defender.entrenchment > 0) {
                defender.entrenchment = Math.max(0, defender.entrenchment - 1);
            }
        } else if (!isUnitOnAirLayer(defender) && defender.entrenchment > 0) {
            int entStrip = aDef.isPioneerOrEngineer() ? 2 : 1;
            defender.entrenchment = Math.max(0, defender.entrenchment - entStrip);
        }

        atkLoss = Math.min(attacker.strength, atkLoss);
        defLoss = Math.min(defender.strength, defLoss);

        attacker.strength -= atkLoss;
        defender.strength -= defLoss;
        attacker.attacked = true; // Every unit can attack once per turn
        attacker.mountedInTransport = false;
        if (supplyEnabled && aDef.maxAmmo > 0) attacker.ammo = Math.max(0, attacker.ammo - 1);
        boolean defenderCanReturnFire = (dist == 1 && !isUnitOnAirLayer(attacker) && !isUnitOnAirLayer(defender))
                || (isUnitOnAirLayer(attacker) && isUnitOnAirLayer(defender) && dist == 1)
                || (isUnitOnAirLayer(attacker) && !isUnitOnAirLayer(defender) && dist == 0 && dDef.airAttack > 0);
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

        if (rep.defenderDestroyed) {
            units.remove(defender);
            if (attacker.isAxis) axisPrestige += 25;

            // Panzer General Tank Overrun Rule:
            // If a Tank destroys a weak ground defender in close combat with 0 total attacker losses, it OVERRUNS!
            if (dist == 1 && aDef.unitClass == PgDatabase.CLASS_TANK && !isUnitOnAirLayer(defender)
                    && rep.attackerLosses == 0 && !rep.attackerDestroyed && rep.defenderStartStr <= 6
                    && getUnitStepMoveCost(attacker, aDef, scenario.grid[defOrigCol][defOrigRow]) < 99) {
                rep.overrun = true;
                attacker.col = defOrigCol;
                attacker.row = defOrigRow;
                PgDatabase.HexCell cell = scenario.grid[defOrigCol][defOrigRow];
                if (cell.isObjective || cell.isSupplyPoint() || cell.ownerNation > 0) {
                    cell.ownerNation = attacker.nation;
                    if (cell.isObjective && attacker.isAxis) axisPrestige += 100;
                }
            } else if (!attacker.isAxis && dist == 1 && !aDef.isAir() && !dDef.isAir() && !rep.attackerDestroyed
                    && getTerrainMoveCost(aDef, scenario.grid[defOrigCol][defOrigRow], false) < 99) {
                // AI automatically advances into vacated hex on close assault
                attacker.col = defOrigCol;
                attacker.row = defOrigRow;
                PgDatabase.HexCell cell = scenario.grid[defOrigCol][defOrigRow];
                if (cell.isObjective || cell.isSupplyPoint() || cell.ownerNation > 0) {
                    cell.ownerNation = attacker.nation;
                }
            }
        } else if (dist == 1 && !isUnitOnAirLayer(attacker) && !isUnitOnAirLayer(defender)
                && (defLoss >= 3 && defLoss > atkLoss || defender.getEffectiveStrength() == 0)) {
            // Defender is forced to retreat; if surrounded / all retreat hexes blocked by units or ZOC, Defender SURRENDERS!
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
                // Second pass: allow retreat to any passable unoccupied hex away from attacker
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
            if (!foundRetreat) {
                // No retreat path exists -> Unit SURRENDERS!
                rep.defenderSurrendered = true;
                rep.defenderDestroyed = true;
                rep.defenderLosses = rep.defenderStartStr;
                defender.strength = 0;
                units.remove(defender);
                if (attacker.isAxis) axisPrestige += 40;
            }
        }

        if (rep.attackerDestroyed) {
            units.remove(attacker);
        }

        recalculateFogOfWar();
        checkVictoryConditions();
        return rep;
    }

    private PgDatabase.ActiveUnit findAirInterceptorOrFlak(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        if (!isUnitOnAirLayer(attacker)) return null;
        // 1. Check adjacent friendly Fighter escort if target is a ground unit or bomber
        PgDatabase.UnitDef dDef = db.equipment.get(defender.unitDefId);
        if (!isUnitOnAirLayer(defender) || dDef.unitClass != PgDatabase.CLASS_FIGHTER) {
            for (int[] nb : getNeighbors(defender.col, defender.row)) {
                PgDatabase.ActiveUnit adjAir = getUnitAt(nb[0], nb[1], true);
                if (adjAir != null && adjAir.isAxis == defender.isAxis && adjAir.strength > 0
                        && (!supplyEnabled || (adjAir.ammo > 0 && adjAir.fuel > 0))) {
                    PgDatabase.UnitDef fDef = db.equipment.get(adjAir.unitDefId);
                    if (fDef.unitClass == PgDatabase.CLASS_FIGHTER) {
                        return adjAir;
                    }
                }
            }
        }
        // 2. Check adjacent friendly Air Defense (Flak) if target is a ground unit
        if (!isUnitOnAirLayer(defender)) {
            for (int[] nb : getNeighbors(defender.col, defender.row)) {
                PgDatabase.ActiveUnit adjGnd = getUnitAt(nb[0], nb[1], false);
                if (adjGnd != null && adjGnd.isAxis == defender.isAxis && adjGnd.strength > 0
                        && (!supplyEnabled || adjGnd.ammo > 0)) {
                    PgDatabase.UnitDef adDef = db.equipment.get(adjGnd.unitDefId);
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
            // Look for adjacent friendly artillery with range >= 2 and ammo
            for (int[] nb : getNeighbors(defender.col, defender.row)) {
                PgDatabase.ActiveUnit adj = getUnitAt(nb[0], nb[1], false);
                if (adj != null && adj.isAxis == defender.isAxis && (!supplyEnabled || adj.ammo > 0) && adj.strength > 0) {
                    PgDatabase.UnitDef adjDef = db.equipment.get(adj.unitDefId);
                    if (adjDef.unitClass == PgDatabase.CLASS_ARTILLERY && adjDef.range >= 2) {
                        return adj;
                    }
                }
            }
        }
        return null;
    }

    private int getAttackStat(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef,
                              PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef) {
        int base;
        if (isUnitOnAirLayer(defender)) base = aDef.airAttack;
        else if (defender.inSeaTransport || dDef.isNaval()) {
            base = Math.max(aDef.navalAttack, defender.inSeaTransport ? Math.max(aDef.softAttack, aDef.hardAttack) : 0);
        }
        else base = (dDef.targetType == PgDatabase.TARGET_HARD && !defender.mountedInTransport) ? aDef.hardAttack : aDef.softAttack;

        // River penalty for ground attacker on an unbridged river hex
        if (!isUnitOnAirLayer(attacker) && !attacker.inSeaTransport && !aDef.isNaval()) {
            PgDatabase.HexCell aCell = scenario.grid[attacker.col][attacker.row];
            if (aCell.terrainType == 4 && aCell.roadFlags == 0
                    && !aDef.isBridgeEngineer()
                    && !hasFriendlyBridgeEngineer(attacker.col, attacker.row, attacker.isAxis)) {
                base = Math.max(1, base - 2);
            }
        }
        return base;
    }

    private int getDefenseStat(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef,
                               PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef, int defCol, int defRow) {
        if (isUnitOnAirLayer(attacker) || isUnitOnAirLayer(defender)) return dDef.airDefense;
        PgDatabase.HexCell cell = scenario.grid[defCol][defRow];
        boolean closeCombat = cell.isCloseDefenseTerrain()
                && aDef.unitClass == PgDatabase.CLASS_INFANTRY
                && dDef.unitClass != PgDatabase.CLASS_INFANTRY;
        int def = closeCombat ? Math.max(1, dDef.closeDefense) : dDef.groundDefense;

        // River penalty for ground defender on an unbridged river hex
        if (!defender.inSeaTransport && !dDef.isNaval() && cell.terrainType == 4 && cell.roadFlags == 0
                && !dDef.isBridgeEngineer()
                && !hasFriendlyBridgeEngineer(defCol, defRow, defender.isAxis)) {
            def = Math.max(1, def - 2);
        }
        return def;
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
            PgDatabase.HexCell dropCell = scenario.grid[u.col][u.row];
            if (dropCell.isObjective || dropCell.isSupplyPoint() || dropCell.ownerNation > 0) {
                boolean wasEnemy = db.isAxisNation(dropCell.ownerNation, scenario) != u.isAxis;
                dropCell.ownerNation = u.nation;
                if (wasEnemy && u.isAxis) {
                    if (dropCell.isObjective) axisPrestige += 100;
                    else if (dropCell.terrainType == 8) axisPrestige += 40;
                }
            }
            recalculateFogOfWar();
            checkVictoryConditions();
            return def.name + " paradropped / landed at (" + u.col + "," + u.row + ")!";
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
            PgDatabase.HexCell landCell = scenario.grid[u.col][u.row];
            if (landCell.isObjective || landCell.isSupplyPoint() || landCell.ownerNation > 0) {
                boolean wasEnemy = db.isAxisNation(landCell.ownerNation, scenario) != u.isAxis;
                landCell.ownerNation = u.nation;
                if (wasEnemy && u.isAxis) {
                    if (landCell.isObjective) axisPrestige += 100;
                    else if (landCell.terrainType == 8) axisPrestige += 40;
                }
            }
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
            return def.name + (u.mountedInTransport ? " mounted transport." : " dismounted for combat.");
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
        PgDatabase.ActiveUnit u = new PgDatabase.ActiveUnit();
        u.instanceId = nextInstanceId++;
        u.unitDefId = unitDefId;
        u.transportDefId = transportDefId;
        u.nation = scenario.axisNations[0] > 0 ? scenario.axisNations[0] : 8;
        u.isAxis = true;
        u.isCore = true;
        u.col = col;
        u.row = row;
        u.strength = 10;
        u.entrenchment = 0;
        u.experience = 100;
        u.fuel = def.maxFuel > 0 ? def.maxFuel : 99;
        u.ammo = def.maxAmmo > 0 ? def.maxAmmo : 99;
        u.movePointsUsed = 0;
        u.moved = false;
        u.attacked = false;
        u.facingLeft = false;
        u.customTitle = u.instanceId + ". " + def.name;
        units.add(u);
        if (isCampaignMode) coreUnits.add(u);
        lastUndoState = null;
        recalculateFogOfWar();
        return u;
    }

    public List<CombatReport> endPlayerTurnAndRunAi() {
        List<CombatReport> aiReports = new ArrayList<>();
        isPlayerTurn = false;
        lastUndoState = null;

        // 1. Reset AI units & run AI turn
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

        // First fire AI ranged artillery
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !units.contains(ai)) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (def.range > 1) {
                List<PgDatabase.ActiveUnit> targets = getValidTargets(ai);
                if (!targets.isEmpty()) {
                    targets.sort(Comparator.comparingInt(t -> hexDistance(ai.col, ai.row, t.col, t.row)));
                    aiReports.add(executeCombat(ai, targets.get(0)));
                }
            }
        }

        // Next move and attack with remaining AI units
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !units.contains(ai)) continue;
            PgDatabase.HexCell curCell = scenario.grid[ai.col][ai.row];
            // If sitting on an objective, hold position and attack adjacent enemies
            if (curCell.isObjective && ai.entrenchment >= 2) {
                List<PgDatabase.ActiveUnit> targets = getValidTargets(ai);
                if (!targets.isEmpty()) {
                    aiReports.add(executeCombat(ai, targets.get(0)));
                }
                continue;
            }

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
                    aiReports.add(executeCombat(ai, bestTarget));
                    continue;
                }
            }

            // Move toward nearest Axis unit or threatened objective
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
            if (nearestEnemy != null && bestDist <= 8) {
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
                            aiReports.add(executeCombat(ai, postMoveTargets.get(0)));
                        }
                    }
                }
            }
        }

        // Advance turn counter and prepare Player units
        turn++;
        updateWeather();
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
            return;
        }
        int roll = rng.nextInt(100);
        if (roll < 72) weather = 0; // Clear
        else if (roll < 90) weather = 1; // Overcast
        else weather = 2; // Rain/Storm

        if (weather == 2) groundCondition = 1; // Mud
        else if (groundCondition == 1 && weather == 0) groundCondition = 0; // Dry
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
        SaveState st = new SaveState();
        st.isCampaign = isCampaignMode;
        st.campaignEntryIndex = campaignIndex;
        st.currentScenarioNumber = currentScenarioNumber;
        st.turn = turn;
        st.maxTurns = maxTurns;
        st.weather = weather;
        st.groundCondition = groundCondition;
        st.axisPrestige = axisPrestige;
        st.alliedPrestige = alliedPrestige;
        st.supplyEnabled = supplyEnabled;
        st.weatherEnabled = weatherEnabled;
        st.fogOfWarEnabled = fogOfWarEnabled;
        st.prestigeModifier = prestigeModifier;
        st.units = new ArrayList<>(units);
        st.coreRoster = new ArrayList<>(coreUnits);
        st.hexOwners = new int[scenario.cols][scenario.rows];
        for (int c = 0; c < scenario.cols; c++) {
            for (int r = 0; r < scenario.rows; r++) {
                st.hexOwners[c][r] = scenario.grid[c][r].ownerNation;
            }
        }
        st.scenarioTitle = scenario.meta.fullTitle;
        st.saveTimestamp = new java.text.SimpleDateFormat("MMM dd HH:mm", Locale.US).format(new Date());

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
