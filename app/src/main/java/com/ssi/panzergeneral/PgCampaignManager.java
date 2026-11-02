package com.ssi.panzergeneral;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages campaign progression, authentic 1994 CAMPAIGN.DAT 38-scenario branching tree,
 * scenario initialization, weather updates, turn advancement, and victory condition evaluation.
 */
public class PgCampaignManager {

    public enum CampaignEndType {
        NONE,               // Campaign continues to next scenario (deterministic or player choice)
        DECISIVE_VICTORY,   // DWIN — Supreme Axis World Victory (Washington conquered before A-bomb)
        MAJOR_VICTORY,      // MWIN — Axis Campaign Victory / Favorable Peace Treaty
        ARMISTICE_DRAW,     // DRAW — Negotiated Armistice / Axis Survival
        HONORABLE_RETIRE,   // Honorable Retirement after defeat following prior front victory
        DEFEAT              // LOSS — Relieved of Command / Total Defeat
    }

    public static class BranchOption {
        public final int scenarioNumber;
        public final String scenarioTitle;
        public final String optionLabel;
        public final int prestigeCost;

        public BranchOption(int scenarioNumber, String scenarioTitle, String optionLabel, int prestigeCost) {
            this.scenarioNumber = scenarioNumber;
            this.scenarioTitle = scenarioTitle;
            this.optionLabel = optionLabel;
            this.prestigeCost = prestigeCost;
        }
    }

    public static class CampaignStepResult {
        public final int scenarioNumber;
        public final int outcome; // 1 = Major/Decisive Victory, 2 = Minor/Marginal Victory, 3 = Loss
        public final int bonusPrestige;
        public final String outcomeBriefingText;
        public final int choiceBriefingId;
        public final String choiceBriefingText;
        public final List<BranchOption> options;
        public final CampaignEndType endType;
        public final boolean setsWestDefeated;
        public final boolean setsEastDefeated;
        public final boolean nextLowCountriesLost;

        public CampaignStepResult(int scenarioNumber,
                                  int outcome,
                                  int bonusPrestige,
                                  String outcomeBriefingText,
                                  int choiceBriefingId,
                                  String choiceBriefingText,
                                  List<BranchOption> options,
                                  CampaignEndType endType,
                                  boolean setsWestDefeated,
                                  boolean setsEastDefeated,
                                  boolean nextLowCountriesLost) {
            this.scenarioNumber = scenarioNumber;
            this.outcome = outcome;
            this.bonusPrestige = bonusPrestige;
            this.outcomeBriefingText = outcomeBriefingText != null ? outcomeBriefingText : "";
            this.choiceBriefingId = choiceBriefingId;
            this.choiceBriefingText = choiceBriefingText != null ? choiceBriefingText : "";
            this.options = options != null ? Collections.unmodifiableList(options) : Collections.emptyList();
            this.endType = endType;
            this.setsWestDefeated = setsWestDefeated;
            this.setsEastDefeated = setsEastDefeated;
            this.nextLowCountriesLost = nextLowCountriesLost;
        }

        public boolean hasNextScenario() {
            return !options.isEmpty();
        }

        public boolean requiresPlayerChoice() {
            return options.size() > 1;
        }
    }

    private final PgGameEngine engine;
    private final PgDatabase db;
    public final List<PgDatabase.ActiveUnit> lastTurnCrashedUnits = new ArrayList<>();

    public PgCampaignManager(PgGameEngine engine) {
        this.engine = engine;
        this.db = engine.db;
    }

    public void startCampaign(int campIdx) throws IOException {
        engine.isCampaignMode = true;
        engine.playerIsAxis = true;
        engine.campaignIndex = campIdx;
        engine.westDefeated = false;
        engine.eastDefeated = false;
        engine.lowCountriesLost = false;
        engine.coreUnits.clear();
        int startScen = PgGameEngine.CAMPAIGN_START_SCENARIOS[campIdx % PgGameEngine.CAMPAIGN_START_SCENARIOS.length];
        loadScenario(startScen, false);
        engine.axisPrestige = (PgGameEngine.CAMPAIGN_START_PRESTIGE[campIdx % PgGameEngine.CAMPAIGN_START_PRESTIGE.length] * engine.prestigeModifier) / 100;
    }

    public void startSingleScenario(int scenNum) throws IOException {
        startSingleScenario(scenNum, true);
    }

    public void startSingleScenario(int scenNum, boolean playAsAxis) throws IOException {
        engine.playerIsAxis = playAsAxis;
        engine.isCampaignMode = false;
        engine.westDefeated = false;
        engine.eastDefeated = false;
        engine.lowCountriesLost = false;
        engine.coreUnits.clear();
        loadScenario(scenNum, false);
    }

    public void loadScenario(int scenNum, boolean carryOverCore) throws IOException {
        engine.currentScenarioNumber = scenNum;
        engine.scenario = db.loadScenario(scenNum);
        if (engine.isCampaignMode && scenNum == 5 && engine.scenario.meta != null) {
            // Authentic France_I (BDB0017+BDB0173) vs France_II (BDB0022+BDB0023) campaign briefing
            String franceBrf = engine.lowCountriesLost ? db.readBriefing(22, 23) : db.readBriefing(17, 173);
            if (!franceBrf.isEmpty()) {
                engine.scenario.meta.briefingText = franceBrf;
            }
        }
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

        if (engine.playerIsAxis) {
            if (!carryOverCore) {
                engine.axisPrestige = (engine.scenario.meta.axisPrestige * engine.prestigeModifier) / 100;
            } else {
                engine.axisPrestige += (Math.max(250, engine.scenario.meta.axisPrestige / 2) * engine.prestigeModifier) / 100;
            }
            engine.alliedPrestige = engine.scenario.meta.alliedPrestige;
        } else {
            engine.alliedPrestige = (engine.scenario.meta.alliedPrestige * engine.prestigeModifier) / 100;
            engine.axisPrestige = engine.scenario.meta.axisPrestige;
        }

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
            if (!engine.playerIsAxis && !engine.isCampaignMode && !u.isAxis && !def.isNaval()) { u.isCore = true; }
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
            if (engine.isPlayerUnit(u)) {
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
                            && (db.isAxisNation(engine.scenario.grid[u.col][u.row].ownerNation, engine.scenario) == u.isAxis));
                    boolean nearCarrier = false;
                    for (int[] nb : engine.getNeighbors(u.col, u.row)) {
                        PgDatabase.ActiveUnit sea = engine.getUnitAt(nb[0], nb[1], false);
                        if (sea != null && sea.isAxis == u.isAxis && sea.unitDefId > 0 && sea.unitDefId < db.equipment.size()
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
        lastTurnCrashedUnits.clear();
        if (!crashedAirUnits.isEmpty()) {
            lastTurnCrashedUnits.addAll(crashedAirUnits);
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

    private boolean axisOwnsHex(int col, int row) {
        if (engine.scenario == null || col < 0 || col >= engine.scenario.cols
                || row < 0 || row >= engine.scenario.rows) {
            return false;
        }
        return db.isAxisNation(engine.scenario.grid[col][row].ownerNation, engine.scenario);
    }

    /**
     * Evaluates authentic 1994 SSI Panzer General scenario victory conditions
     * (CAMPAIGN.TXT & BDB0001..BDB0177) for all 38 offensive and defensive scenarios.
     */
    public void checkVictoryConditions() {
        if (engine.scenario == null || engine.scenario.objectives.isEmpty()) return;
        boolean allAxis = true;
        boolean allAllied = true;
        int axisCount = 0;
        int alliedCount = 0;
        for (int[] obj : engine.scenario.objectives) {
            if (axisOwnsHex(obj[0], obj[1])) {
                axisCount++;
                allAllied = false;
            } else {
                alliedCount++;
                allAxis = false;
            }
        }

        int axisOutcome = evaluateAxisOutcome(allAxis, axisCount);
        if (engine.playerIsAxis) {
            engine.scenarioOutcome = axisOutcome;
        } else {
            // Player is playing ALLIED
            if (allAllied) {
                int turnsLeft = engine.maxTurns - engine.turn;
                engine.scenarioOutcome = (turnsLeft >= engine.scenario.meta.decisiveTurnsRemaining) ? 1 : 2;
            } else if (allAxis) {
                engine.scenarioOutcome = 3;
            } else if (engine.turn > engine.maxTurns) {
                if (axisOutcome == 1 || axisOutcome == 2) {
                    engine.scenarioOutcome = 3;
                } else {
                    if (engine.scenario.isDefensiveScenario) {
                        engine.scenarioOutcome = (axisCount <= 1) ? 1 : 2;
                    } else {
                        engine.scenarioOutcome = (alliedCount >= (engine.scenario.objectives.size() + 1) / 2) ? 1 : 2;
                    }
                }
            } else {
                engine.scenarioOutcome = 0;
            }
        }
    }

    private int evaluateAxisOutcome(boolean allAxis, int axisCount) {
        int scen = engine.currentScenarioNumber;
        switch (scen) {
            case 12: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    boolean holdsTunis = axisOwnsHex(27, 5);
                    return (holdsTunis && axisCount >= 6) ? 1 : ((holdsTunis && axisCount >= 3) ? 2 : 3);
                }
                return 0;
            }
            case 13: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    return (axisCount >= 5) ? 1 : ((axisCount >= 2) ? 2 : 3);
                }
                return 0;
            }
            case 14: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    boolean holdsRome = axisOwnsHex(13, 17);
                    boolean holdsAnzio = axisOwnsHex(15, 25);
                    return (holdsRome && holdsAnzio && axisCount >= 5) ? 1 : ((holdsRome && axisCount >= 3) ? 2 : 3);
                }
                return 0;
            }
            case 15: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    int towns = (axisOwnsHex(22, 28) ? 1 : 0) + (axisOwnsHex(43, 27) ? 1 : 0) + (axisOwnsHex(11, 7) ? 1 : 0);
                    return (towns >= 3) ? 1 : ((towns >= 2) ? 2 : 3);
                }
                return 0;
            }
            case 16: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    boolean holdsMarseilles = axisOwnsHex(12, 27);
                    return (holdsMarseilles && axisCount >= 3) ? 1 : ((axisCount >= 2) ? 2 : 3);
                }
                return 0;
            }
            case 17: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    boolean allExceptBrussels = true;
                    for (int[] obj : engine.scenario.objectives) {
                        if (!(obj[0] == 1 && obj[1] == 10) && !axisOwnsHex(obj[0], obj[1])) {
                            allExceptBrussels = false; break;
                        }
                    }
                    return allExceptBrussels ? 2 : 3;
                }
                return 0;
            }
            case 18: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    return (axisCount >= 4) ? 1 : ((axisCount >= 3) ? 2 : 3);
                }
                return 0;
            }
            case 19: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    boolean holdsArnhem = axisOwnsHex(37, 10) && axisOwnsHex(38, 11);
                    boolean holdsNijmegen = axisOwnsHex(29, 11);
                    return (holdsArnhem && holdsNijmegen) ? 1 : (holdsArnhem ? 2 : 3);
                }
                return 0;
            }
            case 20:
            case 34: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    boolean holdsBerlin = axisOwnsHex(36, 14);
                    return (holdsBerlin && axisCount >= 6) ? 2 : 3;
                }
                return 0;
            }
            case 30: {
                if (allAxis) {
                    int turnsLeft = engine.maxTurns - engine.turn;
                    return (turnsLeft >= engine.scenario.meta.decisiveTurnsRemaining) ? 1 : 2;
                } else if (engine.turn > engine.maxTurns) {
                    return (axisCount >= 1) ? 2 : 3;
                }
                return 0;
            }
            case 32: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    boolean holdsWarsaw = axisOwnsHex(3, 12);
                    return (holdsWarsaw && axisCount >= 3) ? 1 : (holdsWarsaw ? 2 : 3);
                }
                return 0;
            }
            case 35: {
                if (allAxis) return 1;
                if (engine.turn > engine.maxTurns) {
                    boolean holdsBerlin = axisOwnsHex(35, 14);
                    return (holdsBerlin && axisCount >= 6) ? 1 : (holdsBerlin ? 2 : 3);
                }
                return 0;
            }
            default: {
                if (allAxis) {
                    int turnsLeft = engine.maxTurns - engine.turn;
                    return (turnsLeft >= engine.scenario.meta.decisiveTurnsRemaining) ? 1 : 2;
                } else if (engine.turn > engine.maxTurns) {
                    return 3;
                }
                return 0;
            }
        }
    }

    private BranchOption option(int scenNum) {
        return option(scenNum, null, 0);
    }

    private BranchOption option(int scenNum, String customLabel, int prestigeCost) {
        String title = (scenNum >= 1 && scenNum <= db.scenarios.size())
                ? db.scenarios.get(scenNum - 1).fullTitle
                : ("Scenario #" + scenNum);
        String label = (customLabel != null && !customLabel.isEmpty()) ? customLabel : title;
        return new BranchOption(scenNum, title, label, prestigeCost);
    }

    private CampaignStepResult nextSingle(int scen, int outcome, int bonus, String outBrf,
                                          int nextScen, boolean setNoW, boolean setNoE, boolean lowLost) {
        List<BranchOption> opts = Collections.singletonList(option(nextScen));
        return new CampaignStepResult(scen, outcome, bonus, outBrf, 0, "",
                opts, CampaignEndType.NONE, setNoW, setNoE, lowLost);
    }

    private CampaignStepResult nextChoice(int scen, int outcome, int bonus, String outBrf,
                                          int choiceBrfId, BranchOption optA, BranchOption optB,
                                          boolean setNoW, boolean setNoE) {
        List<BranchOption> opts = new ArrayList<>(2);
        opts.add(optA);
        opts.add(optB);
        return new CampaignStepResult(scen, outcome, bonus, outBrf, choiceBrfId,
                db.readBriefing(choiceBrfId), opts, CampaignEndType.NONE, setNoW, setNoE, false);
    }

    private CampaignStepResult terminal(int scen, int outcome, int bonus, String outBrf,
                                        CampaignEndType endType, boolean setNoW, boolean setNoE) {
        return new CampaignStepResult(scen, outcome, bonus, outBrf, 0, "",
                Collections.emptyList(), endType, setNoW, setNoE, false);
    }

    /**
     * Resolves the authentic 1994 SSI Panzer General CAMPAIGN.DAT transition for the active scenario
     * and outcome (1 = Major/Decisive Victory, 2 = Minor/Marginal Victory, 3 = Loss), accounting for
     * [NoW] (westDefeated), [NoE] (eastDefeated), France_I/II, and High Command choice forks (BDB0162..0172).
     */
    public CampaignStepResult resolveScenarioOutcome(int outcome) {
        int scen = engine.currentScenarioNumber;
        PgDatabase.ScenarioMeta meta = (engine.scenario != null && engine.scenario.meta != null)
                ? engine.scenario.meta
                : db.scenarios.get(Math.max(0, Math.min(db.scenarios.size() - 1, scen - 1)));

        int rawBonus = (outcome == 1) ? meta.prestigeDecisive
                : ((outcome == 2) ? meta.prestigeMarginal : meta.prestigeLoss);
        int bonus = (rawBonus * engine.prestigeModifier) / 100;
        int treasury = engine.axisPrestige + bonus;

        boolean noW = engine.westDefeated;
        boolean noE = engine.eastDefeated;

        switch (scen) {
            case 1: // POLAND
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(2), 2, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(3), 2, false, false, false);
                return terminal(scen, outcome, 0, db.readBriefing(4), CampaignEndType.DEFEAT, false, false);

            case 2: // WARSAW
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(6), 3, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(7), 4, false, false, false);
                return terminal(scen, outcome, 0, db.readBriefing(21), CampaignEndType.DEFEAT, false, false);

            case 3: // NORWAY
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(9), 4, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(10), 4, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(11), 4, false, false, false);

            case 4: // LOW COUNTRIES
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(13), 5, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(14), 5, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(16, 15), 5, false, false, true);

            case 5: { // FRANCE
                if (outcome == 1) {
                    if (!engine.lowCountriesLost) {
                        int sealionPlusCost = (treasury >= 1000) ? 1000 : Math.max(0, treasury / 2);
                        return nextChoice(scen, outcome, bonus, db.readBriefing(18), 162,
                                option(6, "Operation Sealion (40) — Direct Channel Invasion", 0),
                                option(38, "Operation Sealion Plus — Invade Gibraltar First (-" + sealionPlusCost + " Prestige)", sealionPlusCost),
                                false, false);
                    } else {
                        return nextChoice(scen, outcome, bonus, db.readBriefing(19), 163,
                                option(7, "North Africa — Afrika Korps Offensive", 0),
                                option(21, "Balkans — Invasion of Yugoslavia & Greece", 0),
                                false, false);
                    }
                }
                if (outcome == 2) {
                    return nextChoice(scen, outcome, bonus, db.readBriefing(19), 163,
                            option(7, "North Africa — Afrika Korps Offensive", 0),
                            option(21, "Balkans — Invasion of Yugoslavia & Greece", 0),
                            false, false);
                }
                return terminal(scen, outcome, 0, db.readBriefing(20, 21), CampaignEndType.DEFEAT, false, false);
            }

            case 6:  // SEALION (40)
            case 38: // SEALION PLUS
                if (outcome == 1) {
                    return nextSingle(scen, outcome, bonus, db.readBriefing(26), 23, true, false, false);
                }
                if (outcome == 2) {
                    return nextChoice(scen, outcome, bonus, db.readBriefing(27), 163,
                            option(7, "North Africa — Afrika Korps Offensive", 0),
                            option(21, "Balkans — Invasion of Yugoslavia & Greece", 0),
                            false, false);
                }
                return terminal(scen, outcome, 0, db.readBriefing(28), CampaignEndType.DEFEAT, false, false);

            case 7: // NORTH AFRICA
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(30), 8, false, false, false);
                if (outcome == 2) {
                    return nextChoice(scen, outcome, bonus, db.readBriefing(31), 164,
                            option(9, "El Alamein — Drive on Alexandria & Cairo", 0),
                            option(24, "Kiev — Encirclement in the Ukraine", 0),
                            false, false);
                }
                return nextSingle(scen, outcome, bonus, db.readBriefing(32), 12, false, false, false);

            case 8: // MIDDLE EAST
                if (outcome == 1) {
                    return nextChoice(scen, outcome, bonus, db.readBriefing(34), 165,
                            option(11, "Sealion (43) — Summer Invasion of England", 0),
                            option(10, "Caucasus — Two-Front Offensive into Southern Russia", 0),
                            false, false);
                }
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(35), 10, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(36), 9, false, false, false);

            case 9: // EL ALAMEIN
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(38), 11, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(39), 12, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(40), 12, false, false, false);

            case 10: // CAUCASUS
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(42), 27, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(43), 29, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(44), 29, false, false, false);

            case 11: // SEALION (43)
                if (outcome == 1) {
                    if (noE) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(46), 36, true, false, false);
                    }
                    return nextSingle(scen, outcome, bonus, db.readBriefing(47), 31, true, false, false);
                }
                if (outcome == 2) {
                    if (noE) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(117), 14, false, false, false);
                    }
                    return nextSingle(scen, outcome, bonus, db.readBriefing(48), 31, false, false, false);
                }
                return nextSingle(scen, outcome, bonus, db.readBriefing(49), 14, false, false, false);

            case 12: // TORCH
                if (outcome == 1) {
                    return nextChoice(scen, outcome, bonus, db.readBriefing(51), 166,
                            option(13, "Husky — Defense of Sicily & Southern Italy", 0),
                            option(30, "Kursk — Operation Citadel on the Eastern Front", 0),
                            false, false);
                }
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(52), 13, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(53), 13, false, false, false);

            case 13: // HUSKY
                if (outcome == 1) {
                    if (noE) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(56), 14, false, false, false);
                    }
                    return nextSingle(scen, outcome, bonus, db.readBriefing(56), 31, false, false, false);
                }
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(57), 14, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(58), 14, false, false, false);

            case 14: // ANZIO
                if (outcome == 1) {
                    if (noE) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(60), 15, false, false, false);
                    }
                    return nextChoice(scen, outcome, bonus, db.readBriefing(61), 167,
                            option(15, "D-Day — Repel the Allied Invasion of France", 0),
                            option(32, "Byelorussia — Stem the Soviet Summer Offensive", 0),
                            false, false);
                }
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(62), 15, false, false, false);
                if (noE) {
                    return terminal(scen, outcome, 0, db.readBriefing(63), CampaignEndType.HONORABLE_RETIRE, false, false);
                }
                return terminal(scen, outcome, 0, db.readBriefing(64), CampaignEndType.DEFEAT, false, false);

            case 15: // D-DAY
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(66), 16, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(67), 18, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(68), 18, false, false, false);

            case 16: // ANVIL
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(70), 17, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(71), 19, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(72), 19, false, false, false);

            case 17: // ARDENNES
                if (outcome == 1) {
                    if (noE) {
                        return terminal(scen, outcome, bonus, db.readBriefing(74, 75), CampaignEndType.MAJOR_VICTORY, true, false);
                    }
                    return nextSingle(scen, outcome, bonus, db.readBriefing(74, 76, 78), 33, true, false, false);
                }
                if (outcome == 2) {
                    return nextSingle(scen, outcome, bonus, db.readBriefing(77), noE ? 20 : 35, false, false, false);
                }
                return nextSingle(scen, outcome, bonus, db.readBriefing(79), noE ? 20 : 35, false, false, false);

            case 18: // COBRA
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(81), 17, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(71), 19, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(83), 19, false, false, false);

            case 19: // MARKET-GARDEN
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(85), 17, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(86), 17, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(87), noE ? 20 : 35, false, false, false);

            case 20: // BERLIN (WEST)
                if (outcome == 1) return terminal(scen, outcome, bonus, db.readBriefing(89), CampaignEndType.MAJOR_VICTORY, false, false);
                if (outcome == 2) return terminal(scen, outcome, bonus, db.readBriefing(90), CampaignEndType.ARMISTICE_DRAW, false, false);
                return terminal(scen, outcome, 0, db.readBriefing(91), CampaignEndType.DEFEAT, false, false);

            case 21: // BALKANS
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(93), 22, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(94), 23, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(95), 23, false, false, false);

            case 22: // CRETE
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(97), 23, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(98), 23, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(99), 23, false, false, false);

            case 23: { // BARBAROSSA
                if (outcome == 1) {
                    int earlyMoscowCost = Math.min(1500, Math.max(0, treasury / 2));
                    return nextChoice(scen, outcome, bonus, db.readBriefing(101), 168,
                            option(24, "Kiev — Encircle Soviet Forces in the Ukraine", 0),
                            option(37, "Early Moscow — Immediate Drive on Moscow (-" + earlyMoscowCost + " Prestige)", earlyMoscowCost),
                            false, false);
                }
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(102), 24, false, false, false);
                if (noW) {
                    return terminal(scen, outcome, 0, db.readBriefing(103, 123), CampaignEndType.HONORABLE_RETIRE, false, false);
                }
                return terminal(scen, outcome, 0, db.readBriefing(103, 21), CampaignEndType.DEFEAT, false, false);
            }

            case 24: // KIEV
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(105), 25, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(106), 25, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(107), 26, false, false, false);

            case 25: // MOSCOW (41)
            case 37: // EARLY MOSCOW
                if (outcome == 1) {
                    if (noW) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(109), 36, false, true, false);
                    }
                    return nextSingle(scen, outcome, bonus, db.readBriefing(110), 11, false, true, false);
                }
                if (outcome == 2) {
                    if (noW) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(111), 26, false, false, false);
                    }
                    return nextChoice(scen, outcome, bonus, db.readBriefing(111), 169,
                            option(26, "Sevastopol — Storm the Crimean Fortress", 0),
                            option(9, "El Alamein — Capture Alexandria & Cairo", 0),
                            false, false);
                }
                return nextSingle(scen, outcome, bonus, db.readBriefing(112), 26, false, false, false);

            case 26: // SEVASTOPOL
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(114), 28, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(115), 28, false, false, false);
                if (noW) {
                    return terminal(scen, outcome, 0, db.readBriefing(116, 123), CampaignEndType.HONORABLE_RETIRE, false, false);
                }
                return terminal(scen, outcome, 0, db.readBriefing(116, 21), CampaignEndType.DEFEAT, false, false);

            case 27: // MOSCOW (42)
                if (outcome == 1) {
                    if (noW) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(119), 36, false, true, false);
                    }
                    return nextSingle(scen, outcome, bonus, db.readBriefing(120), 11, false, true, false);
                }
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(121), 29, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(122), 29, false, false, false);

            case 28: // STALINGRAD
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(42), 27, false, false, false);
                if (outcome == 2) {
                    if (noW) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(43), 29, false, false, false);
                    }
                    return nextChoice(scen, outcome, bonus, db.readBriefing(43), 170,
                            option(29, "Kharkov — Eastern Front Counteroffensive", 0),
                            option(13, "Husky — Defense of Sicily & Southern Italy", 0),
                            false, false);
                }
                return nextSingle(scen, outcome, bonus, db.readBriefing(122), 29, false, false, false);

            case 29: // KHARKOV
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(128), 31, false, false, false);
                if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(129), 30, false, false, false);
                return nextSingle(scen, outcome, bonus, db.readBriefing(130), 32, false, false, false);

            case 30: // KURSK
                if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(132), 31, false, false, false);
                if (outcome == 2) {
                    if (noW) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(133), 32, false, false, false);
                    }
                    return nextChoice(scen, outcome, bonus, db.readBriefing(133), 171,
                            option(32, "Byelorussia — Defend the Eastern Front", 0),
                            option(14, "Anzio — Defend Rome on the Italian Front", 0),
                            false, false);
                }
                return nextSingle(scen, outcome, bonus, db.readBriefing(134), 32, false, false, false);

            case 31: // MOSCOW (43)
                if (outcome == 1) {
                    if (noW) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(119), 36, false, true, false);
                    }
                    return nextSingle(scen, outcome, bonus, db.readBriefing(120, 136), 15, false, true, false);
                }
                if (outcome == 2) {
                    if (noW) {
                        return nextSingle(scen, outcome, bonus, db.readBriefing(137), 32, false, false, false);
                    }
                    return nextChoice(scen, outcome, bonus, db.readBriefing(137), 172,
                            option(32, "Byelorussia — Defend the Eastern Front", 0),
                            option(15, "D-Day — Counter the Allied Invasion of France", 0),
                            false, false);
                }
                return nextSingle(scen, outcome, bonus, db.readBriefing(138), 32, false, false, false);

            case 32: // BYELORUSSIA
                if (noW) {
                    if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(140, 143), 33, false, false, false);
                    if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(142, 143), 33, false, false, false);
                    return nextSingle(scen, outcome, bonus, db.readBriefing(124), 34, false, false, false);
                } else {
                    if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(141), 17, false, false, false);
                    if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(141), 17, false, false, false);
                    return nextSingle(scen, outcome, bonus, db.readBriefing(124), 35, false, false, false);
                }

            case 33: // BUDAPEST
                if (noW) {
                    if (outcome == 1) {
                        return terminal(scen, outcome, bonus, db.readBriefing(144), CampaignEndType.MAJOR_VICTORY, false, true);
                    }
                    if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(146), 34, false, false, false);
                    return nextSingle(scen, outcome, bonus, db.readBriefing(147), 34, false, false, false);
                } else {
                    if (outcome == 1) return nextSingle(scen, outcome, bonus, db.readBriefing(145), 20, false, true, false);
                    if (outcome == 2) return nextSingle(scen, outcome, bonus, db.readBriefing(146), 35, false, false, false);
                    return nextSingle(scen, outcome, bonus, db.readBriefing(147), 35, false, false, false);
                }

            case 34: // BERLIN (EAST)
                if (outcome == 1) return terminal(scen, outcome, bonus, db.readBriefing(149), CampaignEndType.MAJOR_VICTORY, false, false);
                if (outcome == 2) return terminal(scen, outcome, bonus, db.readBriefing(90), CampaignEndType.ARMISTICE_DRAW, false, false);
                return terminal(scen, outcome, 0, db.readBriefing(91), CampaignEndType.DEFEAT, false, false);

            case 35: // BERLIN
                if (outcome == 1) return terminal(scen, outcome, bonus, db.readBriefing(150), CampaignEndType.ARMISTICE_DRAW, false, false);
                if (outcome == 2) return terminal(scen, outcome, bonus, db.readBriefing(151), CampaignEndType.ARMISTICE_DRAW, false, false);
                return terminal(scen, outcome, 0, db.readBriefing(91), CampaignEndType.DEFEAT, false, false);

            case 36: // WASHINGTON
                if (outcome == 1) return terminal(scen, outcome, bonus, db.readBriefing(153, 174), CampaignEndType.DECISIVE_VICTORY, false, false);
                if (outcome == 2) return terminal(scen, outcome, bonus, db.readBriefing(154, 174), CampaignEndType.MAJOR_VICTORY, false, false);
                return terminal(scen, outcome, 0, db.readBriefing(155), CampaignEndType.DEFEAT, false, false);

            default:
                return terminal(scen, outcome, bonus, "",
                        outcome <= 2 ? CampaignEndType.MAJOR_VICTORY : CampaignEndType.DEFEAT, false, false);
        }
    }

    /**
     * Commits a campaign transition after the player completes a scenario (and selects an option
     * if High Command offered a strategic choice), updating front flags, prestige, and loading the next map.
     */
    public void commitCampaignTransition(CampaignStepResult step, BranchOption chosen) throws IOException {
        if (step == null || chosen == null || chosen.scenarioNumber <= 0) return;
        if (step.setsWestDefeated) engine.westDefeated = true;
        if (step.setsEastDefeated) engine.eastDefeated = true;
        engine.lowCountriesLost = step.nextLowCountriesLost;
        engine.axisPrestige = Math.max(0, engine.axisPrestige + step.bonusPrestige - chosen.prestigeCost);
        loadScenario(chosen.scenarioNumber, true);
    }

    public String getWeatherLabel() {
        if (!engine.weatherEnabled) return "Clear / Dry (Fixed)";
        String w = (engine.weather == 0) ? "Clear" : (engine.weather == 1 ? "Overcast" : "Storm");
        String g = (engine.groundCondition == 0) ? "Dry" : (engine.groundCondition == 1 ? "Mud" : "Frozen");
        return w + " / " + g;
    }
}
