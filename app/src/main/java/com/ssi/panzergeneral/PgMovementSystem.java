package com.ssi.panzergeneral;

import java.util.*;

/**
 * Manages hex grid navigation, unit occupancy & layers, Fog of War & submarine spotting,
 * Zone of Control (ZOC), Dijkstra movement pathfinding, undo move, hex capture,
 * and air/sea/organic transport actions.
 */
public class PgMovementSystem {
    private final PgGameEngine engine;
    private final PgDatabase db;

    public PgMovementSystem(PgGameEngine engine) {
        this.engine = engine;
        this.db = engine.db;
    }

    public boolean isUnitOnAirLayer(PgDatabase.ActiveUnit u) {
        if (u == null) return false;
        if (u.inAirTransport) return true;
        if (u.unitDefId <= 0 || u.unitDefId >= db.equipment.size()) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        return def != null && def.isAir();
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

    public boolean canUnitCaptureCityOrAirport(PgDatabase.ActiveUnit u) {
        if (u == null || u.unitDefId <= 0 || u.unitDefId >= db.equipment.size()) return false;
        PgDatabase.UnitDef baseDef = db.equipment.get(u.unitDefId);
        return baseDef != null && baseDef.canCaptureCityOrAirport();
    }

    // Odd-q vertical hex layout neighbors (standard Panzer General hex grid)
    public List<int[]> getNeighbors(int col, int row) {
        if (engine.scenario == null) return Collections.emptyList();
        int[][] deltas = ((col & 1) == 0)
                ? new int[][]{{0, -1}, {1, -1}, {1, 0}, {0, 1}, {-1, 0}, {-1, -1}}
                : new int[][]{{0, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
        List<int[]> list = new ArrayList<>(6);
        for (int[] d : deltas) {
            int nc = col + d[0];
            int nr = row + d[1];
            if (nc >= 0 && nc < engine.scenario.cols && nr >= 0 && nr < engine.scenario.rows) {
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
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u == null || u.strength <= 0) continue;
            if (u.col == col && u.row == row) {
                if (isUnitOnAirLayer(u) == airLayer) return u;
            }
        }
        return null;
    }

    public PgDatabase.ActiveUnit getTopUnitAt(int col, int row, boolean preferAir) {
        PgDatabase.ActiveUnit first = getUnitAt(col, row, preferAir);
        if (first != null && first.strength > 0) return first;
        PgDatabase.ActiveUnit second = getUnitAt(col, row, !preferAir);
        if (second != null && second.strength > 0) return second;
        return null;
    }

    public boolean isUnitVisibleToPlayer(PgDatabase.ActiveUnit u) {
        if (u == null || u.strength <= 0) return false;
        if (!engine.fogOfWarEnabled || u.isAxis) return true;
        if (engine.visibleHexes == null || engine.scenario == null
                || u.col < 0 || u.col >= engine.scenario.cols || u.row < 0 || u.row >= engine.scenario.rows
                || !engine.visibleHexes[u.col][u.row]) {
            return false;
        }
        if (u.unitDefId <= 0 || u.unitDefId >= db.equipment.size()) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (def == null) return false;
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
                int hash = (engine.turn * 37 + u.instanceId * 23 + u.col * 7 + u.row) & 0x7FFFFFFF;
                if ((hash % 100) >= 50) return false;
            }
        }
        return true;
    }

    public Set<Integer> getSpottedEnemyIds() {
        Set<Integer> set = new HashSet<>();
        if (engine.scenario == null || engine.visibleHexes == null) return set;
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u != null && !u.isAxis && u.strength > 0 && isUnitVisibleToPlayer(u)) {
                set.add(u.instanceId);
            }
        }
        return set;
    }

    public void recalculateFogOfWar() {
        if (engine.scenario == null) return;
        for (int c = 0; c < engine.scenario.cols; c++) {
            for (int r = 0; r < engine.scenario.rows; r++) {
                if (!engine.fogOfWarEnabled) {
                    engine.visibleHexes[c][r] = true;
                } else {
                    PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                    engine.visibleHexes[c][r] = db.isAxisNation(cell.ownerNation, engine.scenario);
                }
            }
        }
        if (!engine.fogOfWarEnabled) return;
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u == null || !u.isAxis || u.strength <= 0) continue;
            if (u.unitDefId <= 0 || u.unitDefId >= db.equipment.size()) continue;
            PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
            if (def == null) continue;
            int spot = def.spotting;
            if (engine.weatherEnabled) {
                if (engine.weather == 1) { // Overcast: Air spotting halved, ground spotting -1
                    if (def.isAir() || u.inAirTransport) {
                        spot = Math.max(1, (spot + 1) / 2);
                    } else {
                        spot = Math.max(1, spot - 1);
                    }
                } else if (engine.weather == 2) { // Rain / Snow: Air spotting 1, ground spotting halved
                    if (def.isAir() || u.inAirTransport) {
                        spot = 1;
                    } else {
                        spot = Math.max(1, spot / 2);
                    }
                }
            }
            for (int c = Math.max(0, u.col - spot - 1); c <= Math.min(engine.scenario.cols - 1, u.col + spot + 1); c++) {
                for (int r = Math.max(0, u.row - spot - 1); r <= Math.min(engine.scenario.rows - 1, u.row + spot + 1); r++) {
                    if (hexDistance(u.col, u.row, c, r) <= spot) {
                        engine.visibleHexes[c][r] = true;
                    }
                }
            }
        }
    }

    public int getMaxMovementPoints(PgDatabase.ActiveUnit u) {
        if (u == null || u.strength <= 0) return 0;
        PgDatabase.UnitDef baseDef = db.equipment.get(u.unitDefId);
        if (baseDef.isFortification()) return 0;
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
        return baseDef.movement;
    }

    public int getRemainingMovementPoints(PgDatabase.ActiveUnit u) {
        if (u == null || u.moved || u.strength <= 0) return 0;
        PgDatabase.UnitDef baseDef = db.equipment.get(u.unitDefId);
        if (baseDef.isFortification()) return 0;
        PgDatabase.UnitDef effDef = getEffectiveUnitDef(u);
        int remMove = Math.max(0, getMaxMovementPoints(u) - u.movePointsUsed);
        if (engine.supplyEnabled && effDef != null && effDef.maxFuel > 0 && !u.inAirTransport && !u.inSeaTransport) {
            remMove = Math.min(remMove, u.fuel);
        }
        return remMove;
    }

    public Map<Integer, Integer> getReachableHexes(PgDatabase.ActiveUnit u) {
        Map<Integer, Integer> distMap = new HashMap<>();
        if (u == null || u.moved || u.strength <= 0 || engine.scenario == null) return distMap;
        PgDatabase.UnitDef baseDef = db.equipment.get(u.unitDefId);
        if (baseDef.isFortification()) return distMap;
        PgDatabase.UnitDef def = getEffectiveUnitDef(u);
        int maxMove = getRemainingMovementPoints(u);
        if (maxMove <= 0) return distMap;

        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[2]));
        int startKey = u.row * engine.scenario.cols + u.col;
        distMap.put(startKey, 0);
        pq.add(new int[]{u.col, u.row, 0});

        boolean isAir = isUnitOnAirLayer(u);

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int c = cur[0], r = cur[1], costSoFar = cur[2];
            if (costSoFar > distMap.getOrDefault(r * engine.scenario.cols + c, 999)) continue;

            // Check Enemy Zone of Control (ZOC): entering an enemy ZOC halts further movement along that path
            // Air units do not suffer from or exert ZOC
            if (!isAir && (c != u.col || r != u.row) && isInEnemyZoc(c, r, u)) {
                continue;
            }

            for (int[] nb : getNeighbors(c, r)) {
                int nc = nb[0], nr = nb[1];
                PgDatabase.HexCell cell = engine.scenario.grid[nc][nr];
                int stepCost = getUnitStepMoveCost(u, def, cell);
                if (stepCost >= 99) continue;

                // Cannot move through visible enemy units on the same layer
                PgDatabase.ActiveUnit blocker = getUnitAt(nc, nr, isAir);
                if (blocker != null && blocker.isAxis != u.isAxis && (!engine.fogOfWarEnabled || engine.visibleHexes[nc][nr])) {
                    continue;
                }

                boolean curIsWater = engine.scenario.grid[c][r].isWater();
                boolean nextIsWater = cell.isWater();
                boolean curIsWaterOrPort = curIsWater || engine.scenario.grid[c][r].isPort();

                // 1. Sea Transport disembarkation: moving from water/port onto land consumes all MP and halts further movement
                if (u.inSeaTransport && curIsWaterOrPort && !nextIsWater && !cell.isPort()) {
                    if (getTerrainMoveCost(baseDef, cell, false) >= 99) continue;
                    int nKey = nr * engine.scenario.cols + nc;
                    distMap.put(nKey, maxMove);
                    continue; // Disembarking ends movement along this path
                }

                // 2. Friendly Port embarkation: moving from friendly port into adjacent water
                boolean startInFriendlyPort = (c == u.col && r == u.row)
                        && engine.scenario.grid[c][r].isPort()
                        && db.isAxisNation(engine.scenario.grid[c][r].ownerNation, engine.scenario) == u.isAxis
                        && !def.isAir() && !def.isNaval();
                if (!u.inSeaTransport && startInFriendlyPort && nextIsWater) {
                    int nKey = nr * engine.scenario.cols + nc;
                    distMap.put(nKey, maxMove);
                    continue; // Embarking ends movement along this path
                }

                int newCost = costSoFar + stepCost;
                // Allow mobile units to always make at least 1 step into an adjacent passable hex
                if (costSoFar == 0 && maxMove >= 1 && stepCost < 99 && newCost > maxMove) {
                    newCost = maxMove;
                }

                int nKey = nr * engine.scenario.cols + nc;
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
            int hc = key % engine.scenario.cols;
            int hr = key / engine.scenario.cols;
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
                if (!engine.fogOfWarEnabled || engine.visibleHexes[nb[0]][nb[1]]) {
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
                if (!engine.fogOfWarEnabled || engine.visibleHexes[nb[0]][nb[1]]) {
                    return true;
                }
            }
        }
        return false;
    }

    public int getUnitStepMoveCost(PgDatabase.ActiveUnit u, PgDatabase.UnitDef def, PgDatabase.HexCell cell) {
        if (def.isFortification()) return 99;
        if (u.inAirTransport || def.isAir()) return 1;
        if (u.inSeaTransport) {
            if (cell.isWater() || cell.isPort()) return 1;
            // Entering land to disembark: check if ground terrain is passable for the underlying unit
            PgDatabase.UnitDef baseDef = db.equipment.get(u.unitDefId);
            if (getTerrainMoveCost(baseDef, cell, false) < 99) return 1;
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

    public int getTerrainMoveCost(PgDatabase.UnitDef def, PgDatabase.HexCell cell, boolean mounted) {
        if (def.isAir()) return 1;
        if (def.isNaval()) {
            return (cell.isWater() || cell.isPort()) ? 1 : 99;
        }
        if (cell.isWater()) return 99; // Water impassable without naval transport
        if (cell.roadFlags != 0) return 1; // Road / permanent bridge movement
        int mudPenalty = (engine.weatherEnabled && engine.groundCondition == 1) ? 1 : 0;
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

    public boolean canUndoLastMove() {
        return engine.isPlayerTurn && engine.lastUndoState != null && engine.lastUndoState.unit != null
                && engine.units.contains(engine.lastUndoState.unit) && engine.lastUndoState.unit.strength > 0;
    }

    public PgDatabase.ActiveUnit undoLastMove() {
        if (!canUndoLastMove()) return null;
        PgDatabase.ActiveUnit u = engine.lastUndoState.unit;
        u.col = engine.lastUndoState.prevCol;
        u.row = engine.lastUndoState.prevRow;
        u.fuel = engine.lastUndoState.prevFuel;
        u.movePointsUsed = 0;
        u.moved = false;
        u.attacked = engine.lastUndoState.prevAttacked;
        u.entrenchment = engine.lastUndoState.prevEntrenchment;
        u.facingLeft = engine.lastUndoState.prevFacingLeft;
        u.inSeaTransport = engine.lastUndoState.prevInSeaTransport;
        u.inAirTransport = engine.lastUndoState.prevInAirTransport;
        u.mountedInTransport = engine.lastUndoState.prevMountedInTransport;

        engine.scenario.grid[engine.lastUndoState.destCol][engine.lastUndoState.destRow].ownerNation = engine.lastUndoState.prevDestOwnerNation;
        engine.axisPrestige = engine.lastUndoState.prevAxisPrestige;
        if (engine.lastUndoState.capturedNewHex) {
            int hexKey = (engine.lastUndoState.destCol << 8) | engine.lastUndoState.destRow;
            engine.axisCapturedHexes.remove(hexKey);
        }
        engine.lastUndoState = null;
        recalculateFogOfWar();
        engine.checkVictoryConditions();
        return u;
    }

    public boolean moveUnit(PgDatabase.ActiveUnit u, int destCol, int destRow) {
        if (u == null || engine.scenario == null) return false;
        PgDatabase.UnitDef baseDef = db.equipment.get(u.unitDefId);
        if (baseDef.isFortification()) return false;
        engine.lastSurpriseReport = null;
        Map<Integer, Integer> reach = getReachableHexes(u);
        int key = destRow * engine.scenario.cols + destCol;
        if (!reach.containsKey(key)) return false;

        int cost = reach.get(key);
        PgDatabase.UnitDef def = getEffectiveUnitDef(u);
        boolean airLayer = isUnitOnAirLayer(u);

        // Check for Surprise Contact (Ambush!) with hidden enemy unit at destination
        PgDatabase.ActiveUnit hidden = getUnitAt(destCol, destRow, airLayer);
        if (hidden != null && hidden.isAxis != u.isAxis) {
            engine.lastUndoState = null;
            u.movePointsUsed += Math.max(1, cost);
            u.moved = true;
            recalculateFogOfWar();
            // Hidden enemy ambushes the moving unit with Surprise Contact!
            PgGameEngine.CombatReport ambush = engine.combatSystem.executeCombatInternal(u, hidden, true);
            engine.lastSurpriseReport = ambush;
            return true;
        }

        int hexKey = (destCol << 8) | destRow;
        boolean wasNewHex = !engine.axisCapturedHexes.contains(hexKey);

        PgGameEngine.UndoMoveState undo = PgGameEngine.UndoMoveState.builder()
                .unit(u)
                .prevCol(u.col)
                .prevRow(u.row)
                .prevFuel(u.fuel)
                .prevMovePointsUsed(0)
                .prevMoved(false)
                .prevAttacked(u.attacked)
                .prevInSeaTransport(u.inSeaTransport)
                .prevInAirTransport(u.inAirTransport)
                .prevMountedInTransport(u.mountedInTransport)
                .prevEntrenchment(u.entrenchment)
                .prevFacingLeft(u.facingLeft)
                .destCol(destCol)
                .destRow(destRow)
                .prevDestOwnerNation(engine.scenario.grid[destCol][destRow].ownerNation)
                .prevAxisPrestige(engine.axisPrestige)
                .capturedNewHex(wasNewHex)
                .build();

        boolean wasSeaTransport = u.inSeaTransport;
        PgDatabase.HexCell destCell = engine.scenario.grid[destCol][destRow];

        if (destCol < u.col) u.facingLeft = true;
        else if (destCol > u.col) u.facingLeft = false;

        u.col = destCol;
        u.row = destRow;
        u.movePointsUsed += Math.max(1, cost);
        u.entrenchment = 0;
        if (engine.supplyEnabled && def.maxFuel > 0 && !u.inAirTransport && !u.inSeaTransport) {
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
        if (u.isAxis) {
            engine.lastUndoState = undo;
        } else {
            engine.lastUndoState = null;
        }

        // Every unit can move once per turn
        u.moved = true;
        engine.checkVictoryConditions();
        return true;
    }

    public boolean isRiverHex(int col, int row) {
        if (engine.scenario == null || col < 0 || col >= engine.scenario.cols || row < 0 || row >= engine.scenario.rows) return false;
        PgDatabase.HexCell cell = engine.scenario.grid[col][row];
        return cell.terrainType == 4 && cell.roadFlags == 0;
    }

    public void onHexCaptured(int col, int row, PgDatabase.ActiveUnit capturer) {
        if (engine.scenario == null || col < 0 || col >= engine.scenario.cols || row < 0 || row >= engine.scenario.rows || capturer == null) return;
        PgDatabase.HexCell cell = engine.scenario.grid[col][row];
        boolean isCityPortAirfield = (cell.terrainType == 8 || cell.terrainType == 6 || cell.isPort());
        if (!cell.isObjective && !isCityPortAirfield && cell.ownerNation == 0) return;

        // 1994 Panzer General Rule:
        // Artillery (and AA/Air/Naval/Forts) cannot take possession of cities or airports.
        // Only infantry, recon, tanks, and anti-tank guns can capture.
        if (!canUnitCaptureCityOrAirport(capturer)) {
            return;
        }

        boolean wasEnemy = db.isAxisNation(cell.ownerNation, engine.scenario) != capturer.isAxis;
        cell.ownerNation = capturer.nation;

        // Strategy Guide Chapter 6 Initial Capture Prestige (+160/+40 to capturer, -80/-20 to loser)
        if (wasEnemy) {
            int hexKey = (col << 8) | row;
            if (capturer.isAxis) {
                if (!engine.axisCapturedHexes.contains(hexKey)) {
                    engine.axisCapturedHexes.add(hexKey);
                    int gain = cell.isObjective ? 160 : 40;
                    engine.axisPrestige += gain;
                }
                if (!engine.alliedLostHexes.contains(hexKey)) {
                    engine.alliedLostHexes.add(hexKey);
                    int loss = cell.isObjective ? 80 : 20;
                    engine.alliedPrestige = Math.max(0, engine.alliedPrestige - loss);
                }
            } else {
                if (!engine.alliedCapturedHexes.contains(hexKey)) {
                    engine.alliedCapturedHexes.add(hexKey);
                    int gain = cell.isObjective ? 160 : 40;
                    engine.alliedPrestige += gain;
                }
                if (!engine.axisLostHexes.contains(hexKey)) {
                    engine.axisLostHexes.add(hexKey);
                    int loss = cell.isObjective ? 80 : 20;
                    engine.axisPrestige = Math.max(0, engine.axisPrestige - loss);
                }
            }
        }
    }

    // Panzer General Manual: Embark / Disembark Air Transport (Paratroops), Sea Transport (Ports), and Organic Transport
    public String getTransportActionLabel(PgDatabase.ActiveUnit u) {
        if (u == null || !u.isAxis) return "MOUNT";
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (def.isFortification()) return "IMMOBILE";
        if (u.inAirTransport) return "PARADROP";
        if (u.inSeaTransport) return "DISEMBARK";
        PgDatabase.HexCell cell = engine.scenario.grid[u.col][u.row];
        if (!def.isAir() && !def.isNaval() && !u.attacked) {
            if (cell.terrainType == 6 && db.isAxisNation(cell.ownerNation, engine.scenario)
                    && (def.unitClass == PgDatabase.CLASS_INFANTRY || def.isParatroop())
                    && !u.moved && u.movePointsUsed == 0
                    && getUnitAt(u.col, u.row, true) == null) {
                return "AIRLIFT";
            }
            if (cell.isPort() && db.isAxisNation(cell.ownerNation, engine.scenario)) {
                return "SEA EMBARK";
            }
        }
        return u.mountedInTransport ? "UNMOUNT" : "MOUNT";
    }

    public boolean canUseTransportAction(PgDatabase.ActiveUnit u) {
        if (u == null || !u.isAxis || !engine.isPlayerTurn || u.strength <= 0 || engine.scenario == null) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (def.isFortification()) return false;
        if (u.col < 0 || u.col >= engine.scenario.cols || u.row < 0 || u.row >= engine.scenario.rows) return false;
        PgDatabase.HexCell cell = engine.scenario.grid[u.col][u.row];
        if (u.inAirTransport) {
            return getUnitAt(u.col, u.row, false) == null && getTerrainMoveCost(def, cell, false) < 99;
        }
        if (u.inSeaTransport) {
            if (!cell.isWater()) return true; // In port or on land: can disembark in place
            if (getTerrainMoveCost(def, cell, false) < 99) return true;
            for (int[] nb : getNeighbors(u.col, u.row)) {
                if (getUnitAt(nb[0], nb[1], false) == null
                        && getTerrainMoveCost(def, engine.scenario.grid[nb[0]][nb[1]], false) < 99) {
                    return true;
                }
            }
            return false;
        }
        if (!u.attacked && !def.isAir() && !def.isNaval()) {
            if (cell.isPort() && db.isAxisNation(cell.ownerNation, engine.scenario)) {
                return true;
            }
            if (!u.moved && u.movePointsUsed == 0 && cell.terrainType == 6 && db.isAxisNation(cell.ownerNation, engine.scenario)
                    && (def.unitClass == PgDatabase.CLASS_INFANTRY || def.isParatroop())
                    && getUnitAt(u.col, u.row, true) == null) {
                return true;
            }
        }
        return !u.moved && u.movePointsUsed == 0 && u.transportDefId > 0;
    }

    public String executeTransportAction(PgDatabase.ActiveUnit u) {
        if (!canUseTransportAction(u)) return null;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        PgDatabase.HexCell cell = engine.scenario.grid[u.col][u.row];
        engine.lastUndoState = null;

        if (u.inAirTransport) {
            u.inAirTransport = false;
            u.moved = true;
            u.attacked = true;
            u.movePointsUsed = getMaxMovementPoints(u);
            int origCol = u.col;
            int origRow = u.row;
            boolean scattered = false;
            // Strategy Guide Chapter 3 Paratroop Scatter: 50% on target, 50% scatters 1 hex
            if (engine.rng.nextInt(100) < 50) {
                List<int[]> validScatters = new ArrayList<>();
                for (int[] nb : getNeighbors(origCol, origRow)) {
                    PgDatabase.HexCell nbCell = engine.scenario.grid[nb[0]][nb[1]];
                    if (!nbCell.isWater() && getUnitAt(nb[0], nb[1], false) == null
                            && getTerrainMoveCost(def, nbCell, false) < 99) {
                        validScatters.add(nb);
                    }
                }
                if (!validScatters.isEmpty()) {
                    int[] chosen = validScatters.get(engine.rng.nextInt(validScatters.size()));
                    u.col = chosen[0];
                    u.row = chosen[1];
                    scattered = true;
                }
            }
            onHexCaptured(u.col, u.row, u);
            recalculateFogOfWar();
            engine.checkVictoryConditions();
            return def.name + " paradropped / landed at (" + u.col + "," + u.row + ")" + (scattered ? " [Scattered!]" : "!");
        }
        if (u.inSeaTransport) {
            if (cell.isWater()) {
                for (int[] nb : getNeighbors(u.col, u.row)) {
                    PgDatabase.HexCell nbCell = engine.scenario.grid[nb[0]][nb[1]];
                    if (!nbCell.isWater() && getUnitAt(nb[0], nb[1], false) == null
                            && getTerrainMoveCost(def, nbCell, false) < 99) {
                        u.col = nb[0];
                        u.row = nb[1];
                        break;
                    }
                }
                u.inSeaTransport = false;
                u.moved = true;
                u.attacked = true;
                u.movePointsUsed = getMaxMovementPoints(u);
                onHexCaptured(u.col, u.row, u);
                recalculateFogOfWar();
                engine.checkVictoryConditions();
                return def.name + " disembarked at (" + u.col + "," + u.row + ")!";
            } else {
                // In port hex: disembark back onto port land
                u.inSeaTransport = false;
                if (!u.moved && u.movePointsUsed == 0) {
                    u.attacked = false;
                } else {
                    u.moved = true;
                    u.attacked = true;
                    u.movePointsUsed = getMaxMovementPoints(u);
                }
                recalculateFogOfWar();
                return def.name + " disembarked in port.";
            }
        }
        if (!u.attacked && !def.isAir() && !def.isNaval()) {
            if (cell.terrainType == 6 && db.isAxisNation(cell.ownerNation, engine.scenario)
                    && (def.unitClass == PgDatabase.CLASS_INFANTRY || def.isParatroop())
                    && getUnitAt(u.col, u.row, true) == null && !u.moved && u.movePointsUsed == 0) {
                u.inAirTransport = true;
                u.mountedInTransport = false;
                u.moved = true;
                u.attacked = true;
                u.movePointsUsed = getMaxMovementPoints(u);
                return def.name + " embarked into Ju 52 Air Transport!";
            }
            if (cell.isPort() && db.isAxisNation(cell.ownerNation, engine.scenario)) {
                boolean hadMoved = u.moved || u.movePointsUsed > 0;
                u.inSeaTransport = true;
                u.mountedInTransport = false;
                u.entrenchment = 0;
                u.attacked = true; // Sea transport cannot attack
                if (hadMoved) {
                    u.moved = true;
                    u.movePointsUsed = getMaxMovementPoints(u);
                    return def.name + " embarked into Naval Transport (movement completed for this turn).";
                } else {
                    u.moved = false;
                    u.movePointsUsed = 0;
                    return def.name + " embarked into Naval Transport! Ready to sail (" + getMaxMovementPoints(u) + " MP).";
                }
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
}
