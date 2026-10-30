package com.ssi.panzergeneral;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages campaign progression, scenario initialization, weather updates,
 * turn advancement, and victory condition evaluation.
 */
public class PgCampaignManager {
    private final PgGameEngine engine;
    private final PgDatabase db;

    public PgCampaignManager(PgGameEngine engine) {
        this.engine = engine;
        this.db = engine.db;
    }

    public void startCampaign(int campIdx) throws IOException {
        engine.isCampaignMode = true;
        engine.campaignIndex = campIdx;
        engine.coreUnits.clear();
        int startScen = PgGameEngine.CAMPAIGN_START_SCENARIOS[campIdx % PgGameEngine.CAMPAIGN_START_SCENARIOS.length];
        loadScenario(startScen, false);
        engine.axisPrestige = (PgGameEngine.CAMPAIGN_START_PRESTIGE[campIdx % PgGameEngine.CAMPAIGN_START_PRESTIGE.length] * engine.prestigeModifier) / 100;
    }

    public void startSingleScenario(int scenNum) throws IOException {
        engine.isCampaignMode = false;
        engine.coreUnits.clear();
        loadScenario(scenNum, false);
    }

    public void loadScenario(int scenNum, boolean carryOverCore) throws IOException {
        engine.currentScenarioNumber = scenNum;
        engine.scenario = db.loadScenario(scenNum);
        engine.turn = 1;
        engine.maxTurns = Math.max(10, engine.scenario.meta.turns);
        engine.weather = 0;
        engine.groundCondition = 0;
        engine.wetness = 0;
        engine.axisCapturedHexes.clear();
        engine.alliedCapturedHexes.clear();
        engine.axisLostHexes.clear();
        engine.alliedLostHexes.clear();
        engine.isPlayerTurn = true;
        engine.scenarioOutcome = 0;
        engine.lastUndoState = null;
        engine.lastSurpriseReport = null;

        if (!carryOverCore) {
            engine.axisPrestige = (engine.scenario.meta.axisPrestige * engine.prestigeModifier) / 100;
        } else {
            engine.axisPrestige += (Math.max(250, engine.scenario.meta.axisPrestige / 2) * engine.prestigeModifier) / 100;
        }
        engine.alliedPrestige = engine.scenario.meta.alliedPrestige;

        engine.units.clear();
        engine.unplacedCoreUnits.clear();

        boolean hasExistingCoreArmy = carryOverCore && !engine.coreUnits.isEmpty();

        // Remove any destroyed core units from previous battle
        if (hasExistingCoreArmy) {
            engine.coreUnits.removeIf(u -> u.strength <= 0);
        }

        for (PgDatabase.ActiveUnit u : engine.scenario.initialUnits) {
            if (u == null) continue;
            PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
            if (hasExistingCoreArmy && u.isAxis && u.isCore) {
                if (def != null && def.isNaval()) {
                    // Naval units cannot be bought or carried over in campaign; supply them as scenario auxiliaries!
                    u.isCore = false;
                } else {
                    // In campaign continuation, placeholder core units from scenario file are replaced by player's carried-over core army!
                    continue;
                }
            }
            engine.units.add(u);
            engine.ensureNextInstanceIdAtLeast(u.instanceId + 1);
            if (engine.isCampaignMode && u.isAxis && u.isCore && engine.coreUnits.size() < 40 && !hasExistingCoreArmy) {
                if (def != null && def.isNaval()) {
                    // Naval units are scenario-specific auxiliaries and do not persist in ground core army
                    u.isCore = false;
                } else {
                    if (u.strength < 10) u.strength = 10;
                    engine.coreUnits.add(u);
                }
            }
        }

        // If carrying over core units from previous campaign battle, populate deployment reserve pool
        if (hasExistingCoreArmy) {
            engine.isDeploymentPhase = true;
            for (PgDatabase.ActiveUnit core : engine.coreUnits) {
                if (core == null) continue;
                PgDatabase.UnitDef def = db.equipment.get(core.unitDefId);
                core.movePointsUsed = 0;
                core.moved = false;
                core.attacked = false;
                core.suppression = 0;
                core.mountedInTransport = false;
                core.inSeaTransport = false;
                core.inAirTransport = false;
                core.entrenchment = 0;
                core.fuel = (def != null && def.maxFuel > 0) ? def.maxFuel : 99;
                core.ammo = (def != null && def.maxAmmo > 0) ? def.maxAmmo : 99;
                // Free replenishment between battles: any core unit below 10 strength moves to 10; >= 10 stays unchanged
                if (core.strength < 10) core.strength = 10;
                core.col = -1;
                core.row = -1;
                engine.unplacedCoreUnits.add(core);
            }
            engine.sortUnplacedCoreUnits();
        } else {
            engine.isDeploymentPhase = false;
        }

        engine.visibleHexes = new boolean[engine.scenario.cols][engine.scenario.rows];
        engine.recalculateFogOfWar();
    }

    /**
     * Advances the turn counter after AI execution, updates weather, awards continuing
     * hex control prestige, updates entrenchment, resets player units, and checks air fuel crashes.
     */
    public void advanceTurnAfterAi() {
        engine.turn++;
        updateWeather();

        // Strategy Guide Chapter 6: Continuing Control of Hexes
        // Offensive player: +4 per objective, +1 per normal city/port/airfield
        // Defending player: +8 per objective, +4 per normal city/port/airfield
        if (engine.scenario != null) {
            boolean axisDefending = engine.scenario.isDefensiveScenario;
            for (int c = 0; c < engine.scenario.cols; c++) {
                for (int r = 0; r < engine.scenario.rows; r++) {
                    PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                    boolean isCityPortAirfield = (cell.terrainType == 8 || cell.terrainType == 6 || cell.isPort());
                    if (isCityPortAirfield || cell.isObjective) {
                        boolean isAxisOwner = db.isAxisNation(cell.ownerNation, engine.scenario);
                        if (isAxisOwner) {
                            if (cell.isObjective) {
                                engine.axisPrestige += axisDefending ? 8 : 4;
                            } else {
                                engine.axisPrestige += axisDefending ? 4 : 1;
                            }
                        } else if (cell.ownerNation > 0) {
                            if (cell.isObjective) {
                                engine.alliedPrestige += axisDefending ? 4 : 8;
                            } else {
                                engine.alliedPrestige += axisDefending ? 1 : 4;
                            }
                        }
                    }
                }
            }
        }

        List<PgDatabase.ActiveUnit> crashedAirUnits = new ArrayList<>();
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u == null || u.strength <= 0) continue;
            if (u.unitDefId <= 0 || u.unitDefId >= db.equipment.size()) continue;
            PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
            if (def == null) continue;
            if (u.movePointsUsed == 0 && !u.moved && !u.attacked && !engine.isUnitOnAirLayer(u) && !u.inSeaTransport && !def.isNaval()) {
                int maxEnt = engine.scenario.grid[u.col][u.row].getBaseEntrenchment() + 3;
                if (u.entrenchment < maxEnt) u.entrenchment++;
            }
            if (u.isAxis) {
                u.movePointsUsed = 0;
                u.moved = false;
                u.attacked = false;
                u.suppression = Math.max(0, u.suppression - 3);
                // Automatic air resupply when over friendly airfield or adjacent to friendly Carrier
                if (!engine.supplyEnabled) {
                    u.fuel = def.maxFuel > 0 ? def.maxFuel : 99;
                    u.ammo = def.maxAmmo > 0 ? def.maxAmmo : 99;
                } else if (def.isAir()) {
                    boolean onFriendlyAirfield = (engine.scenario.grid[u.col][u.row].terrainType == 6
                            && db.isAxisNation(engine.scenario.grid[u.col][u.row].ownerNation, engine.scenario));
                    boolean nearCarrier = false;
                    for (int[] nb : engine.getNeighbors(u.col, u.row)) {
                        PgDatabase.ActiveUnit sea = engine.getUnitAt(nb[0], nb[1], false);
                        if (sea != null && sea.isAxis && sea.unitDefId > 0 && sea.unitDefId < db.equipment.size()
                                && db.equipment.get(sea.unitDefId).unitClass == PgDatabase.CLASS_CARRIER) {
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
                        crashedAirUnits.add(u);
                    }
                }
            }
        }
        if (!crashedAirUnits.isEmpty()) {
            engine.units.removeAll(crashedAirUnits);
        }

        engine.isPlayerTurn = true;
        engine.recalculateFogOfWar();
        checkVictoryConditions();
    }

    public void updateWeather() {
        if (!engine.weatherEnabled) {
            engine.weather = 0;
            engine.groundCondition = 0;
            engine.wetness = 0;
            return;
        }
        int roll = engine.rng.nextInt(100);
        if (roll < 72) {
            engine.weather = 0; // Clear
            engine.wetness = Math.max(0, engine.wetness - 2);
        } else if (roll < 90) {
            engine.weather = 1; // Overcast
            engine.wetness = Math.max(0, engine.wetness - 1);
        } else {
            engine.weather = 2; // Rain/Storm
            engine.wetness = Math.min(5, engine.wetness + 2);
        }

        // Strategy Guide Chapter 3: wetness >= 3 creates Mud/Snow; <= 2 is Dry
        if (engine.wetness >= 3) {
            engine.groundCondition = 1; // Mud / Snow
        } else {
            engine.groundCondition = 0; // Dry
        }
    }

    public void checkVictoryConditions() {
        if (engine.scenario == null || engine.scenario.objectives.isEmpty()) return;
        boolean allAxis = true;
        int axisCount = 0;
        for (int[] obj : engine.scenario.objectives) {
            PgDatabase.HexCell cell = engine.scenario.grid[obj[0]][obj[1]];
            if (db.isAxisNation(cell.ownerNation, engine.scenario)) {
                axisCount++;
            } else {
                allAxis = false;
            }
        }

        if (allAxis) {
            int turnsLeft = engine.maxTurns - engine.turn;
            engine.scenarioOutcome = (turnsLeft >= engine.scenario.meta.decisiveTurnsRemaining) ? 1 : 2;
        } else if (engine.turn > engine.maxTurns) {
            if (axisCount >= Math.max(1, (engine.scenario.objectives.size() * 3) / 4)) {
                engine.scenarioOutcome = 2; // Marginal Victory if most objectives held at time limit
            } else {
                engine.scenarioOutcome = 3; // Loss
            }
        } else {
            engine.scenarioOutcome = 0;
        }
    }

    public String getWeatherLabel() {
        if (!engine.weatherEnabled) return "Clear / Dry (Fixed)";
        String w = (engine.weather == 0) ? "Clear" : (engine.weather == 1 ? "Overcast" : "Storm");
        String g = (engine.groundCondition == 0) ? "Dry" : (engine.groundCondition == 1 ? "Mud" : "Frozen");
        return w + " / " + g;
    }
}
