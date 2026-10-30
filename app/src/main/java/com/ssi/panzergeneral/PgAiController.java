package com.ssi.panzergeneral;

import java.util.*;

/**
 * Multi-phase tactical and strategic AI controller for Allied forces:
 * target scoring, artillery & air operations, objective garrisoning,
 * mobile ground assaults, tactical fallback, replacements, and smart unit purchasing.
 */
public class PgAiController {
    private final PgGameEngine engine;
    private final PgDatabase db;

    public PgAiController(PgGameEngine engine) {
        this.engine = engine;
        this.db = engine.db;
    }

    public double evaluateAiDeploymentEffectiveness(PgDatabase.UnitDef d, int col, int row,
            int infCount, int artyCount, int tankCount, int atCount, int aaCount, int airCount) {
        if (d == null || engine.scenario == null) return -999999.0;
        PgDatabase.HexCell cell = engine.scenario.grid[col][row];

        double score = 0.0;

        // 1. Base strategic importance of the hex
        if (cell.isObjective) {
            score += 150.0; // Objectives are primary victory condition!
        } else if (cell.isSupplyPoint()) {
            score += 75.0; // Supply points (cities, airfields, ports)
        } else {
            score += 35.0; // Adjacent to objective or supply point
        }

        // 2. Assess threat level from Axis units
        int minAxisGroundDist = 999;
        int minAxisDist = 999;
        int nearbyAxisTanks = 0;
        int nearbyAxisInf = 0;
        int nearbyAxisAir = 0;

        for (PgDatabase.ActiveUnit ax : engine.units) {
            if (ax != null && ax.isAxis && ax.strength > 0) {
                int dist = PgGameEngine.hexDistance(col, row, ax.col, ax.row);
                if (dist < minAxisDist) minAxisDist = dist;
                PgDatabase.UnitDef axDef = db.equipment.get(ax.unitDefId);
                if (!axDef.isAir() && dist < minAxisGroundDist) {
                    minAxisGroundDist = dist;
                }
                if (dist <= 4) {
                    if (axDef.isAir()) nearbyAxisAir++;
                    else if (axDef.unitClass == PgDatabase.CLASS_TANK) nearbyAxisTanks++;
                    else if (axDef.unitClass == PgDatabase.CLASS_INFANTRY) nearbyAxisInf++;
                }
            }
        }

        // Threat urgency: Higher score for hexes that are actively threatened by enemy
        if (minAxisGroundDist == 1) {
            score += 120.0; // Enemy adjacent! Emergency defense!
        } else if (minAxisGroundDist == 2) {
            score += 100.0; // Enemy 2 hexes away, will reach next turn!
        } else if (minAxisGroundDist == 3) {
            score += 75.0;
        } else if (minAxisGroundDist == 4) {
            score += 50.0;
        } else if (minAxisGroundDist <= 6) {
            score += 25.0;
        } else if (minAxisDist > 12) {
            score -= 60.0; // Too remote to influence current battle
        }

        // 3. Current occupancy of the hex
        PgDatabase.ActiveUnit existingGround = engine.getUnitAt(col, row, false);

        // If objective is completely ungarrisoned, emergency priority to put a defender on it!
        if (cell.isObjective && existingGround == null && !d.isAir()) {
            score += 100.0;
        } else if (cell.isSupplyPoint() && existingGround == null && !d.isAir()) {
            score += 40.0;
        }

        // 4. Unit class synergy with the location and battlefield role
        switch (d.unitClass) {
            case PgDatabase.CLASS_INFANTRY:
                if (cell.terrainType == 8 || cell.isObjective) {
                    score += 80.0; // Infantry gets massive close defense in cities/objectives
                }
                if (minAxisGroundDist <= 3) {
                    score += 50.0;
                }
                if (infCount < 4) {
                    score += 40.0;
                }
                break;

            case PgDatabase.CLASS_ARTILLERY:
                boolean adjacentToFriendlyDefender = false;
                for (int[] nb : engine.getNeighbors(col, row)) {
                    if (nb[0] >= 0 && nb[0] < engine.scenario.cols && nb[1] >= 0 && nb[1] < engine.scenario.rows) {
                        PgDatabase.ActiveUnit nbU = engine.getUnitAt(nb[0], nb[1], false);
                        if (nbU != null && !nbU.isAxis) {
                            adjacentToFriendlyDefender = true;
                            if (engine.scenario.grid[nb[0]][nb[1]].isObjective) {
                                score += 70.0; // Supporting an objective garrison!
                            }
                        }
                    }
                }
                if (adjacentToFriendlyDefender) {
                    score += 50.0;
                }
                if (minAxisGroundDist == 1 && !adjacentToFriendlyDefender) {
                    score -= 40.0; // Don't expose isolated artillery on the frontline
                }
                if (artyCount < 3) {
                    score += 35.0;
                }
                break;

            case PgDatabase.CLASS_TANK:
                if (minAxisGroundDist >= 2 && minAxisGroundDist <= 5) {
                    score += 60.0; // Ideal counter-attack distance
                }
                if (nearbyAxisTanks > 0) {
                    score += 40.0;
                }
                if (cell.terrainType == 0 || cell.terrainType == 5) {
                    score += 20.0;
                }
                if (tankCount < 3) {
                    score += 30.0;
                }
                break;

            case PgDatabase.CLASS_ANTI_TANK:
                if (nearbyAxisTanks > 0) {
                    score += 90.0; // Critical counter-armor deployment
                } else if (minAxisGroundDist <= 4) {
                    score += 40.0;
                }
                if (atCount < 2) {
                    score += 30.0;
                }
                break;

            case PgDatabase.CLASS_AIR_DEFENSE:
            case PgDatabase.CLASS_ANTI_AIR:
                if (nearbyAxisAir > 0) {
                    score += 90.0;
                }
                if (cell.isObjective || cell.isSupplyPoint()) {
                    score += 40.0;
                }
                break;

            case PgDatabase.CLASS_FIGHTER:
                if (nearbyAxisAir > 0) {
                    score += 80.0;
                }
                if (airCount < 2) {
                    score += 40.0;
                }
                break;

            case PgDatabase.CLASS_TAC_BOMBER:
            case PgDatabase.CLASS_LEVEL_BOMBER:
                if (minAxisGroundDist >= 2) {
                    score += 40.0;
                }
                break;
        }

        score += d.cost * 0.1;

        return score;
    }

    public boolean takeAiReplacements(PgDatabase.ActiveUnit u) {
        if (u == null || u.strength >= 10 || engine.alliedPrestige < 20) return false;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        int needed = 10 - u.strength;
        int costPerPoint = Math.max(5, def.cost / 15);
        int cost = needed * costPerPoint;
        if (engine.alliedPrestige < cost) {
            cost = engine.alliedPrestige;
            needed = Math.max(1, cost / costPerPoint);
        }
        engine.alliedPrestige -= cost;
        u.strength += needed;
        u.moved = true;
        u.attacked = true;
        u.movePointsUsed = engine.getMaxMovementPoints(u);
        return true;
    }

    public double evaluateAiAttackScore(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit target) {
        if (attacker == null || target == null || attacker.strength <= 0 || target.strength <= 0) return -999.0;
        PgGameEngine.CombatPrediction p = engine.predictCombat(attacker, target);

        // Base score: weighted exchange ratio
        double score = (p.expectedDefenderLosses * 3.0) - (p.expectedAttackerLosses * 2.0);

        PgDatabase.UnitDef aDef = engine.getEffectiveUnitDef(attacker);
        PgDatabase.UnitDef tDef = engine.getEffectiveUnitDef(target);

        // Kill bonus: Completely destroying an enemy unit removes its counterattack and firepower permanently
        if (p.expectedDefenderLosses >= target.strength) {
            score += 25.0;
        } else if (target.strength - p.expectedDefenderLosses <= 2) {
            score += 10.0; // Crippling a unit
        }

        // Suppression and Overrun bonuses
        if (target.suppression > 0) {
            score += Math.min(10.0, target.suppression * 2.5);
        }
        if (aDef.unitClass == PgDatabase.CLASS_TANK && target.strength <= 6) {
            score += 14.0; // High priority for tank overrun breakthrough
        }

        // High-value target priorities
        if (tDef.unitClass == PgDatabase.CLASS_ARTILLERY) {
            score += 12.0; // Suppress / eliminate enemy artillery fire support
        } else if (tDef.unitClass == PgDatabase.CLASS_ANTI_AIR || tDef.unitClass == PgDatabase.CLASS_AIR_DEFENSE) {
            score += 8.0;
        } else if (aDef.unitClass == PgDatabase.CLASS_ANTI_TANK && tDef.unitClass == PgDatabase.CLASS_TANK) {
            score += 8.0;
        } else if (aDef.unitClass == PgDatabase.CLASS_FIGHTER && (tDef.unitClass == PgDatabase.CLASS_LEVEL_BOMBER || tDef.unitClass == PgDatabase.CLASS_TAC_BOMBER)) {
            score += 16.0; // Intercept bombers!
        }

        // Clearing enemy off victory objectives
        if (engine.scenario != null && target.col >= 0 && target.col < engine.scenario.cols && target.row >= 0 && target.row < engine.scenario.rows) {
            if (engine.scenario.grid[target.col][target.row].isObjective) {
                score += 10.0;
            }
            // Close terrain ambush: Infantry attacking unentrenched tank in cities/woods
            if (tDef.unitClass == PgDatabase.CLASS_TANK && engine.scenario.grid[target.col][target.row].isCloseDefenseTerrain()) {
                score += 8.0;
            }
        }

        // Penalties
        if (p.ruggedDefenseChance) score -= 6.0;
        if (p.artillerySupport) score -= 5.0;
        if (p.fighterInterceptor) score -= 8.0;

        return score;
    }

    public PgDatabase.ActiveUnit pickBestAiTarget(PgDatabase.ActiveUnit attacker, List<PgDatabase.ActiveUnit> targets) {
        if (targets == null || targets.isEmpty()) return null;
        PgDatabase.ActiveUnit best = null;
        double bestScore = -999.0;
        for (PgDatabase.ActiveUnit t : targets) {
            double sc = evaluateAiAttackScore(attacker, t);
            if (sc > bestScore) {
                bestScore = sc;
                best = t;
            }
        }
        return best;
    }

    public double evaluateHypotheticalAttackScore(PgDatabase.ActiveUnit attacker, int fromCol, int fromRow, PgDatabase.ActiveUnit target) {
        if (attacker == null || target == null || attacker.strength <= 0 || target.strength <= 0) return -999.0;
        int origCol = attacker.col;
        int origRow = attacker.row;
        attacker.col = fromCol;
        attacker.row = fromRow;
        try {
            return evaluateAiAttackScore(attacker, target);
        } finally {
            attacker.col = origCol;
            attacker.row = origRow;
        }
    }

    public List<PgGameEngine.CombatReport> endPlayerTurnAndRunAi() {
        return endPlayerTurnAndRunAi(null);
    }

    public List<PgGameEngine.CombatReport> endPlayerTurnAndRunAi(PgGameEngine.AiTurnCallback callback) {
        List<PgGameEngine.CombatReport> aiReports = new ArrayList<>();
        engine.isPlayerTurn = false;
        engine.lastUndoState = null;

        // Reset AI units & clear suppression
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u != null && !u.isAxis && u.strength > 0) {
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
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u != null && !u.isAxis && u.strength > 0) aiList.add(u);
        }

        // Identify strategic objective locations on the map
        List<PgDatabase.HexCell> threatenedAlliedObjectives = new ArrayList<>();
        List<PgDatabase.HexCell> capturableAxisObjectives = new ArrayList<>();
        if (engine.scenario != null) {
            for (int c = 0; c < engine.scenario.cols; c++) {
                for (int r = 0; r < engine.scenario.rows; r++) {
                    PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                    if (cell.isObjective) {
                        boolean isAxis = db.isAxisNation(cell.ownerNation, engine.scenario);
                        PgDatabase.ActiveUnit occupant = engine.getUnitAt(c, r, false);
                        if (!isAxis) {
                            // Allied objective: check if vacant or enemy within 4 hexes
                            boolean threatened = (occupant == null);
                            if (!threatened) {
                                for (PgDatabase.ActiveUnit au : engine.units) {
                                    if (au != null && au.isAxis && au.strength > 0 && PgGameEngine.hexDistance(c, r, au.col, au.row) <= 4) {
                                        threatened = true;
                                        break;
                                    }
                                }
                            }
                            if (threatened) threatenedAlliedObjectives.add(cell);
                        } else {
                            // Axis objective: check if no Axis ground unit defends it
                            if (occupant == null || !occupant.isAxis) {
                                capturableAxisObjectives.add(cell);
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // PHASE 1: Stationary Ranged Artillery & Naval Bombardment (Softening Targets First)
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !engine.units.contains(ai) || ai.attacked) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (def.range > 1 || def.isNaval()) {
                List<PgDatabase.ActiveUnit> targets = engine.getValidTargets(ai);
                if (!targets.isEmpty()) {
                    PgDatabase.ActiveUnit best = pickBestAiTarget(ai, targets);
                    if (best != null) {
                        PgGameEngine.CombatReport rep = engine.executeCombat(ai, best);
                        aiReports.add(rep);
                        if (callback != null) callback.onAiCombatExecuted(ai, best, rep);
                    }
                }
            }
        }

        // =========================================================================
        // PHASE 2: Tactical Air Operations (Fighters intercept, Bombers suppress/strike)
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !engine.units.contains(ai) || ai.attacked) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (!def.isAir()) continue;

            // Low Fuel Check
            if (engine.supplyEnabled && def.maxFuel > 0 && ai.fuel <= 20 && engine.scenario != null) {
                int bestAc = -1, bestAr = -1, minAirDist = 999;
                for (int c = 0; c < engine.scenario.cols; c++) {
                    for (int r = 0; r < engine.scenario.rows; r++) {
                        if (engine.scenario.grid[c][r].terrainType == 6 && !db.isAxisNation(engine.scenario.grid[c][r].ownerNation, engine.scenario)) {
                            int d = PgGameEngine.hexDistance(ai.col, ai.row, c, r);
                            if (d < minAirDist) {
                                minAirDist = d;
                                bestAc = c;
                                bestAr = r;
                            }
                        }
                    }
                }
                if (bestAc >= 0) {
                    Map<Integer, Integer> reach = engine.getReachableHexes(ai);
                    int moveC = ai.col, moveR = ai.row, closestD = minAirDist;
                    for (int key : reach.keySet()) {
                        int mc = key % engine.scenario.cols, mr = key / engine.scenario.cols;
                        int d = PgGameEngine.hexDistance(mc, mr, bestAc, bestAr);
                        if (d < closestD) {
                            closestD = d;
                            moveC = mc;
                            moveR = mr;
                        }
                    }
                    if (moveC != ai.col || moveR != ai.row) {
                        int origC = ai.col, origR = ai.row;
                        if (engine.moveUnit(ai, moveC, moveR)) {
                            if (callback != null) callback.onAiUnitMoved(ai, origC, origR, moveC, moveR);
                        }
                        continue;
                    }
                }
            }

            // Fighters: Intercept bombers or enemy fighters across entire map
            if (def.unitClass == PgDatabase.CLASS_FIGHTER) {
                List<PgDatabase.ActiveUnit> immediateTargets = engine.getValidTargets(ai);
                if (!immediateTargets.isEmpty()) {
                    PgDatabase.ActiveUnit best = pickBestAiTarget(ai, immediateTargets);
                    if (best != null) {
                        PgGameEngine.CombatReport rep = engine.executeCombat(ai, best);
                        aiReports.add(rep);
                        if (callback != null) callback.onAiCombatExecuted(ai, best, rep);
                        continue;
                    }
                }
                if (!ai.moved && engine.scenario != null) {
                    PgDatabase.ActiveUnit airTarget = null;
                    int bestPriority = -1;
                    int closestDist = 999;
                    for (PgDatabase.ActiveUnit eu : engine.units) {
                        if (eu != null && eu.isAxis && eu.strength > 0 && engine.isUnitOnAirLayer(eu)) {
                            PgDatabase.UnitDef eDef = db.equipment.get(eu.unitDefId);
                            int prio = (eDef.unitClass == PgDatabase.CLASS_LEVEL_BOMBER || eDef.unitClass == PgDatabase.CLASS_TAC_BOMBER) ? 2 : 1;
                            int d = PgGameEngine.hexDistance(ai.col, ai.row, eu.col, eu.row);
                            if (prio > bestPriority || (prio == bestPriority && d < closestDist)) {
                                bestPriority = prio;
                                closestDist = d;
                                airTarget = eu;
                            }
                        }
                    }
                    // If no enemy aircraft, search for nearest Axis ground force to patrol
                    if (airTarget == null) {
                        for (PgDatabase.ActiveUnit eu : engine.units) {
                            if (eu != null && eu.isAxis && eu.strength > 0) {
                                int d = PgGameEngine.hexDistance(ai.col, ai.row, eu.col, eu.row);
                                if (d < closestDist) {
                                    closestDist = d;
                                    airTarget = eu;
                                }
                            }
                        }
                    }

                    if (airTarget != null) {
                        Map<Integer, Integer> reach = engine.getReachableHexes(ai);
                        int bestC = ai.col, bestR = ai.row;
                        double bestHexVal = -999.0;
                        for (int key : reach.keySet()) {
                            int mc = key % engine.scenario.cols, mr = key / engine.scenario.cols;
                            int d = PgGameEngine.hexDistance(mc, mr, airTarget.col, airTarget.row);
                            double hVal = -d * 2.0;
                            if (d <= 1 && engine.isUnitOnAirLayer(airTarget)) {
                                hVal += 30.0;
                            }
                            if (hVal > bestHexVal) {
                                bestHexVal = hVal;
                                bestC = mc;
                                bestR = mr;
                            }
                        }
                        if (bestC != ai.col || bestR != ai.row) {
                            int origC = ai.col, origR = ai.row;
                            if (engine.moveUnit(ai, bestC, bestR)) {
                                if (callback != null) callback.onAiUnitMoved(ai, origC, origR, bestC, bestR);
                                List<PgDatabase.ActiveUnit> postTargets = engine.getValidTargets(ai);
                                if (!postTargets.isEmpty()) {
                                    PgDatabase.ActiveUnit postBest = pickBestAiTarget(ai, postTargets);
                                    if (postBest != null) {
                                        PgGameEngine.CombatReport rep = engine.executeCombat(ai, postBest);
                                        aiReports.add(rep);
                                        if (callback != null) callback.onAiCombatExecuted(ai, postBest, rep);
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (def.unitClass == PgDatabase.CLASS_TAC_BOMBER || def.unitClass == PgDatabase.CLASS_LEVEL_BOMBER) {
                // Bombers: Strike high-value armor, artillery, or entrenched objective defenders
                List<PgDatabase.ActiveUnit> immediateTargets = engine.getValidTargets(ai);
                if (!immediateTargets.isEmpty()) {
                    PgDatabase.ActiveUnit best = pickBestAiTarget(ai, immediateTargets);
                    if (best != null) {
                        PgGameEngine.CombatReport rep = engine.executeCombat(ai, best);
                        aiReports.add(rep);
                        if (callback != null) callback.onAiCombatExecuted(ai, best, rep);
                        continue;
                    }
                }
                if (!ai.moved && engine.scenario != null) {
                    PgDatabase.ActiveUnit groundTarget = null;
                    int bestScore = -999;
                    for (PgDatabase.ActiveUnit eu : engine.units) {
                        if (eu != null && eu.isAxis && eu.strength > 0 && !engine.isUnitOnAirLayer(eu)) {
                            PgDatabase.UnitDef eDef = db.equipment.get(eu.unitDefId);
                            int targetVal = 10;
                            if (eDef.unitClass == PgDatabase.CLASS_TANK) targetVal = 25;
                            else if (eDef.unitClass == PgDatabase.CLASS_ARTILLERY) targetVal = 20;
                            else if (engine.scenario.grid[eu.col][eu.row].isObjective) targetVal = 22;
                            int d = PgGameEngine.hexDistance(ai.col, ai.row, eu.col, eu.row);
                            int composite = targetVal * 10 - d;
                            if (composite > bestScore) {
                                bestScore = composite;
                                groundTarget = eu;
                            }
                        }
                    }
                    if (groundTarget != null) {
                        Map<Integer, Integer> reach = engine.getReachableHexes(ai);
                        int bestC = ai.col, bestR = ai.row;
                        double bestHexVal = -999.0;
                        for (int key : reach.keySet()) {
                            int mc = key % engine.scenario.cols, mr = key / engine.scenario.cols;
                            int d = PgGameEngine.hexDistance(mc, mr, groundTarget.col, groundTarget.row);
                            double hVal = -d * 2.0;
                            if (d <= 1) hVal += 40.0;
                            if (hVal > bestHexVal) {
                                bestHexVal = hVal;
                                bestC = mc;
                                bestR = mr;
                            }
                        }
                        if (bestC != ai.col || bestR != ai.row) {
                            int origC = ai.col, origR = ai.row;
                            if (engine.moveUnit(ai, bestC, bestR)) {
                                if (callback != null) callback.onAiUnitMoved(ai, origC, origR, bestC, bestR);
                                List<PgDatabase.ActiveUnit> postTargets = engine.getValidTargets(ai);
                                if (!postTargets.isEmpty()) {
                                    PgDatabase.ActiveUnit postBest = pickBestAiTarget(ai, postTargets);
                                    if (postBest != null) {
                                        PgGameEngine.CombatReport rep = engine.executeCombat(ai, postBest);
                                        aiReports.add(rep);
                                        if (callback != null) callback.onAiCombatExecuted(ai, postBest, rep);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // PHASE 3: Objective Garrisons & Fortifications (Never abandon objectives!)
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !engine.units.contains(ai) || ai.attacked) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (def.isAir() || def.isNaval() || engine.scenario == null) continue;

            PgDatabase.HexCell cell = engine.scenario.grid[ai.col][ai.row];
            if (cell.isObjective || cell.terrainType == 12 || (cell.isSupplyPoint() && ai.entrenchment >= 2)) {
                List<PgDatabase.ActiveUnit> targets = engine.getValidTargets(ai);
                if (!targets.isEmpty()) {
                    PgDatabase.ActiveUnit best = pickBestAiTarget(ai, targets);
                    if (best != null) {
                        double sc = evaluateAiAttackScore(ai, best);
                        // Only attack from the objective if exchange is favorable or defender is low strength
                        if (sc >= 0 || best.strength <= 3) {
                            PgGameEngine.CombatReport rep = engine.executeCombat(ai, best);
                            aiReports.add(rep);
                            if (callback != null) callback.onAiCombatExecuted(ai, best, rep);
                        }
                    }
                }
                // Mark as acted so this garrison unit NEVER moves or abandons the objective
                ai.attacked = true;
                ai.moved = true;
            }
        }

        // =========================================================================
        // PHASE 4: Artillery & Anti-Aircraft Repositioning (Stay 2-3 hexes back)
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !engine.units.contains(ai) || ai.attacked || ai.moved || engine.scenario == null) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (def.range > 1 || def.unitClass == PgDatabase.CLASS_AIR_DEFENSE || def.unitClass == PgDatabase.CLASS_ANTI_AIR) {
                // Find nearest enemy ground unit across the entire board
                PgDatabase.ActiveUnit nearestEnemy = null;
                int minEnemyDist = 999;
                for (PgDatabase.ActiveUnit eu : engine.units) {
                    if (eu != null && eu.isAxis && eu.strength > 0 && !engine.isUnitOnAirLayer(eu)) {
                        int d = PgGameEngine.hexDistance(ai.col, ai.row, eu.col, eu.row);
                        if (d < minEnemyDist) {
                            minEnemyDist = d;
                            nearestEnemy = eu;
                        }
                    }
                }

                if (nearestEnemy != null) {
                    Map<Integer, Integer> reach = engine.getReachableHexes(ai);
                    int targetOptimalDist = Math.min(def.range > 1 ? def.range : 2, 3);
                    int bestC = ai.col, bestR = ai.row;
                    double bestScore = -999.0;

                    for (int key : reach.keySet()) {
                        int mc = key % engine.scenario.cols, mr = key / engine.scenario.cols;
                        int d = PgGameEngine.hexDistance(mc, mr, nearestEnemy.col, nearestEnemy.row);

                        // Check if any enemy is adjacent to (mc, mr)
                        boolean anyAdj = false;
                        for (int[] nb : engine.getNeighbors(mc, mr)) {
                            PgDatabase.ActiveUnit adj = engine.getUnitAt(nb[0], nb[1], false);
                            if (adj != null && adj.isAxis && adj.strength > 0) {
                                anyAdj = true;
                                break;
                            }
                        }

                        double score = 0.0;
                        if (anyAdj) {
                            score -= 50.0; // Heavy penalty for ending move adjacent to enemy!
                        } else {
                            // Optimal distance for artillery is 2 to 3 hexes away
                            score -= Math.abs(d - targetOptimalDist) * 10.0;
                            if (engine.scenario.grid[mc][mr].isSupplyPoint()) score += 5.0;
                        }

                        if (score > bestScore) {
                            bestScore = score;
                            bestC = mc;
                            bestR = mr;
                        }
                    }

                    if (bestC != ai.col || bestR != ai.row) {
                        int origC = ai.col, origR = ai.row;
                        if (engine.moveUnit(ai, bestC, bestR)) {
                            if (callback != null) callback.onAiUnitMoved(ai, origC, origR, bestC, bestR);
                            List<PgDatabase.ActiveUnit> postTargets = engine.getValidTargets(ai);
                            if (!postTargets.isEmpty()) {
                                PgDatabase.ActiveUnit best = pickBestAiTarget(ai, postTargets);
                                if (best != null) {
                                    PgGameEngine.CombatReport rep = engine.executeCombat(ai, best);
                                    aiReports.add(rep);
                                    if (callback != null) callback.onAiCombatExecuted(ai, best, rep);
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // PHASE 5: Armor & Assault Gun Breakthrough (Tanks & AT)
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !engine.units.contains(ai) || ai.attacked) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (def.isAir() || def.isNaval() || def.range > 1 || engine.scenario == null) continue;
            if (def.unitClass == PgDatabase.CLASS_TANK || def.unitClass == PgDatabase.CLASS_ANTI_TANK) {
                executeAiMobileGroundAssault(ai, aiReports, capturableAxisObjectives, threatenedAlliedObjectives, callback);
            }
        }

        // =========================================================================
        // PHASE 6: Infantry & Recon Maneuver & Close Assault
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || !engine.units.contains(ai) || ai.attacked) continue;
            PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
            if (def.isAir() || def.isNaval() || def.range > 1 || engine.scenario == null) continue;
            if (def.unitClass != PgDatabase.CLASS_TANK && def.unitClass != PgDatabase.CLASS_ANTI_TANK) {
                executeAiMobileGroundAssault(ai, aiReports, capturableAxisObjectives, threatenedAlliedObjectives, callback);
            }
        }

        // =========================================================================
        // PHASE 7: Tactical Fallback for Heavily Damaged Units (< 5 str)
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength <= 0 || ai.strength >= 5 || !engine.units.contains(ai) || ai.attacked || ai.moved || engine.scenario == null) continue;
            PgDatabase.HexCell curCell = engine.scenario.grid[ai.col][ai.row];
            if (curCell.isObjective || curCell.terrainType == 12) continue; // Do not abandon objectives

            // Check if threatened by nearby enemies within 2 hexes
            PgDatabase.ActiveUnit nearestThreat = null;
            int minThreatDist = 999;
            for (PgDatabase.ActiveUnit eu : engine.units) {
                if (eu != null && eu.isAxis && eu.strength > 0) {
                    int d = PgGameEngine.hexDistance(ai.col, ai.row, eu.col, eu.row);
                    if (d < minThreatDist) {
                        minThreatDist = d;
                        nearestThreat = eu;
                    }
                }
            }

            if (nearestThreat != null && minThreatDist <= 2) {
                Map<Integer, Integer> reach = engine.getReachableHexes(ai);
                int bestC = ai.col, bestR = ai.row;
                double bestRetreatScore = -999.0;
                for (int key : reach.keySet()) {
                    int mc = key % engine.scenario.cols, mr = key / engine.scenario.cols;
                    int dThreat = PgGameEngine.hexDistance(mc, mr, nearestThreat.col, nearestThreat.row);
                    if (dThreat <= 1) continue; // Never retreat directly adjacent to enemy

                    double sc = dThreat * 15.0;
                    PgDatabase.HexCell dest = engine.scenario.grid[mc][mr];
                    if (dest.isSupplyPoint() || dest.isObjective) sc += 30.0;
                    if (dest.isCloseDefenseTerrain()) sc += 10.0;

                    if (sc > bestRetreatScore) {
                        bestRetreatScore = sc;
                        bestC = mc;
                        bestR = mr;
                    }
                }
                if (bestC != ai.col || bestR != ai.row) {
                    int origC = ai.col, origR = ai.row;
                    if (engine.moveUnit(ai, bestC, bestR)) {
                        if (callback != null) callback.onAiUnitMoved(ai, origC, origR, bestC, bestR);
                    }
                }
            }
        }

        // =========================================================================
        // PHASE 8: Replacements for Safe Wounded AI Units
        // =========================================================================
        for (PgDatabase.ActiveUnit ai : aiList) {
            if (ai.strength > 0 && ai.strength <= 7 && !ai.attacked && engine.alliedPrestige >= 25 && engine.scenario != null) {
                PgDatabase.HexCell cell = engine.scenario.grid[ai.col][ai.row];
                boolean isSupply = cell.isSupplyPoint() || cell.isObjective || cell.terrainType == 8;
                boolean safeFromEnemy = true;
                for (int[] nb : engine.getNeighbors(ai.col, ai.row)) {
                    PgDatabase.ActiveUnit adj = engine.getUnitAt(nb[0], nb[1], false);
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
        // PHASE 9: Smart AI Reinforcements / Prestige Spending
        // Allow multiple purchases per turn as long as AI has prestige, placing units where most effective
        // =========================================================================
        if (engine.scenario != null && engine.alliedPrestige >= 50) {
            List<PgDatabase.UnitDef> candidateDefs = new ArrayList<>();
            Set<Integer> seenDefIds = new HashSet<>();
            for (PgDatabase.ActiveUnit initU : engine.scenario.initialUnits) {
                if (!initU.isAxis && initU.unitDefId > 0 && initU.unitDefId < db.equipment.size()) {
                    if (seenDefIds.add(initU.unitDefId)) {
                        PgDatabase.UnitDef d = db.equipment.get(initU.unitDefId);
                        if (d != null && !d.isNaval() && !d.isFortification()
                                && d.unitClass != PgDatabase.CLASS_FORT
                                && d.unitClass != PgDatabase.CLASS_LAND_TRANSPORT) {
                            candidateDefs.add(d);
                        }
                    }
                }
            }

            if (candidateDefs.isEmpty()) {
                for (int i = 1; i < db.equipment.size(); i++) {
                    PgDatabase.UnitDef d = db.equipment.get(i);
                    if (d != null && !d.isNaval() && !d.isFortification()
                            && d.unitClass != PgDatabase.CLASS_FORT
                            && d.unitClass != PgDatabase.CLASS_LAND_TRANSPORT
                            && d.cost > 0) {
                        candidateDefs.add(d);
                        if (candidateDefs.size() >= 10) break;
                    }
                }
            }

            int tankCount = 0, infCount = 0, artyCount = 0, atCount = 0, aaCount = 0, airCount = 0;
            for (PgDatabase.ActiveUnit u : engine.units) {
                if (u != null && !u.isAxis && u.strength > 0) {
                    PgDatabase.UnitDef d = db.equipment.get(u.unitDefId);
                    if (d.unitClass == PgDatabase.CLASS_TANK) tankCount++;
                    else if (d.unitClass == PgDatabase.CLASS_INFANTRY) infCount++;
                    else if (d.unitClass == PgDatabase.CLASS_ARTILLERY) artyCount++;
                    else if (d.unitClass == PgDatabase.CLASS_ANTI_TANK) atCount++;
                    else if (d.unitClass == PgDatabase.CLASS_AIR_DEFENSE || d.unitClass == PgDatabase.CLASS_ANTI_AIR) aaCount++;
                    else if (d.isAir()) airCount++;
                }
            }

            int maxPurchasesThisTurn = 15;
            int purchasesDone = 0;

            while (purchasesDone < maxPurchasesThisTurn) {
                int minAffordableCost = Integer.MAX_VALUE;
                for (PgDatabase.UnitDef d : candidateDefs) {
                    if (d.cost <= engine.alliedPrestige && d.cost < minAffordableCost) {
                        minAffordableCost = d.cost;
                    }
                }
                if (minAffordableCost > engine.alliedPrestige) {
                    break;
                }

                PgDatabase.UnitDef bestDef = null;
                int[] bestSpot = null;
                double bestEffectivenessScore = -999999.0;

                List<int[]> groundSpots = engine.getAvailableAiDeployHexes(false);
                List<int[]> airSpots = engine.getAvailableAiDeployHexes(true);

                for (PgDatabase.UnitDef d : candidateDefs) {
                    if (d.cost > engine.alliedPrestige) continue;
                    List<int[]> spots = d.isAir() ? airSpots : groundSpots;
                    if (spots.isEmpty()) continue;

                    for (int[] spot : spots) {
                        double score = evaluateAiDeploymentEffectiveness(d, spot[0], spot[1],
                                infCount, artyCount, tankCount, atCount, aaCount, airCount);
                        if (score > bestEffectivenessScore) {
                            bestEffectivenessScore = score;
                            bestDef = d;
                            bestSpot = spot;
                        }
                    }
                }

                if (bestDef != null && bestSpot != null && bestEffectivenessScore > -500.0) {
                    PgDatabase.ActiveUnit spawned = engine.purchaseAiUnit(bestDef.id, bestSpot[0], bestSpot[1]);
                    if (spawned != null) {
                        purchasesDone++;
                        if (bestDef.unitClass == PgDatabase.CLASS_TANK) tankCount++;
                        else if (bestDef.unitClass == PgDatabase.CLASS_INFANTRY) infCount++;
                        else if (bestDef.unitClass == PgDatabase.CLASS_ARTILLERY) artyCount++;
                        else if (bestDef.unitClass == PgDatabase.CLASS_ANTI_TANK) atCount++;
                        else if (bestDef.unitClass == PgDatabase.CLASS_AIR_DEFENSE || bestDef.unitClass == PgDatabase.CLASS_ANTI_AIR) aaCount++;
                        else if (bestDef.isAir()) airCount++;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        // Advance turn counter, update weather, award continuing hex prestige, and prepare Player units
        engine.campaignManager.advanceTurnAfterAi();
        return aiReports;
    }

    private void executeAiMobileGroundAssault(PgDatabase.ActiveUnit ai, List<PgGameEngine.CombatReport> aiReports,
                                             List<PgDatabase.HexCell> capturableAxisObjectives,
                                             List<PgDatabase.HexCell> threatenedAlliedObjectives,
                                             PgGameEngine.AiTurnCallback callback) {
        if (ai.strength <= 0 || !engine.units.contains(ai) || ai.attacked) return;
        PgDatabase.UnitDef def = db.equipment.get(ai.unitDefId);
        if (def.isAir() || def.isNaval() || def.range > 1 || engine.scenario == null) return;

        // Transport management
        if (ai.transportDefId > 0 && !ai.moved && ai.movePointsUsed == 0) {
            boolean nearEnemy = false;
            for (PgDatabase.ActiveUnit e : engine.units) {
                if (e != null && e.isAxis && e.strength > 0 && PgGameEngine.hexDistance(ai.col, ai.row, e.col, e.row) <= 2) {
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

        // Step 1: Check for high-value immediate attack
        List<PgDatabase.ActiveUnit> immediateTargets = engine.getValidTargets(ai);
        if (!immediateTargets.isEmpty()) {
            PgDatabase.ActiveUnit best = pickBestAiTarget(ai, immediateTargets);
            if (best != null) {
                double sc = evaluateAiAttackScore(ai, best);
                if (sc >= 0 || best.strength <= 3) {
                    PgGameEngine.CombatReport rep = engine.executeCombat(ai, best);
                    aiReports.add(rep);
                    if (callback != null) callback.onAiCombatExecuted(ai, best, rep);
                    if (rep.overrun && ai.strength > 0 && !ai.attacked) {
                        List<PgDatabase.ActiveUnit> overrunTargets = engine.getValidTargets(ai);
                        if (!overrunTargets.isEmpty()) {
                            PgDatabase.ActiveUnit overBest = pickBestAiTarget(ai, overrunTargets);
                            if (overBest != null) {
                                PgGameEngine.CombatReport oRep = engine.executeCombat(ai, overBest);
                                aiReports.add(oRep);
                                if (callback != null) callback.onAiCombatExecuted(ai, overBest, oRep);
                            }
                        }
                    }
                    return;
                }
            }
        }

        // Step 2: Strategic Maneuver & Repositioning
        if (!ai.moved) {
            PgDatabase.HexCell primaryObjTarget = null;
            int minObjDist = 999;
            for (PgDatabase.HexCell capObj : capturableAxisObjectives) {
                int d = PgGameEngine.hexDistance(ai.col, ai.row, capObj.col, capObj.row);
                if (d < minObjDist) {
                    minObjDist = d;
                    primaryObjTarget = capObj;
                }
            }
            if (primaryObjTarget == null) {
                for (PgDatabase.HexCell thObj : threatenedAlliedObjectives) {
                    int d = PgGameEngine.hexDistance(ai.col, ai.row, thObj.col, thObj.row);
                    if (d < minObjDist) {
                        minObjDist = d;
                        primaryObjTarget = thObj;
                    }
                }
            }

            PgDatabase.ActiveUnit nearestEnemy = null;
            int minEnemyDist = 999;
            for (PgDatabase.ActiveUnit eu : engine.units) {
                if (eu != null && eu.isAxis && eu.strength > 0 && !engine.isUnitOnAirLayer(eu)) {
                    int d = PgGameEngine.hexDistance(ai.col, ai.row, eu.col, eu.row);
                    if (d < minEnemyDist) {
                        minEnemyDist = d;
                        nearestEnemy = eu;
                    }
                }
            }

            Map<Integer, Integer> reach = engine.getReachableHexes(ai);
            int bestMoveCol = ai.col, bestMoveRow = ai.row;
            double bestHexScore = -999.0;

            for (int key : reach.keySet()) {
                int mc = key % engine.scenario.cols, mr = key / engine.scenario.cols;
                PgDatabase.HexCell destCell = engine.scenario.grid[mc][mr];
                double score = 0.0;

                // Huge reward for capturing an objective!
                if (destCell.isObjective && db.isAxisNation(destCell.ownerNation, engine.scenario)) {
                    score += 100.0;
                } else if (destCell.isObjective && !db.isAxisNation(destCell.ownerNation, engine.scenario)) {
                    score += 25.0; // Garrisoning friendly objective
                }

                // Strategic destination proximity
                if (primaryObjTarget != null) {
                    int dObj = PgGameEngine.hexDistance(mc, mr, primaryObjTarget.col, primaryObjTarget.row);
                    score -= dObj * 3.0;
                }
                if (nearestEnemy != null) {
                    int dEnemy = PgGameEngine.hexDistance(mc, mr, nearestEnemy.col, nearestEnemy.row);
                    score -= dEnemy * 2.5;
                }

                // Terrain bonuses
                if (def.unitClass == PgDatabase.CLASS_INFANTRY) {
                    if (destCell.isCloseDefenseTerrain()) score += 5.0;
                } else if (def.unitClass == PgDatabase.CLASS_TANK) {
                    if (!destCell.isCloseDefenseTerrain()) score += 3.0;
                    else score -= 4.0; // Tanks avoid close terrain
                }

                // Check if adjacent to enemy units from destination (mc, mr)
                List<PgDatabase.ActiveUnit> adjEnemies = new ArrayList<>();
                for (int[] nb : engine.getNeighbors(mc, mr)) {
                    PgDatabase.ActiveUnit adjEnemy = engine.getUnitAt(nb[0], nb[1], false);
                    if (adjEnemy != null && adjEnemy.isAxis && adjEnemy.strength > 0) {
                        adjEnemies.add(adjEnemy);
                    }
                }

                if (!adjEnemies.isEmpty()) {
                    double bestAtkScore = -999.0;
                    for (PgDatabase.ActiveUnit enemy : adjEnemies) {
                        double atkSc = evaluateHypotheticalAttackScore(ai, mc, mr, enemy);
                        if (atkSc > bestAtkScore) {
                            bestAtkScore = atkSc;
                        }
                    }

                    if (bestAtkScore >= -1.0) {
                        score += 25.0 + (bestAtkScore * 2.0);
                    } else {
                        score -= 50.0 + (adjEnemies.size() * 15.0);
                    }
                } else if (nearestEnemy != null) {
                    int dEnemy = PgGameEngine.hexDistance(mc, mr, nearestEnemy.col, nearestEnemy.row);
                    if (dEnemy == 2) {
                        score += 10.0;
                        if (destCell.isCloseDefenseTerrain()) score += 8.0;
                    }
                }

                if (score > bestHexScore) {
                    bestHexScore = score;
                    bestMoveCol = mc;
                    bestMoveRow = mr;
                }
            }

            if (bestMoveCol != ai.col || bestMoveRow != ai.row) {
                int origC = ai.col, origR = ai.row;
                if (engine.moveUnit(ai, bestMoveCol, bestMoveRow)) {
                    if (callback != null) callback.onAiUnitMoved(ai, origC, origR, bestMoveCol, bestMoveRow);
                    List<PgDatabase.ActiveUnit> postMoveTargets = engine.getValidTargets(ai);
                    if (!postMoveTargets.isEmpty()) {
                        PgDatabase.ActiveUnit postBest = pickBestAiTarget(ai, postMoveTargets);
                        if (postBest != null) {
                            PgGameEngine.CombatReport rep = engine.executeCombat(ai, postBest);
                            aiReports.add(rep);
                            if (callback != null) callback.onAiCombatExecuted(ai, postBest, rep);
                            if (rep.overrun && ai.strength > 0 && !ai.attacked) {
                                List<PgDatabase.ActiveUnit> overrunTargets = engine.getValidTargets(ai);
                                if (!overrunTargets.isEmpty()) {
                                    PgDatabase.ActiveUnit overBest = pickBestAiTarget(ai, overrunTargets);
                                    if (overBest != null) {
                                        PgGameEngine.CombatReport oRep = engine.executeCombat(ai, overBest);
                                        aiReports.add(oRep);
                                        if (callback != null) callback.onAiCombatExecuted(ai, overBest, oRep);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
