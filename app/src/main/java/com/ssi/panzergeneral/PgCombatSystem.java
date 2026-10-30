package com.ssi.panzergeneral;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves Panzer General 1994 Strategy Guide combat rules: target validation,
 * attack/defense grades, D20 CRT fire resolution, combat odds prediction,
 * fighter escort dogfights, air interception/flak, defensive artillery support,
 * rugged defense, submarine diving, tank overrun, forced retreat, and surrender.
 */
public class PgCombatSystem {
    private final PgGameEngine engine;
    private final PgDatabase db;

    public PgCombatSystem(PgGameEngine engine) {
        this.engine = engine;
        this.db = engine.db;
    }

    public List<PgDatabase.ActiveUnit> getValidTargets(PgDatabase.ActiveUnit attacker) {
        List<PgDatabase.ActiveUnit> targets = new ArrayList<>();
        if (attacker == null || attacker.attacked || attacker.strength <= 0
                || attacker.inAirTransport || attacker.inSeaTransport
                || (engine.supplyEnabled && attacker.ammo <= 0)) {
            return targets;
        }
        PgDatabase.UnitDef aDef = engine.getEffectiveUnitDef(attacker);
        boolean aAir = engine.isUnitOnAirLayer(attacker);

        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u == null || u.strength <= 0 || u.isAxis == attacker.isAxis) continue;
            if (engine.fogOfWarEnabled && !engine.isUnitVisibleToPlayer(u)) continue;
            int dist = PgGameEngine.hexDistance(attacker.col, attacker.row, u.col, u.row);
            boolean dAir = engine.isUnitOnAirLayer(u);
            boolean dNaval = u.inSeaTransport || db.equipment.get(u.unitDefId).isNaval();
            PgDatabase.UnitDef dDef = engine.getEffectiveUnitDef(u);
            if (canAttackTarget(attacker, aDef, aAir, u, dDef, dAir, dNaval, dist)) {
                targets.add(u);
            }
        }
        return targets;
    }

    public boolean canAttackTarget(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef, boolean aAir,
                                   PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef, boolean dAir, boolean dNaval, int dist) {
        if (engine.weatherEnabled && engine.weather == 2 && (aAir || dAir)) {
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

    public int getStrategyGuideAttackGrade(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef,
                                          PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef,
                                          int dist, boolean stiffDefense) {
        if (engine.supplyEnabled && attacker.ammo <= 0) return 0;
        if (engine.weatherEnabled && engine.weather == 2 && (engine.isUnitOnAirLayer(attacker) || engine.isUnitOnAirLayer(defender))) return 0;
        if (aDef.unitClass == PgDatabase.CLASS_AIR_DEFENSE && !engine.isUnitOnAirLayer(defender)) return 0;

        int base;
        if (engine.isUnitOnAirLayer(defender)) {
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
        if (!engine.isUnitOnAirLayer(defender) && !defender.inSeaTransport && !dDef.isNaval()
                && engine.isRiverHex(defender.col, defender.row)
                && !dDef.isBridgeEngineer()
                && !engine.hasFriendlyBridgeEngineer(defender.col, defender.row, defender.isAxis)) {
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
        if (engine.isUnitOnAirLayer(attacker) || engine.isUnitOnAirLayer(defender)) {
            def = dDef.airDefense;
        } else if (aDef.unitClass == PgDatabase.CLASS_SUBMARINE && (dDef.isNaval() || defender.inSeaTransport)) {
            // Authentic Torpedo Defense: ships defend against torpedoes using Close Defense
            def = Math.max(1, dDef.closeDefense);
        } else {
            PgDatabase.HexCell cell = engine.scenario.grid[defender.col][defender.row];
            boolean closeCombat = (cell.isCloseDefenseTerrain() && aDef.unitClass == PgDatabase.CLASS_INFANTRY && dDef.unitClass != PgDatabase.CLASS_INFANTRY)
                    || stiffDefense;
            def = closeCombat ? Math.max(1, dDef.closeDefense) : dDef.groundDefense;
        }

        // Defensive Grade Modifiers (Cumulative) per Strategy Guide Chapter 5:
        // + entrenchment: full entrenchment if attacker is non-infantry, 1/2 entrenchment if non-engineer infantry, 0 if pioneer/engineer
        // Mounted units receive 0 entrenchment bonus
        if (!engine.isUnitOnAirLayer(defender) && !engine.isUnitOnAirLayer(attacker) && !defender.mountedInTransport) {
            if (aDef.isPioneerOrEngineer()) {
                // Pioneers/Engineers ignore entrenchment
            } else if (aDef.unitClass == PgDatabase.CLASS_INFANTRY) {
                def += defender.entrenchment / 2;
            } else {
                def += defender.entrenchment;
            }
        }

        // +8 for all ground units versus naval units
        if (!engine.isUnitOnAirLayer(defender) && (aDef.isNaval() || attacker.inSeaTransport)) {
            def += 8;
        }

        // +3 versus ranged attacks when it's raining or snowing
        if (engine.weatherEnabled && engine.weather == 2 && dist > 1) {
            def += 3;
        }

        // +1 for each level of experience the unit enjoys
        def += defender.getStars();

        // +4 if the opponent (attacker) is not an airplane and is on an unbridged river hex
        if (!engine.isUnitOnAirLayer(attacker) && !attacker.inSeaTransport && !aDef.isNaval()
                && engine.isRiverHex(attacker.col, attacker.row)
                && !aDef.isBridgeEngineer()
                && !engine.hasFriendlyBridgeEngineer(attacker.col, attacker.row, attacker.isAxis)) {
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
        if (u == null || def == null) return 0;
        int str = Math.max(0, u.strength - u.suppression);
        if (str <= 0) return 0;

        // Out of fuel halves vehicle combat strength (Chapter 3)
        if (engine.supplyEnabled && def.maxFuel > 0 && !u.inAirTransport && !u.inSeaTransport && u.fuel <= 0) {
            str = (str + 1) / 2;
        }

        // Overcast halves air combat strengths (Chapter 3)
        if (engine.weatherEnabled && engine.weather == 1 && (def.isAir() || u.inAirTransport)) {
            str = (str + 1) / 2;
        }

        // Mud halves ground attacking strength (Chapter 3)
        if (engine.weatherEnabled && engine.groundCondition == 1 && isAttacker && !def.isAir() && !u.inAirTransport) {
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
        if (engine.supplyEnabled && dDef.maxAmmo > 0 && defender.ammo <= 0) return false;
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
        boolean aAir = engine.isUnitOnAirLayer(attacker);
        boolean dAir = engine.isUnitOnAirLayer(defender);
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
            int roll = engine.rng.nextInt(20) + 1;
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

    public PgGameEngine.CombatPrediction predictCombat(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        PgDatabase.UnitDef aDef = engine.getEffectiveUnitDef(attacker);
        PgDatabase.UnitDef dDef = engine.getEffectiveUnitDef(defender);
        int dist = PgGameEngine.hexDistance(attacker.col, attacker.row, defender.col, defender.row);

        boolean isGroundClose = (dist == 1 && !engine.isUnitOnAirLayer(attacker) && !engine.isUnitOnAirLayer(defender));
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

        PgDatabase.HexCell defCell = engine.scenario.grid[defender.col][defender.row];
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

        PgDatabase.ActiveUnit support = findDefensiveSupport(attacker, defender);
        boolean artillerySupport = (support != null && dist == 1 && !engine.isUnitOnAirLayer(attacker) && !engine.isUnitOnAirLayer(defender));
        PgDatabase.ActiveUnit interceptor = findAirInterceptorOrFlak(attacker, defender);
        boolean fighterInterceptor = (interceptor != null);
        PgDatabase.ActiveUnit escort = (fighterInterceptor && engine.isUnitOnAirLayer(attacker)) ? findFighterEscort(attacker) : null;

        if (fighterInterceptor) {
            PgDatabase.UnitDef iDef = engine.getEffectiveUnitDef(interceptor);
            boolean isFighter = (iDef != null && iDef.unitClass == PgDatabase.CLASS_FIGHTER);
            if (isFighter && escort != null) {
                // Escort engages interceptor: reduces or eliminates interceptor threat to bomber
                PgDatabase.UnitDef eDef = engine.getEffectiveUnitDef(escort);
                int eAtk = getStrategyGuideAttackGrade(escort, eDef, interceptor, iDef, 1, false);
                int iDefGrd = getStrategyGuideDefenseGrade(escort, eDef, interceptor, iDef, 1, false);
                int eEff = calculateEffectiveDifference(eAtk, iDefGrd);
                int eStr = getEffectiveFiringStrength(escort, eDef, true);
                double ePHit = (eAtk > 0) ? getD20HitProbability(eEff, false) : 0.0;
                double ePSupp = (eAtk > 0) ? getD20SuppProbability(eEff, false) : 0.0;
                double expIntKills = Math.min(interceptor.strength, eStr * ePHit);
                double expIntSupp = Math.min(interceptor.strength - expIntKills, eStr * ePSupp);

                int remIntStr = (int) Math.max(0, interceptor.strength - expIntKills - expIntSupp);
                if (remIntStr > 0) {
                    int iAtk = getStrategyGuideAttackGrade(interceptor, iDef, attacker, aDef, 1, false);
                    int aGrd = getStrategyGuideDefenseGrade(interceptor, iDef, attacker, aDef, 1, false);
                    double iPHit = (iAtk > 0) ? getD20HitProbability(calculateEffectiveDifference(iAtk, aGrd), false) : 0.0;
                    double expBLoss = Math.min(attacker.strength, remIntStr * iPHit);
                    expAtkLosses += expBLoss;
                    atkStr = (int) Math.max(0, atkStr - expBLoss);
                }
            } else {
                int iStr = getEffectiveFiringStrength(interceptor, iDef, true);
                int iAtk = getStrategyGuideAttackGrade(interceptor, iDef, attacker, aDef, 1, false);
                int aGrd = getStrategyGuideDefenseGrade(interceptor, iDef, attacker, aDef, 1, false);
                double iPHit = (iAtk > 0) ? getD20HitProbability(calculateEffectiveDifference(iAtk, aGrd), false) : 0.0;
                double expBLoss = Math.min(attacker.strength, iStr * iPHit);
                expAtkLosses += expBLoss;
                atkStr = (int) Math.max(0, atkStr - expBLoss);
            }
        }

        if (artillerySupport) {
            PgDatabase.UnitDef sDef = engine.getEffectiveUnitDef(support);
            int distToAtk = PgGameEngine.hexDistance(support.col, support.row, attacker.col, attacker.row);
            int sAtkGrade = getStrategyGuideAttackGrade(support, sDef, attacker, aDef, distToAtk, false);
            int aDefGrade = getStrategyGuideDefenseGrade(support, sDef, attacker, aDef, distToAtk, false);
            int sEffDiff = calculateEffectiveDifference(sAtkGrade, aDefGrade);
            int sStr = getEffectiveFiringStrength(support, sDef, true);
            double sPHit = (sAtkGrade > 0) ? getD20HitProbability(sEffDiff, true) : 0.0;
            double sPSupp = (sAtkGrade > 0) ? getD20SuppProbability(sEffDiff, true) : 0.0;
            double expArtKills = Math.min(attacker.strength, sStr * sPHit);
            double expArtSupp = Math.min(attacker.strength - expArtKills, sStr * sPSupp);
            expAtkLosses += expArtKills;
            atkStr = (int) Math.max(0, atkStr - expArtKills - expArtSupp);
            expDefLosses = (atkAttackGrade > 0) ? Math.min(defender.strength, atkStr * atkPHit) : 0.0;
        }

        int expectedDefenderLosses = (int) Math.round(expDefLosses);
        int expectedAttackerLosses = (int) Math.round(expAtkLosses);
        String interceptorTag = fighterInterceptor ? (escort != null ? " [Escort vs Interceptor]" : " [Interceptor]") : "";
        String summary = String.format(Locale.ROOT, "Expected: Atk -%d vs Def -%d (Ini %d:%d)%s%s%s",
                expectedAttackerLosses, expectedDefenderLosses,
                aIni, dIni,
                ruggedDefenseChance ? " [Rugged Def Risk]" : "",
                interceptorTag,
                artillerySupport ? " [Artillery Sup]" : "");

        return PgGameEngine.CombatPrediction.builder()
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

    public PgGameEngine.CombatReport executeCombat(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        engine.lastUndoState = null;
        return executeCombatInternal(attacker, defender, false);
    }

    public PgGameEngine.CombatReport executeCombatInternal(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender, boolean surpriseAmbush) {
        PgDatabase.UnitDef aDef = engine.getEffectiveUnitDef(attacker);
        PgDatabase.UnitDef dDef = engine.getEffectiveUnitDef(defender);
        PgGameEngine.CombatReport rep = PgGameEngine.CombatReport.builder()
                .attackerName(aDef.name)
                .defenderName(dDef.name)
                .attackerId(attacker.instanceId)
                .defenderId(defender.instanceId)
                .attackerIconId(engine.getEffectiveIconId(attacker))
                .defenderIconId(engine.getEffectiveIconId(defender))
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

        int dist = PgGameEngine.hexDistance(attacker.col, attacker.row, defender.col, defender.row);

        // 1. Check Fighter Interceptor / Flak protection against Air Attacks
        PgDatabase.ActiveUnit interceptor = findAirInterceptorOrFlak(attacker, defender);
        if (interceptor != null) {
            rep.interceptorUnit = interceptor;
            PgDatabase.UnitDef iDef = engine.getEffectiveUnitDef(interceptor);
            boolean isFighterInterceptor = (iDef != null && iDef.unitClass == PgDatabase.CLASS_FIGHTER);

            if (isFighterInterceptor) {
                // Check if the attacking bomber has a friendly Fighter Escort
                PgDatabase.ActiveUnit escort = findFighterEscort(attacker);
                if (escort != null) {
                    rep.supportUnit = escort;
                    // Fighter Escort attacks the Enemy Fighter Interceptor FIRST!
                    PgGameEngine.CombatReport dogfightRep = resolveEscortDogfight(escort, interceptor);
                    rep.supportReport = dogfightRep;

                    int unsuppressedIntStr = getEffectiveFiringStrength(interceptor, iDef, true);
                    if (unsuppressedIntStr > 0 && interceptor.strength > 0) {
                        // Surviving unsuppressed interceptor attacks the bomber
                        if (engine.supplyEnabled && iDef.maxAmmo > 0) {
                            interceptor.ammo = Math.max(0, interceptor.ammo - 1);
                        }
                        int iAtk = getStrategyGuideAttackGrade(interceptor, iDef, attacker, aDef, 1, false);
                        int aGrd = getStrategyGuideDefenseGrade(interceptor, iDef, attacker, aDef, 1, false);
                        int effDiff = calculateEffectiveDifference(iAtk, aGrd);
                        int[] iRes = resolveD20Fire(unsuppressedIntStr, effDiff, false);
                        int bLoss = Math.min(attacker.strength, iRes[0]);
                        int bSupp = Math.min(attacker.strength - bLoss, iRes[1]);
                        attacker.strength = Math.max(0, attacker.strength - bLoss);
                        attacker.suppression = Math.min(attacker.strength, attacker.suppression + bSupp);
                        rep.attackerLosses += bLoss;
                        if (attacker.strength <= 0) {
                            rep.attackerDestroyed = true;
                            attacker.attacked = true;
                            engine.units.remove(attacker);
                            return rep;
                        }
                    }
                } else {
                    // No escort: Interceptor attacks bomber directly with full strength
                    if (engine.supplyEnabled && iDef.maxAmmo > 0) {
                        interceptor.ammo = Math.max(0, interceptor.ammo - 1);
                    }
                    int iStr = getEffectiveFiringStrength(interceptor, iDef, true);
                    int iAtk = getStrategyGuideAttackGrade(interceptor, iDef, attacker, aDef, 1, false);
                    int aGrd = getStrategyGuideDefenseGrade(interceptor, iDef, attacker, aDef, 1, false);
                    int effDiff = calculateEffectiveDifference(iAtk, aGrd);
                    int[] iRes = resolveD20Fire(iStr, effDiff, false);
                    int bLoss = Math.min(attacker.strength, iRes[0]);
                    int bSupp = Math.min(attacker.strength - bLoss, iRes[1]);
                    attacker.strength = Math.max(0, attacker.strength - bLoss);
                    attacker.suppression = Math.min(attacker.strength, attacker.suppression + bSupp);
                    rep.attackerLosses += bLoss;
                    if (attacker.strength <= 0) {
                        rep.attackerDestroyed = true;
                        attacker.attacked = true;
                        engine.units.remove(attacker);
                        return rep;
                    }
                }
            } else {
                // Ground Air Defense (Flak) interception
                if (engine.supplyEnabled && iDef != null && iDef.maxAmmo > 0) {
                    interceptor.ammo = Math.max(0, interceptor.ammo - 1);
                }
                int flakStr = getEffectiveFiringStrength(interceptor, iDef, true);
                int flakAtk = getStrategyGuideAttackGrade(interceptor, iDef, attacker, aDef, dist, false);
                int aGrd = getStrategyGuideDefenseGrade(interceptor, iDef, attacker, aDef, dist, false);
                int effDiff = calculateEffectiveDifference(flakAtk, aGrd);
                int[] fRes = resolveD20Fire(flakStr, effDiff, false);
                int bLoss = Math.min(attacker.strength, fRes[0]);
                int bSupp = Math.min(attacker.strength - bLoss, fRes[1]);
                attacker.strength = Math.max(0, attacker.strength - bLoss);
                attacker.suppression = Math.min(attacker.strength, attacker.suppression + bSupp);
                rep.attackerLosses += bLoss;
                if (attacker.strength <= 0) {
                    rep.attackerDestroyed = true;
                    attacker.attacked = true;
                    engine.units.remove(attacker);
                    return rep;
                }
            }
        }

        // 2. Check Defensive Artillery Support against ground close assault
        if (!surpriseAmbush) {
            PgDatabase.ActiveUnit support = findDefensiveSupport(attacker, defender);
            if (support != null && dist == 1 && !engine.isUnitOnAirLayer(attacker) && !engine.isUnitOnAirLayer(defender)) {
                rep.supportUnit = support;
                PgGameEngine.CombatReport sRep = resolveDefensiveSupportFire(support, attacker, defender);
                rep.supportReport = sRep;
                if (sRep != null) {
                    rep.attackerLosses += sRep.defenderLosses;
                    if (sRep.defenderDestroyed) {
                        rep.attackerDestroyed = true;
                        attacker.attacked = true;
                        return rep;
                    }
                }
            }
        }

        // 3. Stiff Defense (Rugged Defense) Chance (Chapter 5)
        boolean isGroundClose = (dist == 1 && !engine.isUnitOnAirLayer(attacker) && !engine.isUnitOnAirLayer(defender));
        boolean stiffDefense = false;
        if (surpriseAmbush) {
            stiffDefense = true;
        } else if (isGroundClose && defender.entrenchment >= 1 && !aDef.isPioneerOrEngineer()) {
            double expRatio = (defender.getStars() + 2.0) / (attacker.getStars() + 2.0);
            double entrRatio = (dDef.entrenchmentRate + 1.0) / (aDef.entrenchmentRate + 1.0);
            double ambushChance = defender.entrenchment * expRatio * entrRatio * 5.0;
            if (engine.rng.nextInt(100) < (int) Math.round(ambushChance)) {
                stiffDefense = true;
            }
        }
        rep.ruggedDefense = stiffDefense;

        // Submarine Evasion by Diving (Strategy Guide Chapter 4: 50% vs DD, 75% vs Tac Bomber)
        if (dDef.unitClass == PgDatabase.CLASS_SUBMARINE && !surpriseAmbush) {
            int diveChance = (aDef.unitClass == PgDatabase.CLASS_TAC_BOMBER) ? 75 : 50;
            if (engine.rng.nextInt(100) < diveChance) {
                rep.submarineDived = true;
                attacker.attacked = true;
                if (engine.supplyEnabled && aDef.maxAmmo > 0) attacker.ammo = Math.max(0, attacker.ammo - 1);
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

        PgDatabase.HexCell defCell = engine.scenario.grid[defender.col][defender.row];
        int iniCap = (dist == 1 && !aDef.isAir() && !dDef.isAir()) ? defCell.getInitiativeCap() : 99;
        int aIni = Math.min(iniCap, aDef.initiative) + attacker.getStars() + engine.rng.nextInt(3) + 1;
        int dIni = Math.min(iniCap, dDef.initiative) + defender.getStars() + engine.rng.nextInt(3) + 1;
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
        if (aDef.unitClass == PgDatabase.CLASS_LEVEL_BOMBER && !engine.isUnitOnAirLayer(defender)) {
            defender.suppression = Math.min(defender.strength, defender.suppression + defSupp + 1);
            rep.defenderSuppressed = defSupp + 1;
            defender.entrenchment = Math.max(0, defender.entrenchment - 2);
            if (engine.supplyEnabled) {
                defender.fuel = (defender.fuel * 3) / 4;
                defender.ammo = (defender.ammo * 3) / 4;
            }
        } else if (dist > 1 || aDef.unitClass == PgDatabase.CLASS_ARTILLERY) {
            defender.suppression = Math.min(defender.strength, defender.suppression + defSupp);
            rep.defenderSuppressed = defSupp;
            if (!engine.isUnitOnAirLayer(defender) && defender.entrenchment > 0) {
                defender.entrenchment = Math.max(0, defender.entrenchment - 1);
            }
        } else if (!engine.isUnitOnAirLayer(defender) && defender.entrenchment > 0) {
            int entStrip = aDef.isPioneerOrEngineer() ? 2 : 1;
            defender.entrenchment = Math.max(0, defender.entrenchment - entStrip);
            rep.defenderSuppressed = defSupp;
        }

        atkLoss = Math.min(attacker.strength, atkLoss);
        defLoss = Math.min(defender.strength, defLoss);

        attacker.strength -= atkLoss;
        defender.strength -= defLoss;
        attacker.attacked = true; // Every unit can attack once per turn
        if (engine.supplyEnabled && aDef.maxAmmo > 0) attacker.ammo = Math.max(0, attacker.ammo - 1);
        if (engine.supplyEnabled && defenderCanReturnFire && dDef.maxAmmo > 0 && defender.ammo > 0) {
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
            engine.units.remove(defender);
            if (attacker.isAxis) engine.axisPrestige += fullReward;
            else engine.alliedPrestige += fullReward;

            // Panzer General Tank Overrun Rule:
            // If a Tank destroys a weak ground defender in close combat with 0 total attacker losses, it OVERRUNS!
            if (dist == 1 && aDef.unitClass == PgDatabase.CLASS_TANK && !engine.isUnitOnAirLayer(defender)
                    && rep.attackerLosses == 0 && !rep.attackerDestroyed && rep.defenderStartStr <= 6
                    && engine.movementSystem.getUnitStepMoveCost(attacker, aDef, engine.scenario.grid[defOrigCol][defOrigRow]) < 99) {
                rep.overrun = true;
                attacker.col = defOrigCol;
                attacker.row = defOrigRow;
                engine.onHexCaptured(defOrigCol, defOrigRow, attacker);
            } else if (!attacker.isAxis && dist == 1 && !aDef.isAir() && !dDef.isAir() && !rep.attackerDestroyed
                    && engine.movementSystem.getTerrainMoveCost(aDef, engine.scenario.grid[defOrigCol][defOrigRow], false) < 99) {
                // AI automatically advances into vacated hex on close assault
                attacker.col = defOrigCol;
                attacker.row = defOrigRow;
                engine.onHexCaptured(defOrigCol, defOrigRow, attacker);
            }
        } else if (dDef.isFortification() && (defender.getEffectiveStrength() == 0 || defender.suppression >= defender.strength || (dist == 1 && defLoss >= 3 && defLoss > atkLoss))) {
            // Bunkers, forts, and fortifications cannot move or retreat:
            // When fully suppressed after an attack or forced to retreat, the garrison surrenders!
            rep.defenderSurrendered = true;
            rep.defenderDestroyed = true;
            rep.defenderLosses = rep.defenderStartStr;
            defender.strength = 0;
            engine.units.remove(defender);
            if (attacker.isAxis) engine.axisPrestige += fullReward;
            else engine.alliedPrestige += fullReward;

            // Ground close assault captures the fortification hex
            if (dist == 1 && !aDef.isAir() && !rep.attackerDestroyed
                    && engine.movementSystem.getTerrainMoveCost(aDef, engine.scenario.grid[defOrigCol][defOrigRow], false) < 99) {
                attacker.col = defOrigCol;
                attacker.row = defOrigRow;
                engine.onHexCaptured(defOrigCol, defOrigRow, attacker);
            }
        } else if (dist == 1 && !engine.isUnitOnAirLayer(attacker) && !engine.isUnitOnAirLayer(defender)
                && (defLoss >= 3 && defLoss > atkLoss || defender.getEffectiveStrength() == 0)) {
            // Defender is forced to retreat
            boolean foundRetreat = false;
            if (!dDef.isFortification()) {
                for (int[] nb : engine.getNeighbors(defender.col, defender.row)) {
                    if (PgGameEngine.hexDistance(attacker.col, attacker.row, nb[0], nb[1]) > 1
                            && engine.getUnitAt(nb[0], nb[1], false) == null
                            && !engine.isInEnemyZoc(nb[0], nb[1], defender)
                            && engine.movementSystem.getUnitStepMoveCost(defender, dDef, engine.scenario.grid[nb[0]][nb[1]]) < 99) {
                        defender.col = nb[0];
                        defender.row = nb[1];
                        defender.entrenchment = 0;
                        rep.defenderRetreated = true;
                        foundRetreat = true;
                        break;
                    }
                }
                if (!foundRetreat) {
                    for (int[] nb : engine.getNeighbors(defender.col, defender.row)) {
                        if (PgGameEngine.hexDistance(attacker.col, attacker.row, nb[0], nb[1]) > 1
                                && engine.getUnitAt(nb[0], nb[1], false) == null
                                && engine.movementSystem.getUnitStepMoveCost(defender, dDef, engine.scenario.grid[nb[0]][nb[1]]) < 99) {
                            defender.col = nb[0];
                            defender.row = nb[1];
                            defender.entrenchment = 0;
                            rep.defenderRetreated = true;
                            foundRetreat = true;
                            break;
                        }
                    }
                }
            }
            if (foundRetreat) {
                if (attacker.isAxis) engine.axisPrestige += Math.max(1, fullReward / 4);
                else engine.alliedPrestige += Math.max(1, fullReward / 4);
            } else {
                // No retreat path -> Unit SURRENDERS!
                rep.defenderSurrendered = true;
                rep.defenderDestroyed = true;
                rep.defenderLosses = rep.defenderStartStr;
                defender.strength = 0;
                engine.units.remove(defender);
                if (attacker.isAxis) engine.axisPrestige += fullReward;
                else engine.alliedPrestige += fullReward;
            }
        } else if (defLoss > atkLoss) {
            // Damage lead prestige reward
            if (attacker.isAxis) engine.axisPrestige += Math.max(1, fullReward / 8);
            else engine.alliedPrestige += Math.max(1, fullReward / 8);
        }

        if (rep.attackerDestroyed) {
            engine.units.remove(attacker);
            int atkBasePrestige = Math.max(1, aDef.cost / 10);
            int atkFullReward = (atkBasePrestige * (attacker.getStars() + 1)) / 2;
            if (defender.isAxis) engine.axisPrestige += atkFullReward;
            else engine.alliedPrestige += atkFullReward;
        }

        engine.recalculateFogOfWar();
        engine.checkVictoryConditions();
        return rep;
    }

    public PgDatabase.ActiveUnit findFighterEscort(PgDatabase.ActiveUnit bomber) {
        if (bomber == null || !engine.isUnitOnAirLayer(bomber)) return null;
        PgDatabase.UnitDef bDef = engine.getEffectiveUnitDef(bomber);
        if (bDef == null || (bDef.unitClass != PgDatabase.CLASS_TAC_BOMBER && bDef.unitClass != PgDatabase.CLASS_LEVEL_BOMBER && !bomber.inAirTransport)) {
            return null;
        }

        PgDatabase.ActiveUnit bestEscort = null;
        int bestStr = -1;
        // Check adjacent hexes (and same hex if sharing)
        for (int[] nb : engine.getNeighbors(bomber.col, bomber.row)) {
            PgDatabase.ActiveUnit adj = engine.getUnitAt(nb[0], nb[1], true);
            if (adj != null && adj.isAxis == bomber.isAxis && adj.strength > 0
                    && (!engine.supplyEnabled || (adj.ammo > 0 && adj.fuel > 0))) {
                PgDatabase.UnitDef fDef = engine.getEffectiveUnitDef(adj);
                if (fDef != null && fDef.unitClass == PgDatabase.CLASS_FIGHTER) {
                    int effStr = getEffectiveFiringStrength(adj, fDef, true);
                    if (effStr > bestStr) {
                        bestStr = effStr;
                        bestEscort = adj;
                    }
                }
            }
        }
        return (bestStr > 0) ? bestEscort : null;
    }

    public PgGameEngine.CombatReport resolveEscortDogfight(PgDatabase.ActiveUnit escort, PgDatabase.ActiveUnit interceptor) {
        if (escort == null || interceptor == null) return null;
        PgDatabase.UnitDef eDef = engine.getEffectiveUnitDef(escort);
        PgDatabase.UnitDef iDef = engine.getEffectiveUnitDef(interceptor);
        if (eDef == null || iDef == null) return null;

        if (engine.supplyEnabled) {
            if (eDef.maxAmmo > 0) escort.ammo = Math.max(0, escort.ammo - 1);
            if (iDef.maxAmmo > 0) interceptor.ammo = Math.max(0, interceptor.ammo - 1);
        }

        // Air-to-air dogfight grades
        int eAtkGrade = getStrategyGuideAttackGrade(escort, eDef, interceptor, iDef, 1, false);
        int iDefGrade = getStrategyGuideDefenseGrade(escort, eDef, interceptor, iDef, 1, false);
        int eEffDiff = calculateEffectiveDifference(eAtkGrade, iDefGrade);

        int iAtkGrade = getStrategyGuideAttackGrade(interceptor, iDef, escort, eDef, 1, false);
        int eDefGrade = getStrategyGuideDefenseGrade(interceptor, iDef, escort, eDef, 1, false);
        int iEffDiff = calculateEffectiveDifference(iAtkGrade, eDefGrade);

        int eStr = getEffectiveFiringStrength(escort, eDef, true);
        int iStr = getEffectiveFiringStrength(interceptor, iDef, false);

        int eIni = eDef.initiative + escort.getStars() + engine.rng.nextInt(3) + 1;
        int iIni = iDef.initiative + interceptor.getStars() + engine.rng.nextInt(3) + 1;

        int intLoss = 0, intSupp = 0;
        int escLoss = 0, escSupp = 0;

        // Fighter Escort attacks the Enemy Fighter Interceptor FIRST!
        if (eIni >= iIni) {
            if (eAtkGrade > 0 && eStr > 0) {
                int[] res = resolveD20Fire(eStr, eEffDiff, false);
                intLoss = Math.min(interceptor.strength, res[0]);
                intSupp = Math.min(interceptor.strength - intLoss, res[1]);
            }
            int remIntStr = Math.max(0, iStr - intLoss - intSupp);
            if (iAtkGrade > 0 && remIntStr > 0) {
                int[] res = resolveD20Fire(remIntStr, iEffDiff, false);
                escLoss = Math.min(escort.strength, res[0]);
                escSupp = Math.min(escort.strength - escLoss, res[1]);
            }
        } else {
            if (iAtkGrade > 0 && iStr > 0) {
                int[] res = resolveD20Fire(iStr, iEffDiff, false);
                escLoss = Math.min(escort.strength, res[0]);
                escSupp = Math.min(escort.strength - escLoss, res[1]);
            }
            int remEscStr = Math.max(0, eStr - escLoss - escSupp);
            if (eAtkGrade > 0 && remEscStr > 0) {
                int[] res = resolveD20Fire(remEscStr, eEffDiff, false);
                intLoss = Math.min(interceptor.strength, res[0]);
                intSupp = Math.min(interceptor.strength - intLoss, res[1]);
            }
        }

        int origIntStr = interceptor.strength;
        interceptor.strength = Math.max(0, interceptor.strength - intLoss);
        interceptor.suppression = Math.min(interceptor.strength, interceptor.suppression + intSupp);
        escort.strength = Math.max(0, escort.strength - escLoss);
        escort.suppression = Math.min(escort.strength, escort.suppression + escSupp);

        escort.experience = Math.min(500, escort.experience + intLoss * 12 + 5);
        interceptor.experience = Math.min(500, interceptor.experience + escLoss * 12 + 5);

        boolean interceptorDestroyed = (interceptor.strength <= 0);
        if (interceptorDestroyed) {
            engine.units.remove(interceptor);
            int baseP = Math.max(1, iDef.cost / 10);
            int reward = (baseP * (interceptor.getStars() + 1)) / 2;
            if (escort.isAxis) engine.axisPrestige += reward; else engine.alliedPrestige += reward;
        }

        boolean escortDestroyed = (escort.strength <= 0);
        if (escortDestroyed) {
            engine.units.remove(escort);
            int baseP = Math.max(1, eDef.cost / 10);
            int reward = (baseP * (escort.getStars() + 1)) / 2;
            if (interceptor.isAxis) engine.axisPrestige += reward; else engine.alliedPrestige += reward;
        }

        return PgGameEngine.CombatReport.builder()
                .attackerName(eDef.name)
                .defenderName(iDef.name)
                .attackerId(escort.instanceId)
                .defenderId(interceptor.instanceId)
                .attackerIconId(engine.getEffectiveIconId(escort))
                .defenderIconId(engine.getEffectiveIconId(interceptor))
                .attackerStartStr(escort.strength + escLoss)
                .defenderStartStr(origIntStr)
                .attackerLosses(escLoss)
                .defenderLosses(intLoss)
                .defenderSuppressed(intSupp)
                .attackerDestroyed(escortDestroyed)
                .defenderDestroyed(interceptorDestroyed)
                .targetCol(interceptor.col)
                .targetRow(interceptor.row)
                .supportUnit(escort)
                .isEscortDogfight(true)
                .build();
    }

    private PgDatabase.ActiveUnit findAirInterceptorOrFlak(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        if (!engine.isUnitOnAirLayer(attacker)) return null;
        PgDatabase.UnitDef dDef = engine.getEffectiveUnitDef(defender);
        if (!engine.isUnitOnAirLayer(defender) || dDef.unitClass != PgDatabase.CLASS_FIGHTER) {
            for (int[] nb : engine.getNeighbors(defender.col, defender.row)) {
                PgDatabase.ActiveUnit adjAir = engine.getUnitAt(nb[0], nb[1], true);
                if (adjAir != null && adjAir.isAxis == defender.isAxis && adjAir.strength > 0
                        && (!engine.supplyEnabled || (adjAir.ammo > 0 && adjAir.fuel > 0))) {
                    PgDatabase.UnitDef fDef = engine.getEffectiveUnitDef(adjAir);
                    if (fDef.unitClass == PgDatabase.CLASS_FIGHTER) {
                        return adjAir;
                    }
                }
            }
        }
        if (!engine.isUnitOnAirLayer(defender)) {
            for (int[] nb : engine.getNeighbors(defender.col, defender.row)) {
                PgDatabase.ActiveUnit adjGnd = engine.getUnitAt(nb[0], nb[1], false);
                if (adjGnd != null && adjGnd.isAxis == defender.isAxis && adjGnd.strength > 0
                        && (!engine.supplyEnabled || adjGnd.ammo > 0)) {
                    PgDatabase.UnitDef adDef = engine.getEffectiveUnitDef(adjGnd);
                    if (adDef.unitClass == PgDatabase.CLASS_AIR_DEFENSE && adDef.range >= 2) {
                        return adjGnd;
                    }
                }
            }
        }
        return null;
    }

    public PgDatabase.ActiveUnit findDefensiveSupport(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        if (attacker == null || defender == null) return null;
        if (!engine.isUnitOnAirLayer(attacker) && !engine.isUnitOnAirLayer(defender)) {
            PgDatabase.ActiveUnit bestSupport = null;
            int bestStr = -1;
            for (int[] nb : engine.getNeighbors(defender.col, defender.row)) {
                PgDatabase.ActiveUnit adj = engine.getUnitAt(nb[0], nb[1], false);
                if (adj != null && adj.isAxis == defender.isAxis && (!engine.supplyEnabled || adj.ammo > 0) && adj.strength > 0) {
                    PgDatabase.UnitDef adjDef = engine.getEffectiveUnitDef(adj);
                    if (adjDef != null && (adjDef.unitClass == PgDatabase.CLASS_ARTILLERY || adjDef.isNaval()) && adjDef.range >= 2) {
                        int distToAtk = PgGameEngine.hexDistance(adj.col, adj.row, attacker.col, attacker.row);
                        if (distToAtk <= adjDef.range) {
                            int firingStr = getEffectiveFiringStrength(adj, adjDef, true);
                            if (firingStr > bestStr) {
                                bestStr = firingStr;
                                bestSupport = adj;
                            }
                        }
                    }
                }
            }
            if (bestStr > 0) return bestSupport;
        }
        return null;
    }

    public PgGameEngine.CombatReport resolveDefensiveSupportFire(PgDatabase.ActiveUnit support, PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        if (support == null || attacker == null) return null;
        PgDatabase.UnitDef sDef = engine.getEffectiveUnitDef(support);
        PgDatabase.UnitDef aDef = engine.getEffectiveUnitDef(attacker);
        if (sDef == null || aDef == null) return null;

        if (engine.supplyEnabled && sDef.maxAmmo > 0) {
            support.ammo = Math.max(0, support.ammo - 1);
        }

        int distToAttacker = PgGameEngine.hexDistance(support.col, support.row, attacker.col, attacker.row);
        int sAtkGrade = getStrategyGuideAttackGrade(support, sDef, attacker, aDef, distToAttacker, false);
        int aDefGrade = getStrategyGuideDefenseGrade(support, sDef, attacker, aDef, distToAttacker, false);
        int sEffDiff = calculateEffectiveDifference(sAtkGrade, aDefGrade);
        int sStr = getEffectiveFiringStrength(support, sDef, true);

        int sKills = 0;
        int sSupp = 0;
        if (sStr > 0 && sAtkGrade > 0) {
            int[] sRes = resolveD20Fire(sStr, sEffDiff, true);
            sKills = Math.min(attacker.strength, sRes[0]);
            sSupp = Math.min(attacker.strength - sKills, sRes[1]);
        }

        int startStr = attacker.strength;
        attacker.strength = Math.max(0, attacker.strength - sKills);
        attacker.suppression = Math.min(attacker.strength, attacker.suppression + sSupp);
        support.experience = Math.min(500, support.experience + sKills * 12 + 5);

        boolean attackerKilled = (attacker.strength <= 0);
        if (attackerKilled) {
            engine.units.remove(attacker);
            int atkBasePrestige = Math.max(1, aDef.cost / 10);
            int atkFullReward = (atkBasePrestige * (attacker.getStars() + 1)) / 2;
            if (support.isAxis) engine.axisPrestige += atkFullReward;
            else engine.alliedPrestige += atkFullReward;
        }

        return PgGameEngine.CombatReport.builder()
                .attackerName(sDef.name)
                .defenderName(aDef.name)
                .attackerId(support.instanceId)
                .defenderId(attacker.instanceId)
                .attackerIconId(engine.getEffectiveIconId(support))
                .defenderIconId(engine.getEffectiveIconId(attacker))
                .attackerStartStr(support.strength)
                .defenderStartStr(startStr)
                .attackerLosses(0)
                .defenderLosses(sKills)
                .defenderSuppressed(sSupp)
                .defenderDestroyed(attackerKilled)
                .targetCol(attacker.col)
                .targetRow(attacker.row)
                .supportUnit(support)
                .isDefensiveSupport(true)
                .build();
    }
}
