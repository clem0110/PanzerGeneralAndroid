package com.ssi.panzergeneral;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.*;

/**
 * Central game state coordinator and facade for Panzer General.
 *
 * Subsystem responsibilities are separated into dedicated components:
 * - {@link PgCampaignManager}: Campaign/scenario initialization, weather, turn progression & victory conditions
 * - {@link PgDeploymentManager}: Core deployment phase, besieged base blocking rules & unit purchasing
 * - {@link PgMovementSystem}: Hex navigation, Fog of War, ZOC, pathfinding, undo move, capture & transports
 * - {@link PgCombatSystem}: Target validation, Strategy Guide grades, D20 CRT, escorts, support & combat resolution
 * - {@link PgLogisticsManager}: Unit readiness, resupply, regular/elite replacements, upgrades & disbanding
 * - {@link PgAiController}: Multi-phase tactical and strategic Allied AI opponent
 * - {@link PgSaveManager}: Save/load serialization and slot import/export
 */
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
        public boolean isDefensiveSupport;
        public boolean isEscortDogfight;
        public CombatReport supportReport;
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
        public boolean prevAttacked;
        public boolean prevInSeaTransport;
        public boolean prevInAirTransport;
        public boolean prevMountedInTransport;
        public int prevEntrenchment;
        public boolean prevFacingLeft;
        public int destCol;
        public int destRow;
        public int prevDestOwnerNation;
        public int prevAxisPrestige;
        public boolean capturedNewHex;
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
        @Builder.Default
        public List<PgDatabase.ActiveUnit> unplacedCoreUnits = new ArrayList<>();
        public boolean isDeploymentPhase;
        public int[][] hexOwners;
        public boolean[][] fogVisible;
        public String saveTimestamp;
        public String scenarioTitle;
    }

    public interface AiTurnCallback {
        void onAiUnitMoved(PgDatabase.ActiveUnit unit, int fromCol, int fromRow, int toCol, int toRow);
        void onAiCombatExecuted(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender, CombatReport report);
    }

    public final PgDatabase db;

    // Extracted Subsystem Components
    public final PgCampaignManager campaignManager;
    public final PgDeploymentManager deploymentManager;
    public final PgMovementSystem movementSystem;
    public final PgCombatSystem combatSystem;
    public final PgLogisticsManager logisticsManager;
    public final PgAiController aiController;
    public final PgSaveManager saveManager;

    // Shared Scenario & Campaign State
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

    // Campaign Core Deployment Phase
    public boolean isDeploymentPhase = false;
    public final List<PgDatabase.ActiveUnit> unplacedCoreUnits = new CopyOnWriteArrayList<>();

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

    public final List<PgDatabase.ActiveUnit> units = new CopyOnWriteArrayList<>();
    public final List<PgDatabase.ActiveUnit> coreUnits = new CopyOnWriteArrayList<>();
    public boolean[][] visibleHexes;
    public UndoMoveState lastUndoState = null;
    public CombatReport lastSurpriseReport = null;

    final Random rng = new Random();
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
        this.campaignManager = new PgCampaignManager(this);
        this.deploymentManager = new PgDeploymentManager(this);
        this.movementSystem = new PgMovementSystem(this);
        this.combatSystem = new PgCombatSystem(this);
        this.logisticsManager = new PgLogisticsManager(this);
        this.aiController = new PgAiController(this);
        this.saveManager = new PgSaveManager(this);
    }

    int allocateInstanceId() {
        return nextInstanceId++;
    }

    void ensureNextInstanceIdAtLeast(int minId) {
        if (minId > nextInstanceId) {
            nextInstanceId = minId;
        }
    }

    // =========================================================================
    // CAMPAIGN, WEATHER & VICTORY FACADE (PgCampaignManager)
    // =========================================================================
    public void startCampaign(int campIdx) throws IOException {
        campaignManager.startCampaign(campIdx);
    }

    public void startSingleScenario(int scenNum) throws IOException {
        campaignManager.startSingleScenario(scenNum);
    }

    public void loadScenario(int scenNum, boolean carryOverCore) throws IOException {
        campaignManager.loadScenario(scenNum, carryOverCore);
    }

    public void checkVictoryConditions() {
        campaignManager.checkVictoryConditions();
    }

    public String getWeatherLabel() {
        return campaignManager.getWeatherLabel();
    }

    // =========================================================================
    // DEPLOYMENT & PURCHASING FACADE (PgDeploymentManager)
    // =========================================================================
    public boolean hasEnemyAdjacentOrOnHex(int col, int row, boolean isAxis) {
        return deploymentManager.hasEnemyAdjacentOrOnHex(col, row, isAxis);
    }

    public boolean isCityAirportOrSupply(PgDatabase.HexCell cell) {
        return deploymentManager.isCityAirportOrSupply(cell);
    }

    public boolean isHexBlockedByEnemyNearBase(int col, int row, boolean isAxis) {
        return deploymentManager.isHexBlockedByEnemyNearBase(col, row, isAxis);
    }

    public List<int[]> getValidCoreDeploymentHexes(PgDatabase.ActiveUnit u) {
        return deploymentManager.getValidCoreDeploymentHexes(u);
    }

    public boolean isHexValidForCoreDeployment(int col, int row) {
        return deploymentManager.isHexValidForCoreDeployment(col, row);
    }

    public boolean deployCoreUnit(PgDatabase.ActiveUnit u, int col, int row) {
        return deploymentManager.deployCoreUnit(u, col, row);
    }

    public void sortUnitsByTypeAndName(List<PgDatabase.ActiveUnit> list) {
        deploymentManager.sortUnitsByTypeAndName(list);
    }

    public void sortUnplacedCoreUnits() {
        deploymentManager.sortUnplacedCoreUnits();
    }

    public boolean undeployCoreUnit(PgDatabase.ActiveUnit u) {
        return deploymentManager.undeployCoreUnit(u);
    }

    public void startBattleAfterDeployment() {
        deploymentManager.startBattleAfterDeployment();
    }

    public List<int[]> getAvailableDeployHexes() {
        return deploymentManager.getAvailableDeployHexes();
    }

    public List<int[]> getAvailableDeployHexes(int unitDefId) {
        return deploymentManager.getAvailableDeployHexes(unitDefId);
    }

    public PgDatabase.ActiveUnit purchaseUnit(int unitDefId, int transportDefId, int col, int row) {
        return deploymentManager.purchaseUnit(unitDefId, transportDefId, col, row);
    }

    public List<int[]> getAvailableAiDeployHexes() {
        return deploymentManager.getAvailableAiDeployHexes();
    }

    public List<int[]> getAvailableAiDeployHexes(boolean isAir) {
        return deploymentManager.getAvailableAiDeployHexes(isAir);
    }

    public boolean isHexValidForAiSpawn(int col, int row, boolean isAir) {
        return deploymentManager.isHexValidForAiSpawn(col, row, isAir);
    }

    public PgDatabase.ActiveUnit purchaseAiUnit(int unitDefId, int col, int row) {
        return deploymentManager.purchaseAiUnit(unitDefId, col, row);
    }

    // =========================================================================
    // MOVEMENT, FOG OF WAR & TRANSPORT FACADE (PgMovementSystem)
    // =========================================================================
    public boolean isUnitOnAirLayer(PgDatabase.ActiveUnit u) {
        return movementSystem.isUnitOnAirLayer(u);
    }

    public PgDatabase.UnitDef getAirTransportDef() {
        return movementSystem.getAirTransportDef();
    }

    public PgDatabase.UnitDef getSeaTransportDef() {
        return movementSystem.getSeaTransportDef();
    }

    public PgDatabase.UnitDef getSeaTransportDef(boolean isAxis) {
        return movementSystem.getSeaTransportDef(isAxis);
    }

    public PgDatabase.UnitDef getEffectiveUnitDef(PgDatabase.ActiveUnit u) {
        return movementSystem.getEffectiveUnitDef(u);
    }

    public int getEffectiveIconId(PgDatabase.ActiveUnit u) {
        return movementSystem.getEffectiveIconId(u);
    }

    public boolean hasFriendlyBridgeEngineer(int col, int row, boolean isAxis) {
        return movementSystem.hasFriendlyBridgeEngineer(col, row, isAxis);
    }

    public boolean canUnitCaptureCityOrAirport(PgDatabase.ActiveUnit u) {
        return movementSystem.canUnitCaptureCityOrAirport(u);
    }

    public List<int[]> getNeighbors(int col, int row) {
        return movementSystem.getNeighbors(col, row);
    }

    public static int hexDistance(int c1, int r1, int c2, int r2) {
        return PgMovementSystem.hexDistance(c1, r1, c2, r2);
    }

    public PgDatabase.ActiveUnit getUnitAt(int col, int row, boolean airLayer) {
        return movementSystem.getUnitAt(col, row, airLayer);
    }

    public PgDatabase.ActiveUnit getTopUnitAt(int col, int row, boolean preferAir) {
        return movementSystem.getTopUnitAt(col, row, preferAir);
    }

    public boolean isUnitVisibleToPlayer(PgDatabase.ActiveUnit u) {
        return movementSystem.isUnitVisibleToPlayer(u);
    }

    public Set<Integer> getSpottedEnemyIds() {
        return movementSystem.getSpottedEnemyIds();
    }

    public void recalculateFogOfWar() {
        movementSystem.recalculateFogOfWar();
    }

    public int getMaxMovementPoints(PgDatabase.ActiveUnit u) {
        return movementSystem.getMaxMovementPoints(u);
    }

    public int getRemainingMovementPoints(PgDatabase.ActiveUnit u) {
        return movementSystem.getRemainingMovementPoints(u);
    }

    public Map<Integer, Integer> getReachableHexes(PgDatabase.ActiveUnit u) {
        return movementSystem.getReachableHexes(u);
    }

    public boolean isInEnemyZoc(int col, int row, PgDatabase.ActiveUnit mover) {
        return movementSystem.isInEnemyZoc(col, row, mover);
    }

    public boolean isInEnemyZoc(int col, int row, boolean moverIsAxis) {
        return movementSystem.isInEnemyZoc(col, row, moverIsAxis);
    }

    public boolean canUndoLastMove() {
        return movementSystem.canUndoLastMove();
    }

    public PgDatabase.ActiveUnit undoLastMove() {
        return movementSystem.undoLastMove();
    }

    public boolean moveUnit(PgDatabase.ActiveUnit u, int destCol, int destRow) {
        return movementSystem.moveUnit(u, destCol, destRow);
    }

    public boolean isRiverHex(int col, int row) {
        return movementSystem.isRiverHex(col, row);
    }

    public void onHexCaptured(int col, int row, PgDatabase.ActiveUnit capturer) {
        movementSystem.onHexCaptured(col, row, capturer);
    }

    public String getTransportActionLabel(PgDatabase.ActiveUnit u) {
        return movementSystem.getTransportActionLabel(u);
    }

    public boolean canUseTransportAction(PgDatabase.ActiveUnit u) {
        return movementSystem.canUseTransportAction(u);
    }

    public String executeTransportAction(PgDatabase.ActiveUnit u) {
        return movementSystem.executeTransportAction(u);
    }

    // =========================================================================
    // COMBAT PREDICTION & RESOLUTION FACADE (PgCombatSystem)
    // =========================================================================
    public List<PgDatabase.ActiveUnit> getValidTargets(PgDatabase.ActiveUnit attacker) {
        return combatSystem.getValidTargets(attacker);
    }

    public boolean canAttackTarget(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef, boolean aAir,
                                   PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef, boolean dAir, boolean dNaval, int dist) {
        return combatSystem.canAttackTarget(attacker, aDef, aAir, defender, dDef, dAir, dNaval, dist);
    }

    public int getStrategyGuideAttackGrade(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef,
                                           PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef,
                                           int dist, boolean stiffDefense) {
        return combatSystem.getStrategyGuideAttackGrade(attacker, aDef, defender, dDef, dist, stiffDefense);
    }

    public int getStrategyGuideDefenseGrade(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef,
                                            PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef,
                                            int dist, boolean stiffDefense) {
        return combatSystem.getStrategyGuideDefenseGrade(attacker, aDef, defender, dDef, dist, stiffDefense);
    }

    public int calculateEffectiveDifference(int attackGrade, int defenseGrade) {
        return combatSystem.calculateEffectiveDifference(attackGrade, defenseGrade);
    }

    public int getEffectiveFiringStrength(PgDatabase.ActiveUnit u, PgDatabase.UnitDef def, boolean isAttacker) {
        return combatSystem.getEffectiveFiringStrength(u, def, isAttacker);
    }

    public boolean isBombardment(PgDatabase.UnitDef def, int dist) {
        return combatSystem.isBombardment(def, dist);
    }

    public double getD20HitProbability(int effDiff, boolean isBombardment) {
        return combatSystem.getD20HitProbability(effDiff, isBombardment);
    }

    public double getD20SuppProbability(int effDiff, boolean isBombardment) {
        return combatSystem.getD20SuppProbability(effDiff, isBombardment);
    }

    public boolean canReturnFire(PgDatabase.ActiveUnit attacker, PgDatabase.UnitDef aDef,
                                 PgDatabase.ActiveUnit defender, PgDatabase.UnitDef dDef, int dist) {
        return combatSystem.canReturnFire(attacker, aDef, defender, dDef, dist);
    }

    public CombatPrediction predictCombat(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        return combatSystem.predictCombat(attacker, defender);
    }

    public CombatReport executeCombat(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        return combatSystem.executeCombat(attacker, defender);
    }

    public PgDatabase.ActiveUnit findFighterEscort(PgDatabase.ActiveUnit bomber) {
        return combatSystem.findFighterEscort(bomber);
    }

    public CombatReport resolveEscortDogfight(PgDatabase.ActiveUnit escort, PgDatabase.ActiveUnit interceptor) {
        return combatSystem.resolveEscortDogfight(escort, interceptor);
    }

    public PgDatabase.ActiveUnit findDefensiveSupport(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        return combatSystem.findDefensiveSupport(attacker, defender);
    }

    public CombatReport resolveDefensiveSupportFire(PgDatabase.ActiveUnit support, PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender) {
        return combatSystem.resolveDefensiveSupportFire(support, attacker, defender);
    }

    // =========================================================================
    // LOGISTICS, REPLACEMENTS & UPGRADES FACADE (PgLogisticsManager)
    // =========================================================================
    public boolean hasAvailableAction(PgDatabase.ActiveUnit u) {
        return logisticsManager.hasAvailableAction(u);
    }

    public boolean resupplyUnit(PgDatabase.ActiveUnit u) {
        return logisticsManager.resupplyUnit(u);
    }

    public boolean canReinforceUnit(PgDatabase.ActiveUnit u, boolean elite) {
        return logisticsManager.canReinforceUnit(u, elite);
    }

    public boolean reinforceUnit(PgDatabase.ActiveUnit u, boolean elite) {
        return logisticsManager.reinforceUnit(u, elite);
    }

    public boolean canUpgradeUnit(PgDatabase.ActiveUnit u) {
        return logisticsManager.canUpgradeUnit(u);
    }

    public int getUpgradeCost(PgDatabase.ActiveUnit u, int newUnitDefId, int newTransportDefId) {
        return logisticsManager.getUpgradeCost(u, newUnitDefId, newTransportDefId);
    }

    public boolean upgradeUnit(PgDatabase.ActiveUnit u, int newUnitDefId, int newTransportDefId) {
        return logisticsManager.upgradeUnit(u, newUnitDefId, newTransportDefId);
    }

    public boolean canDisbandUnit(PgDatabase.ActiveUnit u) {
        return logisticsManager.canDisbandUnit(u);
    }

    public int getDisbandRefund(PgDatabase.ActiveUnit u) {
        return logisticsManager.getDisbandRefund(u);
    }

    public int disbandUnit(PgDatabase.ActiveUnit u) {
        return logisticsManager.disbandUnit(u);
    }

    // =========================================================================
    // AI OPPONENT FACADE (PgAiController)
    // =========================================================================
    public double evaluateAiDeploymentEffectiveness(PgDatabase.UnitDef d, int col, int row,
            int infCount, int artyCount, int tankCount, int atCount, int aaCount, int airCount) {
        return aiController.evaluateAiDeploymentEffectiveness(d, col, row,
                infCount, artyCount, tankCount, atCount, aaCount, airCount);
    }

    public boolean takeAiReplacements(PgDatabase.ActiveUnit u) {
        return aiController.takeAiReplacements(u);
    }

    public double evaluateAiAttackScore(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit target) {
        return aiController.evaluateAiAttackScore(attacker, target);
    }

    public PgDatabase.ActiveUnit pickBestAiTarget(PgDatabase.ActiveUnit attacker, List<PgDatabase.ActiveUnit> targets) {
        return aiController.pickBestAiTarget(attacker, targets);
    }

    public double evaluateHypotheticalAttackScore(PgDatabase.ActiveUnit attacker, int fromCol, int fromRow, PgDatabase.ActiveUnit target) {
        return aiController.evaluateHypotheticalAttackScore(attacker, fromCol, fromRow, target);
    }

    public List<CombatReport> endPlayerTurnAndRunAi() {
        return aiController.endPlayerTurnAndRunAi();
    }

    public List<CombatReport> endPlayerTurnAndRunAi(AiTurnCallback callback) {
        return aiController.endPlayerTurnAndRunAi(callback);
    }

    // =========================================================================
    // SAVE & LOAD PERSISTENCE FACADE (PgSaveManager)
    // =========================================================================
    public void saveGameToStream(OutputStream os) throws IOException {
        saveManager.saveGameToStream(os);
    }

    public void saveGame(Context ctx, int slot) throws IOException {
        saveManager.saveGame(ctx, slot);
    }

    public boolean exportSlotToStream(Context ctx, int slot, OutputStream os) {
        return saveManager.exportSlotToStream(ctx, slot, os);
    }

    public boolean importStreamToSlot(Context ctx, InputStream is, int slot) {
        return saveManager.importStreamToSlot(ctx, is, slot);
    }

    public boolean loadGameFromStream(InputStream is) {
        return saveManager.loadGameFromStream(is);
    }

    public boolean loadGame(Context ctx, int slot) {
        return saveManager.loadGame(ctx, slot);
    }

    public String getSaveSummary(Context ctx, int slot) {
        return saveManager.getSaveSummary(ctx, slot);
    }
}
