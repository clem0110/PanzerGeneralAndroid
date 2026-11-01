package com.ssi.panzergeneral;

import java.util.List;
import java.util.Map;

/**
 * Manages unit logistics: action availability, resupply, regular & elite
 * replacements / overstrength, equipment upgrades, and disbanding.
 */
public class PgLogisticsManager {
    private final PgGameEngine engine;
    private final PgDatabase db;

    public PgLogisticsManager(PgGameEngine engine) {
        this.engine = engine;
        this.db = engine.db;
    }

    public boolean hasAvailableAction(PgDatabase.ActiveUnit u) {
        if (u == null || !u.isAxis || u.strength <= 0 || !engine.isPlayerTurn) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (def == null) return false;

        // 1. Can the unit attack any valid target?
        if (!u.attacked) {
            List<PgDatabase.ActiveUnit> targets = engine.getValidTargets(u);
            if (!targets.isEmpty()) return true;
        }

        // Fortifications cannot move or take transport actions; if they cannot attack, they have no actions
        if (def.isFortification()) {
            return false;
        }

        // 2. Can the unit move to any reachable hex?
        if (!u.moved && engine.getRemainingMovementPoints(u) > 0) {
            Map<Integer, Integer> reach = engine.getReachableHexes(u);
            if (reach.size() > 1) {
                return true;
            }
        }

        // 3. Can the unit mount/dismount/embark/disembark?
        if (engine.canUseTransportAction(u)) {
            return true;
        }

        // If the unit has already moved or attacked, it cannot perform non-combat actions like supply, reinforce, or upgrade
        if (u.moved || u.movePointsUsed > 0 || u.attacked) {
            return false;
        }

        // 4. Can the unit take replacements (regular or elite)?
        if (canReinforceUnit(u, false) || canReinforceUnit(u, true)) {
            return true;
        }

        // 5. Can the unit upgrade?
        if (canUpgradeUnit(u)) {
            return true;
        }

        // 6. Can the unit resupply ammo or fuel?
        if (canResupplyUnit(u)) {
            return true;
        }

        return false;
    }

    public boolean canResupplyUnit(PgDatabase.ActiveUnit u) {
        if (!engine.supplyEnabled || engine.isDeploymentPhase || engine.scenario == null) return false;
        if (u == null || !u.isAxis || !engine.isPlayerTurn || u.strength <= 0) return false;
        if (u.moved || u.movePointsUsed > 0 || u.attacked) return false;
        if (u.col < 0 || u.col >= engine.scenario.cols || u.row < 0 || u.row >= engine.scenario.rows) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (def == null || def.isFortification()) return false;
        boolean needsAmmo = def.maxAmmo > 0 && u.ammo < def.maxAmmo;
        boolean needsFuel = def.maxFuel > 0 && u.fuel < def.maxFuel;
        return needsAmmo || needsFuel;
    }

    public boolean resupplyUnit(PgDatabase.ActiveUnit u) {
        if (!canResupplyUnit(u)) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        int maxA = def.maxAmmo > 0 ? def.maxAmmo : 99;
        int maxF = def.maxFuel > 0 ? def.maxFuel : 99;

        // Panzer General Supply Rule:
        // Friendly Supply Point = 100%, Open terrain = 75%, In Enemy ZOC = 25%
        PgDatabase.HexCell cell = engine.scenario.grid[u.col][u.row];
        boolean inFriendlySupplyHex = cell.isSupplyPoint() && db.isAxisNation(cell.ownerNation, engine.scenario) == u.isAxis;
        boolean inZoc = engine.isInEnemyZoc(u.col, u.row, u);
        int factor = inZoc ? 25 : (inFriendlySupplyHex ? 100 : 75);

        int addAmmo = Math.max(1, ((maxA - u.ammo) * factor) / 100);
        int addFuel = Math.max(1, ((maxF - u.fuel) * factor) / 100);
        u.ammo = Math.min(maxA, u.ammo + addAmmo);
        u.fuel = Math.min(maxF, u.fuel + addFuel);
        u.moved = true;
        u.attacked = true;
        engine.lastUndoState = null;
        return true;
    }

    public boolean canReinforceUnit(PgDatabase.ActiveUnit u, boolean elite) {
        if (u == null || !u.isAxis || !engine.isPlayerTurn || u.strength <= 0) {
            return false;
        }
        if (!engine.isDeploymentPhase && (u.moved || u.movePointsUsed > 0 || u.attacked)) {
            return false;
        }
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (def == null) return false;

        int costPerPoint = (u.strength >= 10)
                ? Math.max(20, def.cost / 5)
                : Math.max(8, def.cost / (elite ? 10 : 20));
        if (engine.axisPrestige < costPerPoint) {
            return false;
        }

        if (u.col < 0 || u.row < 0) {
            if (engine.isDeploymentPhase) {
                if (!elite) return u.strength < 10;
                return u.strength < u.getMaxOverstrength();
            }
            return false;
        }
        if (!elite) {
            return u.strength < 10;
        } else {
            // Elite Replacements allow Overstrength up to (10 + stars) when not in enemy ZOC!
            int maxAllowed = engine.isInEnemyZoc(u.col, u.row, u) ? 10 : u.getMaxOverstrength();
            return u.strength < maxAllowed;
        }
    }

    public boolean reinforceUnit(PgDatabase.ActiveUnit u, boolean elite) {
        if (!canReinforceUnit(u, elite)) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        boolean inZoc = (u.col >= 0 && u.row >= 0) && engine.isInEnemyZoc(u.col, u.row, u);

        int needed;
        int costPerPoint;
        if (u.strength >= 10) {
            // Overstrength rule: increments strength exactly 1 point at a time (e.g. 10 -> 11, then 11 -> 12)
            needed = 1;
            costPerPoint = Math.max(20, def.cost / 5);
        } else {
            // Normal / Elite replacements replenish damaged units up to 10
            int maxTarget = 10;
            needed = maxTarget - u.strength;
            if (inZoc) {
                // Enemy ZOC restricts replacements to half
                needed = Math.max(1, needed / 2);
            }
            costPerPoint = Math.max(8, def.cost / (elite ? 10 : 20));
        }

        int affordable = Math.min(needed, engine.axisPrestige / costPerPoint);
        if (affordable <= 0) return false;

        engine.axisPrestige -= affordable * costPerPoint;
        if (!elite) {
            u.experience = (u.experience * u.strength) / (u.strength + affordable);
        }
        u.strength += affordable;
        if (u.strength <= 10) {
            u.ammo = def.maxAmmo > 0 ? def.maxAmmo : 99;
            u.fuel = def.maxFuel > 0 ? def.maxFuel : 99;
        }
        if (!engine.isDeploymentPhase) {
            u.moved = true;
            u.attacked = true;
        }
        engine.lastUndoState = null;
        return true;
    }

    // Panzer General Manual: Ground units upgrade only on a friendly controlled City (Air units on a friendly controlled Airfield) with no adjacent enemies
    public boolean canUpgradeUnit(PgDatabase.ActiveUnit u) {
        if (u == null || !u.isAxis || !engine.isPlayerTurn || u.strength <= 0 || engine.scenario == null) {
            return false;
        }
        if (!engine.isDeploymentPhase && (u.moved || u.movePointsUsed > 0 || u.attacked)) {
            return false;
        }
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        if (def == null || def.isFortification() || def.isNaval()) return false;
        if (u.inAirTransport || u.inSeaTransport) return false;
        boolean supportsTransport = (def.unitClass == PgDatabase.CLASS_INFANTRY
                || def.unitClass == PgDatabase.CLASS_ARTILLERY
                || def.unitClass == PgDatabase.CLASS_ANTI_TANK
                || def.unitClass == PgDatabase.CLASS_ANTI_AIR
                || def.unitClass == PgDatabase.CLASS_AIR_DEFENSE);
        int minPrestige = supportsTransport ? 10 : 20;
        if (engine.axisPrestige < minPrestige) return false;
        if (u.col < 0 || u.row < 0) {
            return engine.isDeploymentPhase;
        }
        if (u.col >= engine.scenario.cols || u.row >= engine.scenario.rows) return false;
        if (engine.deploymentManager.hasEnemyAdjacentOrOnHex(u.col, u.row, u.isAxis)) return false;
        PgDatabase.HexCell cell = engine.scenario.grid[u.col][u.row];
        if (def.isAir()) {
            return cell.terrainType == 6 && db.isAxisNation(cell.ownerNation, engine.scenario);
        }
        return cell.terrainType == 8 && db.isAxisNation(cell.ownerNation, engine.scenario);
    }

    public int getUpgradeCost(PgDatabase.ActiveUnit u, int newUnitDefId, int newTransportDefId) {
        if (u == null) return 0;
        PgDatabase.UnitDef oldDef = db.equipment.get(u.unitDefId);
        PgDatabase.UnitDef newDef = (newUnitDefId > 0 && newUnitDefId < db.equipment.size())
                ? db.equipment.get(newUnitDefId) : oldDef;
        int upgradeCost = 0;
        if (newUnitDefId != u.unitDefId) {
            upgradeCost = Math.max(20, newDef.cost - (oldDef.cost / 2));
        }
        if (newTransportDefId > 0 && newTransportDefId != u.transportDefId && newTransportDefId < db.equipment.size()) {
            upgradeCost += Math.max(10, db.equipment.get(newTransportDefId).cost / 2);
        }
        return upgradeCost;
    }

    public boolean upgradeUnit(PgDatabase.ActiveUnit u, int newUnitDefId, int newTransportDefId) {
        if (!canUpgradeUnit(u)) return false;
        if (newUnitDefId <= 0 || newUnitDefId >= db.equipment.size()) return false;
        if (newUnitDefId == u.unitDefId && newTransportDefId == u.transportDefId) {
            return true;
        }
        PgDatabase.UnitDef newDef = db.equipment.get(newUnitDefId);
        int upgradeCost = getUpgradeCost(u, newUnitDefId, newTransportDefId);
        if (engine.axisPrestige < upgradeCost) return false;

        engine.axisPrestige -= upgradeCost;
        u.unitDefId = newUnitDefId;
        u.transportDefId = newTransportDefId;
        u.strength = Math.min(u.getMaxOverstrength(), u.strength);
        u.fuel = newDef.maxFuel > 0 ? newDef.maxFuel : 99;
        u.ammo = newDef.maxAmmo > 0 ? newDef.maxAmmo : 99;
        if (!engine.isDeploymentPhase) {
            u.moved = true;
            u.attacked = true;
        }
        engine.lastUndoState = null;
        if (u.col >= 0 && u.row >= 0) {
            engine.recalculateFogOfWar();
        }
        return true;
    }

    // Panzer General Manual: Disband unmoved/unattacked Unit to recover Prestige
    public boolean canDisbandUnit(PgDatabase.ActiveUnit u) {
        if (u == null || !u.isAxis || !engine.isPlayerTurn || u.strength <= 0) return false;
        if (!engine.isDeploymentPhase && (u.moved || u.movePointsUsed > 0 || u.attacked)) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        return def != null && !def.isFortification();
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
        engine.axisPrestige += refund;
        engine.units.remove(u);
        engine.unplacedCoreUnits.remove(u);
        engine.coreUnits.remove(u);
        engine.lastUndoState = null;
        engine.recalculateFogOfWar();
        return refund;
    }
}
