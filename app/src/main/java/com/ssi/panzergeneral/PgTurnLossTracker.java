package com.ssi.panzergeneral;

import java.io.Serializable;
import java.util.*;

/**
 * Tracks and compiles unit casualties and combat events during the enemy AI turn
 * so that a comprehensive After-Action Loss Summary can be presented at the start
 * of the user's turn.
 */
public class PgTurnLossTracker implements Serializable {

    public static class UnitLossRecord implements Serializable {
        public int instanceId;
        public int unitDefId;
        public String unitName;
        public int unitClass;
        public int iconId;
        public boolean isCore;
        public boolean isPlayer;
        public int col, row;
        public int startStrength;
        public int currentStrength;
        public int strengthLost;
        public int maxOverstrength;
        public int stars;
        public boolean destroyed;
        public boolean surrendered;
        public boolean retreated;
        public boolean fuelCrash;
        public boolean ruggedDefenseTriggered;
        public List<String> attackCauses = new ArrayList<>();
    }

    public static class CombatLogEntry implements Serializable {
        public int attackerInstanceId;
        public int defenderInstanceId;
        public String attackerName;
        public String defenderName;
        public boolean isAttackerPlayer;
        public int targetCol, targetRow;
        public int attackerLosses;
        public int defenderLosses;
        public boolean defenderDestroyed;
        public boolean attackerDestroyed;
        public boolean defenderRetreated;
        public boolean defenderSurrendered;
        public boolean ruggedDefense;
        public boolean defensiveSupportFired;
        public String supportUnitName;
    }

    public static class TurnSummary implements Serializable {
        public int turnNumber;
        public int maxTurns;
        public String playerSideName = "AXIS";
        public String enemySideName = "ALLIED";

        public int totalPlayerUnitsLost = 0;
        public int totalPlayerStrengthLost = 0;
        public int totalEnemyUnitsLost = 0;
        public int totalEnemyStrengthLost = 0;

        public final List<UnitLossRecord> playerDestroyed = new ArrayList<>();
        public final List<UnitLossRecord> playerDamaged = new ArrayList<>();
        public final List<UnitLossRecord> enemyDestroyed = new ArrayList<>();
        public final List<UnitLossRecord> enemyDamaged = new ArrayList<>();
        public final List<CombatLogEntry> combatLog = new ArrayList<>();

        public boolean hasCasualties() {
            return totalPlayerUnitsLost > 0 || totalPlayerStrengthLost > 0 || totalEnemyUnitsLost > 0 || totalEnemyStrengthLost > 0;
        }

        public boolean hasPlayerLosses() {
            return totalPlayerUnitsLost > 0 || totalPlayerStrengthLost > 0;
        }

        public boolean hasActivity() {
            return hasCasualties() || !combatLog.isEmpty();
        }
    }

    private static class UnitSnapshot {
        int instanceId;
        int unitDefId;
        String name;
        int unitClass;
        int iconId;
        boolean isCore;
        boolean isPlayer;
        int strength;
        int maxOverstrength;
        int stars;
        int col, row;
    }

    private final Map<Integer, UnitSnapshot> preTurnUnits = new HashMap<>();
    private final List<CombatLogEntry> turnCombatEvents = new ArrayList<>();
    private int currentTurnNumber = 1;

    public void beginEnemyTurn(PgGameEngine engine, PgDatabase db) {
        preTurnUnits.clear();
        turnCombatEvents.clear();
        if (engine == null || engine.scenario == null || db == null) return;
        currentTurnNumber = engine.turn;

        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u == null || u.strength <= 0) continue;
            UnitSnapshot snap = new UnitSnapshot();
            snap.instanceId = u.instanceId;
            snap.unitDefId = u.unitDefId;
            snap.isCore = u.isCore;
            snap.isPlayer = engine.isPlayerUnit(u);
            snap.strength = u.strength;
            snap.maxOverstrength = u.getMaxOverstrength();
            snap.stars = u.getStars();
            snap.col = u.col;
            snap.row = u.row;

            PgDatabase.UnitDef def = (u.unitDefId > 0 && u.unitDefId < db.equipment.size()) ? db.equipment.get(u.unitDefId) : null;
            snap.name = def != null ? def.name : "Unit";
            snap.unitClass = def != null ? def.unitClass : 0;
            snap.iconId = engine.getEffectiveIconId(u);

            preTurnUnits.put(u.instanceId, snap);
        }
    }

    public void recordCombat(PgGameEngine.CombatReport report, PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender, PgGameEngine engine, PgDatabase db) {
        if (report == null) return;
        CombatLogEntry entry = new CombatLogEntry();
        entry.attackerInstanceId = attacker != null ? attacker.instanceId : -1;
        entry.defenderInstanceId = defender != null ? defender.instanceId : -1;
        entry.attackerName = report.attackerName != null ? report.attackerName : (attacker != null ? db.equipment.get(attacker.unitDefId).name : "Attacker");
        entry.defenderName = report.defenderName != null ? report.defenderName : (defender != null ? db.equipment.get(defender.unitDefId).name : "Defender");
        entry.isAttackerPlayer = attacker != null && engine.isPlayerUnit(attacker);
        entry.targetCol = report.targetCol;
        entry.targetRow = report.targetRow;
        entry.attackerLosses = report.attackerLosses;
        entry.defenderLosses = report.defenderLosses;
        entry.defenderDestroyed = report.defenderDestroyed;
        entry.attackerDestroyed = report.attackerDestroyed;
        entry.defenderRetreated = report.defenderRetreated;
        entry.defenderSurrendered = report.defenderSurrendered;
        entry.ruggedDefense = report.ruggedDefense;

        if (report.supportReport != null && report.supportReport.supportUnit != null) {
            entry.defensiveSupportFired = true;
            PgDatabase.UnitDef sDef = db.equipment.get(report.supportReport.supportUnit.unitDefId);
            entry.supportUnitName = sDef != null ? sDef.name : "Support Artillery";
        }

        turnCombatEvents.add(entry);
    }

    public TurnSummary endEnemyTurnAndCompile(PgGameEngine engine, PgDatabase db, List<PgDatabase.ActiveUnit> crashedAirUnits) {
        TurnSummary summary = new TurnSummary();
        if (engine == null || db == null) return summary;

        summary.turnNumber = engine.turn;
        summary.maxTurns = engine.maxTurns;
        summary.playerSideName = engine.getPlayerSideName().toUpperCase(Locale.ROOT);
        summary.enemySideName = engine.getEnemySideName().toUpperCase(Locale.ROOT);

        summary.combatLog.addAll(turnCombatEvents);

        Map<Integer, PgDatabase.ActiveUnit> currentUnitsMap = new HashMap<>();
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u != null) {
                currentUnitsMap.put(u.instanceId, u);
            }
        }

        // 1. Process all units from snapshot to determine losses and damage
        for (UnitSnapshot snap : preTurnUnits.values()) {
            PgDatabase.ActiveUnit current = currentUnitsMap.get(snap.instanceId);
            boolean nowAlive = current != null && current.strength > 0;

            UnitLossRecord rec = new UnitLossRecord();
            rec.instanceId = snap.instanceId;
            rec.unitDefId = snap.unitDefId;
            rec.unitName = snap.name;
            rec.unitClass = snap.unitClass;
            rec.iconId = snap.iconId;
            rec.isCore = snap.isCore;
            rec.isPlayer = snap.isPlayer;
            rec.col = snap.col;
            rec.row = snap.row;
            rec.startStrength = snap.strength;
            rec.maxOverstrength = snap.maxOverstrength;
            rec.stars = snap.stars;

            // Find matching combat events for this unit
            for (CombatLogEntry ev : turnCombatEvents) {
                if (ev.defenderInstanceId == snap.instanceId) {
                    rec.attackCauses.add(String.format(Locale.ROOT, "Attacked by %s (-%d str)", ev.attackerName, ev.defenderLosses));
                    if (ev.defenderSurrendered) rec.surrendered = true;
                    if (ev.defenderRetreated) rec.retreated = true;
                    if (ev.ruggedDefense) rec.ruggedDefenseTriggered = true;
                } else if (ev.attackerInstanceId == snap.instanceId) {
                    if (ev.attackerLosses > 0) {
                        rec.attackCauses.add(String.format(Locale.ROOT, "Counter-fire vs %s (-%d str)", ev.defenderName, ev.attackerLosses));
                    }
                    if (ev.defensiveSupportFired && ev.supportUnitName != null) {
                        rec.attackCauses.add(String.format(Locale.ROOT, "Hit by support artillery (%s)", ev.supportUnitName));
                    }
                    if (ev.ruggedDefense) rec.ruggedDefenseTriggered = true;
                }
            }

            if (!nowAlive) {
                // Unit was DESTROYED
                rec.currentStrength = 0;
                rec.strengthLost = snap.strength;
                rec.destroyed = true;

                // Check if air fuel crash
                if (crashedAirUnits != null) {
                    for (PgDatabase.ActiveUnit cr : crashedAirUnits) {
                        if (cr.instanceId == snap.instanceId) {
                            rec.fuelCrash = true;
                            rec.attackCauses.add("Fuel exhausted away from airfield/carrier");
                            break;
                        }
                    }
                }

                if (snap.isPlayer) {
                    summary.totalPlayerUnitsLost++;
                    summary.totalPlayerStrengthLost += snap.strength;
                    summary.playerDestroyed.add(rec);
                } else {
                    summary.totalEnemyUnitsLost++;
                    summary.totalEnemyStrengthLost += snap.strength;
                    summary.enemyDestroyed.add(rec);
                }
            } else {
                // Unit survived; check if damaged or engaged
                int lost = snap.strength - current.strength;
                boolean wasEngaged = !rec.attackCauses.isEmpty() || rec.retreated;
                if (lost > 0 || wasEngaged) {
                    rec.currentStrength = current.strength;
                    rec.strengthLost = Math.max(0, lost);
                    rec.col = current.col;
                    rec.row = current.row;

                    if (snap.isPlayer) {
                        summary.totalPlayerStrengthLost += Math.max(0, lost);
                        summary.playerDamaged.add(rec);
                    } else {
                        summary.totalEnemyStrengthLost += Math.max(0, lost);
                        summary.enemyDamaged.add(rec);
                    }
                }
            }
        }

        // Sort destroyed and damaged units: Core units first, then by strength lost descending
        Comparator<UnitLossRecord> comp = (a, b) -> {
            if (a.isCore != b.isCore) return a.isCore ? -1 : 1;
            return Integer.compare(b.strengthLost, a.strengthLost);
        };
        summary.playerDestroyed.sort(comp);
        summary.playerDamaged.sort(comp);
        summary.enemyDestroyed.sort(comp);
        summary.enemyDamaged.sort(comp);

        return summary;
    }
}
