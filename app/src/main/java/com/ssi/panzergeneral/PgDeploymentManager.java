package com.ssi.panzergeneral;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages campaign core unit deployment, reserve sorting, besieged base blocking rules,
 * and player/AI unit purchasing and placement.
 */
public class PgDeploymentManager {
    private final PgGameEngine engine;
    private final PgDatabase db;

    public PgDeploymentManager(PgGameEngine engine) {
        this.engine = engine;
        this.db = engine.db;
    }

    public boolean hasEnemyAdjacentOrOnHex(int col, int row, boolean isAxis) {
        if (engine.scenario == null || col < 0 || col >= engine.scenario.cols || row < 0 || row >= engine.scenario.rows) return false;
        PgDatabase.ActiveUnit gOn = engine.getUnitAt(col, row, false);
        if (gOn != null && gOn.isAxis != isAxis && gOn.strength > 0) return true;
        PgDatabase.ActiveUnit aOn = engine.getUnitAt(col, row, true);
        if (aOn != null && aOn.isAxis != isAxis && aOn.strength > 0) return true;

        for (int[] nb : engine.getNeighbors(col, row)) {
            PgDatabase.ActiveUnit gNb = engine.getUnitAt(nb[0], nb[1], false);
            if (gNb != null && gNb.isAxis != isAxis && gNb.strength > 0) return true;
            PgDatabase.ActiveUnit aNb = engine.getUnitAt(nb[0], nb[1], true);
            if (aNb != null && aNb.isAxis != isAxis && aNb.strength > 0) return true;
        }
        return false;
    }

    public boolean isCityAirportOrSupply(PgDatabase.HexCell cell) {
        if (cell == null) return false;
        return cell.terrainType == 8 || cell.terrainType == 6 || cell.isPort() || cell.isSupplyPoint() || cell.isObjective;
    }

    /**
     * If an enemy unit is next to a city or supply area or airport,
     * a new unit cannot be placed at that hex or any surrounding hex.
     */
    public boolean isHexBlockedByEnemyNearBase(int col, int row, boolean isAxis) {
        if (engine.scenario == null || col < 0 || col >= engine.scenario.cols || row < 0 || row >= engine.scenario.rows) return true;

        // 1. Direct enemy presence on candidate hex or adjacent to candidate hex
        if (hasEnemyAdjacentOrOnHex(col, row, isAxis)) {
            return true;
        }

        // 2. If candidate hex itself is a city, airport, or supply area,
        // and an enemy unit is next to it (or on it):
        PgDatabase.HexCell cell = engine.scenario.grid[col][row];
        if (isCityAirportOrSupply(cell) && hasEnemyAdjacentOrOnHex(col, row, isAxis)) {
            return true;
        }

        // 3. If candidate hex is a surrounding hex to any city, airport, or supply area
        // that has an enemy unit next to it (or on it):
        for (int[] nb : engine.getNeighbors(col, row)) {
            PgDatabase.HexCell nbCell = engine.scenario.grid[nb[0]][nb[1]];
            if (isCityAirportOrSupply(nbCell) && hasEnemyAdjacentOrOnHex(nb[0], nb[1], isAxis)) {
                return true;
            }
        }

        return false;
    }

    // =========================================================================
    // CAMPAIGN CORE DEPLOYMENT PHASE METHODS
    // =========================================================================
    public List<int[]> getValidCoreDeploymentHexes(PgDatabase.ActiveUnit u) {
        List<int[]> res = new ArrayList<>();
        if (engine.scenario == null || u == null) return res;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        boolean isAir = def != null && def.isAir();
        boolean[][] added = new boolean[engine.scenario.cols][engine.scenario.rows];

        // 1. Scenario designated deployment hexes
        for (int[] d : engine.scenario.deployHexes) {
            int dc = d[0], dr = d[1];
            if (dc >= 0 && dc < engine.scenario.cols && dr >= 0 && dr < engine.scenario.rows) {
                // Ground units (in sea transports) and planes (in flight) can both deploy on water deploy hexes without a carrier
                boolean allowWater = true;
                if (!added[dc][dr] && canDeployAtHex(dc, dr, isAir, allowWater) && !isHexBlockedByEnemyNearBase(dc, dr, true)) {
                    added[dc][dr] = true;
                    res.add(new int[]{dc, dr});
                }
            }
        }

        // 2. Allow planes to be deployed on the same hex as other deployed units if they are land units
        if (isAir) {
            for (PgDatabase.ActiveUnit other : engine.units) {
                if (other != null && other.isAxis && other.strength > 0 && !engine.isUnitOnAirLayer(other)) {
                    PgDatabase.UnitDef oDef = (other.unitDefId > 0 && other.unitDefId < db.equipment.size())
                            ? db.equipment.get(other.unitDefId) : null;
                    if (oDef != null && !oDef.isAir() && !oDef.isNaval()) {
                        int c = other.col, r = other.row;
                        if (c >= 0 && c < engine.scenario.cols && r >= 0 && r < engine.scenario.rows) {
                            if (!added[c][r] && canDeployAtHex(c, r, true, true) && !isHexBlockedByEnemyNearBase(c, r, true)) {
                                added[c][r] = true;
                                res.add(new int[]{c, r});
                            }
                        }
                    }
                }
            }
        }

        // 3. Controlled cities (for ground), controlled airfields (for air), or controlled ports/supply areas
        for (int c = 0; c < engine.scenario.cols; c++) {
            for (int r = 0; r < engine.scenario.rows; r++) {
                PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                boolean isBase = isAir ? (cell.terrainType == 6) : (cell.terrainType == 8 || cell.isPort() || cell.isSupplyPoint());
                boolean isControlledBase = isBase && db.isAxisNation(cell.ownerNation, engine.scenario);
                if (isControlledBase) {
                    // If an enemy unit is next to a city or supply area or airport,
                    // a new unit cannot be placed at that hex or any surrounding hex.
                    if (hasEnemyAdjacentOrOnHex(c, r, true)) continue;

                    boolean allowWaterOnBase = cell.isWater() || cell.isPort() || cell.isDeployHex;
                    if (!added[c][r] && canDeployAtHex(c, r, isAir, allowWaterOnBase) && !isHexBlockedByEnemyNearBase(c, r, true)) {
                        added[c][r] = true;
                        res.add(new int[]{c, r});
                    }
                    for (int[] nb : engine.getNeighbors(c, r)) {
                        int nc = nb[0], nr = nb[1];
                        if (nc >= 0 && nc < engine.scenario.cols && nr >= 0 && nr < engine.scenario.rows) {
                            PgDatabase.HexCell nbCell = engine.scenario.grid[nc][nr];
                            boolean allowWaterNb = cell.isPort() || nbCell.isDeployHex || nbCell.isPort();
                            if (!added[nc][nr] && canDeployAtHex(nc, nr, isAir, allowWaterNb) && !isHexBlockedByEnemyNearBase(nc, nr, true)) {
                                added[nc][nr] = true;
                                res.add(new int[]{nc, nr});
                            }
                        }
                    }
                }
            }
        }
        return res;
    }

    public boolean isHexValidForCoreDeployment(int col, int row) {
        if (!engine.isDeploymentPhase || engine.scenario == null || engine.unplacedCoreUnits.isEmpty()) return false;
        if (col < 0 || col >= engine.scenario.cols || row < 0 || row >= engine.scenario.rows) return false;
        for (PgDatabase.ActiveUnit u : engine.unplacedCoreUnits) {
            for (int[] h : getValidCoreDeploymentHexes(u)) {
                if (h[0] == col && h[1] == row) return true;
            }
        }
        return false;
    }

    public boolean deployCoreUnit(PgDatabase.ActiveUnit u, int col, int row) {
        if (!engine.isDeploymentPhase || u == null) return false;
        if (isHexBlockedByEnemyNearBase(col, row, true)) return false;
        List<int[]> validSpots = getValidCoreDeploymentHexes(u);
        boolean valid = false;
        for (int[] s : validSpots) {
            if (s[0] == col && s[1] == row) {
                valid = true;
                break;
            }
        }
        if (!valid) return false;

        u.col = col;
        u.row = row;
        u.movePointsUsed = 0;
        u.moved = false;
        u.attacked = false;

        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        boolean onWater = engine.scenario.grid[col][row].terrainType == 2 || engine.scenario.grid[col][row].isWater();
        if (def != null && !def.isAir() && !def.isNaval() && onWater) {
            u.inSeaTransport = true;
        } else {
            u.inSeaTransport = false;
        }

        if (!engine.units.contains(u)) {
            engine.units.add(u);
        }
        engine.unplacedCoreUnits.remove(u);
        engine.recalculateFogOfWar();
        return true;
    }

    public void sortUnitsByTypeAndName(List<PgDatabase.ActiveUnit> list) {
        if (list == null || list.size() <= 1) return;
        List<PgDatabase.ActiveUnit> copy = new ArrayList<>(list);
        Collections.sort(copy, (u1, u2) -> {
            PgDatabase.UnitDef d1 = (u1 != null && u1.unitDefId > 0 && u1.unitDefId < db.equipment.size())
                    ? db.equipment.get(u1.unitDefId) : null;
            PgDatabase.UnitDef d2 = (u2 != null && u2.unitDefId > 0 && u2.unitDefId < db.equipment.size())
                    ? db.equipment.get(u2.unitDefId) : null;
            int c1 = d1 != null ? d1.unitClass : 999;
            int c2 = d2 != null ? d2.unitClass : 999;
            if (c1 != c2) return Integer.compare(c1, c2);
            String n1 = d1 != null && d1.name != null ? d1.name : "";
            String n2 = d2 != null && d2.name != null ? d2.name : "";
            int nameCmp = n1.compareToIgnoreCase(n2);
            if (nameCmp != 0) return nameCmp;
            int id1 = u1 != null ? u1.instanceId : 0;
            int id2 = u2 != null ? u2.instanceId : 0;
            return Integer.compare(id1, id2);
        });
        if (list instanceof CopyOnWriteArrayList) {
            synchronized (list) {
                list.clear();
                list.addAll(copy);
            }
        } else {
            list.clear();
            list.addAll(copy);
        }
    }

    public void sortUnplacedCoreUnits() {
        sortUnitsByTypeAndName(engine.unplacedCoreUnits);
    }

    public boolean undeployCoreUnit(PgDatabase.ActiveUnit u) {
        if (!engine.isDeploymentPhase || u == null || !u.isCore) return false;
        if (engine.units.remove(u)) {
            u.col = -1;
            u.row = -1;
            u.inSeaTransport = false;
            if (!engine.unplacedCoreUnits.contains(u)) {
                engine.unplacedCoreUnits.add(u);
                sortUnplacedCoreUnits();
            }
            engine.recalculateFogOfWar();
            return true;
        }
        return false;
    }

    public void startBattleAfterDeployment() {
        engine.isDeploymentPhase = false;
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u != null && u.isAxis) {
                u.moved = false;
                u.attacked = false;
                u.movePointsUsed = 0;
            }
        }
        engine.recalculateFogOfWar();
    }

    public List<int[]> getAvailableDeployHexes() {
        return getAvailableDeployHexes(0);
    }

    public List<int[]> getAvailableDeployHexes(int unitDefId) {
        List<int[]> res = new ArrayList<>();
        if (engine.scenario == null) return res;

        // If in deployment phase, allow deploying purchased units on scenario deployment hexes as well
        if (engine.isDeploymentPhase) {
            PgDatabase.UnitDef def = (unitDefId > 0 && unitDefId < db.equipment.size()) ? db.equipment.get(unitDefId) : null;
            boolean isAir = def != null && def.isAir();
            boolean[][] added = new boolean[engine.scenario.cols][engine.scenario.rows];
            for (int[] d : engine.scenario.deployHexes) {
                int dc = d[0], dr = d[1];
                if (dc >= 0 && dc < engine.scenario.cols && dr >= 0 && dr < engine.scenario.rows) {
                    boolean allowWater = true;
                    if (!added[dc][dr] && canDeployAtHex(dc, dr, isAir, allowWater) && !isHexBlockedByEnemyNearBase(dc, dr, true)) {
                        added[dc][dr] = true;
                        res.add(new int[]{dc, dr});
                    }
                }
            }
            if (isAir) {
                for (PgDatabase.ActiveUnit other : engine.units) {
                    if (other != null && other.isAxis && other.strength > 0 && !engine.isUnitOnAirLayer(other)) {
                        PgDatabase.UnitDef oDef = (other.unitDefId > 0 && other.unitDefId < db.equipment.size())
                                ? db.equipment.get(other.unitDefId) : null;
                        if (oDef != null && !oDef.isAir() && !oDef.isNaval()) {
                            int c = other.col, r = other.row;
                            if (c >= 0 && c < engine.scenario.cols && r >= 0 && r < engine.scenario.rows) {
                                if (!added[c][r] && canDeployAtHex(c, r, true, true) && !isHexBlockedByEnemyNearBase(c, r, true)) {
                                    added[c][r] = true;
                                    res.add(new int[]{c, r});
                                }
                            }
                        }
                    }
                }
            }
            for (int c = 0; c < engine.scenario.cols; c++) {
                for (int r = 0; r < engine.scenario.rows; r++) {
                    PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                    boolean isBase = isAir ? (cell.terrainType == 6) : (cell.terrainType == 8 || cell.isPort() || cell.isSupplyPoint());
                    boolean isControlledBase = isBase && db.isAxisNation(cell.ownerNation, engine.scenario);
                    if (isControlledBase) {
                        if (hasEnemyAdjacentOrOnHex(c, r, true)) continue;
                        boolean allowWaterOnBase = !isAir && (cell.isWater() || cell.isPort() || cell.isDeployHex);
                        if (!added[c][r] && canDeployAtHex(c, r, isAir, allowWaterOnBase) && !isHexBlockedByEnemyNearBase(c, r, true)) {
                            added[c][r] = true;
                            res.add(new int[]{c, r});
                        }
                        for (int[] nb : engine.getNeighbors(c, r)) {
                            int nc = nb[0], nr = nb[1];
                            if (nc >= 0 && nc < engine.scenario.cols && nr >= 0 && nr < engine.scenario.rows) {
                                PgDatabase.HexCell nbCell = engine.scenario.grid[nc][nr];
                                boolean allowWaterNb = !isAir && (cell.isPort() || nbCell.isDeployHex || nbCell.isPort());
                                if (!added[nc][nr] && canDeployAtHex(nc, nr, isAir, allowWaterNb) && !isHexBlockedByEnemyNearBase(nc, nr, true)) {
                                    added[nc][nr] = true;
                                    res.add(new int[]{nc, nr});
                                }
                            }
                        }
                    }
                }
            }
            return res;
        }

        // Initial scenario setup
        if (unitDefId == 0) {
            boolean[][] added = new boolean[engine.scenario.cols][engine.scenario.rows];
            for (int[] d : engine.scenario.deployHexes) {
                int dc = d[0], dr = d[1];
                if (dc >= 0 && dc < engine.scenario.cols && dr >= 0 && dr < engine.scenario.rows) {
                    if (!added[dc][dr] && canDeployAtHex(dc, dr, false, true) && !isHexBlockedByEnemyNearBase(dc, dr, true)) {
                        added[dc][dr] = true;
                        res.add(new int[]{dc, dr});
                    }
                }
            }
            for (int c = 0; c < engine.scenario.cols; c++) {
                for (int r = 0; r < engine.scenario.rows; r++) {
                    PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                    if (cell.terrainType == 8 && db.isAxisNation(cell.ownerNation, engine.scenario)) {
                        if (!added[c][r] && canDeployAtHex(c, r, false, false) && !isHexBlockedByEnemyNearBase(c, r, true)) {
                            added[c][r] = true;
                            res.add(new int[]{c, r});
                        }
                    }
                }
            }
            return res;
        }

        PgDatabase.UnitDef def = (unitDefId > 0 && unitDefId < db.equipment.size()) ? db.equipment.get(unitDefId) : null;
        boolean isAir = def != null && def.isAir();
        boolean[][] added = new boolean[engine.scenario.cols][engine.scenario.rows];

        // 1994 Panzer General Rule:
        // - Purchased ground units can only be placed on or adjacent to player-controlled cities (terrainType 8) or supply areas
        // - Purchased planes can only be placed on or adjacent to player-controlled airports / airfields (terrainType 6)
        //   or on the same hex as other deployed friendly land units
        // - If an enemy unit is on or next to a city or supply area or airport, a new unit cannot be placed at that hex or any surrounding hex!
        if (isAir) {
            for (PgDatabase.ActiveUnit other : engine.units) {
                if (other != null && other.isAxis && other.strength > 0 && !engine.isUnitOnAirLayer(other)) {
                    PgDatabase.UnitDef oDef = (other.unitDefId > 0 && other.unitDefId < db.equipment.size())
                            ? db.equipment.get(other.unitDefId) : null;
                    if (oDef != null && !oDef.isAir() && !oDef.isNaval()) {
                        int c = other.col, r = other.row;
                        if (c >= 0 && c < engine.scenario.cols && r >= 0 && r < engine.scenario.rows) {
                            if (!added[c][r] && canDeployAtHex(c, r, true, true) && !isHexBlockedByEnemyNearBase(c, r, true)) {
                                added[c][r] = true;
                                res.add(new int[]{c, r});
                            }
                        }
                    }
                }
            }
        }

        for (int c = 0; c < engine.scenario.cols; c++) {
            for (int r = 0; r < engine.scenario.rows; r++) {
                PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                boolean isBase = isAir ? (cell.terrainType == 6) : (cell.terrainType == 8 || cell.isPort() || cell.isSupplyPoint());
                if (isBase) {
                    // Update ownership if currently occupied by an Axis ground unit capable of capturing
                    PgDatabase.ActiveUnit gndOcc = engine.getUnitAt(c, r, false);
                    if (gndOcc != null && gndOcc.isAxis && engine.canUnitCaptureCityOrAirport(gndOcc)
                            && !db.isAxisNation(cell.ownerNation, engine.scenario)) {
                        cell.ownerNation = gndOcc.nation;
                    }

                    if (db.isAxisNation(cell.ownerNation, engine.scenario)) {
                        // If enemy has a unit next to the city/supply area/airport (or on it),
                        // no units can be placed at that hex or any surrounding hex
                        if (hasEnemyAdjacentOrOnHex(c, r, true)) {
                            continue;
                        }

                        // Base hex itself (if unoccupied, valid terrain, and not blocked)
                        if (!added[c][r] && canDeployAtHex(c, r, isAir) && !isHexBlockedByEnemyNearBase(c, r, true)) {
                            added[c][r] = true;
                            res.add(new int[]{c, r});
                        }

                        // Adjacent hexagons next to the controlled city/airport/supply area
                        for (int[] nb : engine.getNeighbors(c, r)) {
                            int nc = nb[0], nr = nb[1];
                            // Candidate deploy hex must be unoccupied, valid terrain,
                            // and not blocked by enemy near any base
                            if (!added[nc][nr] && canDeployAtHex(nc, nr, isAir)
                                    && !isHexBlockedByEnemyNearBase(nc, nr, true)) {
                                added[nc][nr] = true;
                                res.add(new int[]{nc, nr});
                            }
                        }
                    }
                }
            }
        }

        return res;
    }

    public boolean canDeployAtHex(int col, int row, boolean isAir) {
        return canDeployAtHex(col, row, isAir, false);
    }

    public boolean canDeployAtHex(int col, int row, boolean isAir, boolean allowWater) {
        if (col < 0 || col >= engine.scenario.cols || row < 0 || row >= engine.scenario.rows) return false;
        // Target layer (air vs. ground) must be unoccupied
        if (engine.getUnitAt(col, row, isAir) != null) return false;
        // Cannot deploy onto a hex that has an enemy unit on the other layer
        PgDatabase.ActiveUnit otherLayerUnit = engine.getUnitAt(col, row, !isAir);
        if (otherLayerUnit != null && !otherLayerUnit.isAxis) return false;

        boolean isWaterHex = engine.scenario.grid[col][row].terrainType == 2 || engine.scenario.grid[col][row].isWater();
        // Air units and ground units can deploy onto water when allowWater is true (e.g. deployment phase)
        if (isWaterHex && !allowWater) return false;
        return true;
    }

    public PgDatabase.ActiveUnit purchaseUnit(int unitDefId, int transportDefId, int col, int row) {
        if (unitDefId <= 0 || unitDefId >= db.equipment.size()) return null;
        PgDatabase.UnitDef def = db.equipment.get(unitDefId);
        if (def.isFortification() || def.unitClass == PgDatabase.CLASS_FORT) return null;
        int totalCost = def.cost;
        if (transportDefId > 0 && transportDefId < db.equipment.size()) {
            totalCost += db.equipment.get(transportDefId).cost / 2;
        }
        if (engine.axisPrestige < totalCost) return null;
        if (engine.getUnitAt(col, row, def.isAir()) != null) return null;

        List<int[]> validSpots = getAvailableDeployHexes(unitDefId);
        boolean validSpot = false;
        for (int[] s : validSpots) {
            if (s[0] == col && s[1] == row) {
                validSpot = true;
                break;
            }
        }
        if (!validSpot) return null;
        if (isHexBlockedByEnemyNearBase(col, row, true)) return null;

        engine.axisPrestige -= totalCost;
        int curId = engine.allocateInstanceId();
        boolean onWater = engine.scenario.grid[col][row].terrainType == 2 || engine.scenario.grid[col][row].isWater();
        PgDatabase.ActiveUnit u = PgDatabase.ActiveUnit.builder()
                .instanceId(curId)
                .unitDefId(unitDefId)
                .transportDefId(transportDefId)
                .nation(engine.scenario.axisNations[0] > 0 ? engine.scenario.axisNations[0] : 8)
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
                .inSeaTransport(!def.isAir() && !def.isNaval() && onWater)
                .facingLeft(false)
                .customTitle(curId + ". " + def.name)
                .build();
        if (u != null) {
            engine.units.add(u);
            if (engine.isCampaignMode) engine.coreUnits.add(u);
        }
        engine.lastUndoState = null;
        engine.recalculateFogOfWar();
        return u;
    }

    public List<int[]> getAvailableAiDeployHexes() {
        return getAvailableAiDeployHexes(false);
    }

    public List<int[]> getAvailableAiDeployHexes(boolean isAir) {
        List<int[]> res = new ArrayList<>();
        if (engine.scenario == null) return res;
        boolean[][] added = new boolean[engine.scenario.cols][engine.scenario.rows];

        // Any controlled objective, city, airport, or supply point
        for (int c = 0; c < engine.scenario.cols; c++) {
            for (int r = 0; r < engine.scenario.rows; r++) {
                PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                boolean isAiOwned = cell.ownerNation > 0 && !db.isAxisNation(cell.ownerNation, engine.scenario);
                boolean isBase = isAir ? (cell.terrainType == 6 || cell.isObjective || cell.isSupplyPoint())
                        : (cell.terrainType == 8 || cell.isObjective || cell.isSupplyPoint() || cell.isPort());
                if (isAiOwned && isBase) {
                    // If an enemy unit is next to a city or supply area or airport,
                    // a new unit cannot be placed at that hex or any surrounding hex.
                    if (hasEnemyAdjacentOrOnHex(c, r, false)) {
                        continue;
                    }

                    // Check if base hex itself is a valid spawn hex
                    if (isHexValidForAiSpawn(c, r, isAir) && !isHexBlockedByEnemyNearBase(c, r, false)) {
                        if (!added[c][r]) {
                            added[c][r] = true;
                            res.add(new int[]{c, r});
                        }
                    }
                    // Also allow adjacent hexes around controlled objectives/supply points
                    for (int[] nb : engine.getNeighbors(c, r)) {
                        int nc = nb[0], nr = nb[1];
                        if (nc >= 0 && nc < engine.scenario.cols && nr >= 0 && nr < engine.scenario.rows) {
                            PgDatabase.HexCell nbCell = engine.scenario.grid[nc][nr];
                            boolean nbAiOwned = nbCell.ownerNation == 0 || !db.isAxisNation(nbCell.ownerNation, engine.scenario);
                            if (nbAiOwned && isHexValidForAiSpawn(nc, nr, isAir) && !isHexBlockedByEnemyNearBase(nc, nr, false)) {
                                if (!added[nc][nr]) {
                                    added[nc][nr] = true;
                                    res.add(new int[]{nc, nr});
                                }
                            }
                        }
                    }
                }
            }
        }
        return res;
    }

    public boolean isHexValidForAiSpawn(int col, int row, boolean isAir) {
        if (engine.scenario == null || col < 0 || col >= engine.scenario.cols || row < 0 || row >= engine.scenario.rows) return false;
        PgDatabase.HexCell cell = engine.scenario.grid[col][row];
        if (cell.ownerNation > 0 && db.isAxisNation(cell.ownerNation, engine.scenario)) return false;

        // If an enemy unit is next to a city or supply area or airport,
        // a new unit cannot be placed at that hex or any surrounding hex.
        if (isHexBlockedByEnemyNearBase(col, row, false)) return false;

        // No enemy (Axis) unit may occupy this hex on either layer
        PgDatabase.ActiveUnit gOn = engine.getUnitAt(col, row, false);
        if (gOn != null && gOn.isAxis) return false;
        PgDatabase.ActiveUnit aOn = engine.getUnitAt(col, row, true);
        if (aOn != null && aOn.isAxis) return false;

        // Target layer must be free
        if (engine.getUnitAt(col, row, isAir) != null) return false;

        // Terrain validity
        if (!isAir) {
            // Ground units cannot spawn on ocean/water
            if (cell.terrainType == 2 || cell.isWater()) return false;
        } else {
            // Air units can spawn on airfields or objectives/supply points
            if (cell.terrainType != 6 && !cell.isObjective && !cell.isSupplyPoint()) return false;
        }
        return true;
    }

    public PgDatabase.ActiveUnit purchaseAiUnit(int unitDefId, int col, int row) {
        if (unitDefId <= 0 || unitDefId >= db.equipment.size()) return null;
        PgDatabase.UnitDef def = db.equipment.get(unitDefId);
        if (def.isFortification() || def.unitClass == PgDatabase.CLASS_FORT) return null;
        if (engine.alliedPrestige < def.cost) return null;
        if (isHexBlockedByEnemyNearBase(col, row, false)) return null;
        if (engine.getUnitAt(col, row, def.isAir()) != null) return null;
        PgDatabase.ActiveUnit otherLayer = engine.getUnitAt(col, row, !def.isAir());
        if (otherLayer != null && otherLayer.isAxis) return null;

        engine.alliedPrestige -= def.cost;
        int curId = engine.allocateInstanceId();
        int nat = (engine.scenario.alliedNations != null && engine.scenario.alliedNations.length > 0 && engine.scenario.alliedNations[0] > 0)
                ? engine.scenario.alliedNations[0] : 16;
        PgDatabase.HexCell cell = engine.scenario.grid[col][row];
        int baseEnt = (cell.terrainType == 8 ? 2 : (cell.isObjective ? 1 : 0));
        PgDatabase.ActiveUnit u = PgDatabase.ActiveUnit.builder()
                .instanceId(curId)
                .unitDefId(unitDefId)
                .nation(nat)
                .isAxis(false)
                .isCore(false)
                .col(col)
                .row(row)
                .strength(10)
                .entrenchment(baseEnt)
                .experience(0)
                .fuel(def.maxFuel > 0 ? def.maxFuel : 99)
                .ammo(def.maxAmmo > 0 ? def.maxAmmo : 99)
                .movePointsUsed(0)
                .moved(false)
                .attacked(false)
                .facingLeft(true)
                .customTitle(curId + ". " + def.name)
                .aiOrder((cell.isObjective || cell.isSupplyPoint()) && !def.isAir() ? 1 : 0)
                .build();
        if (u != null) {
            engine.units.add(u);
        }
        return u;
    }
}
