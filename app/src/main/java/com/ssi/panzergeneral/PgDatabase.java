package com.ssi.panzergeneral;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.*;

public class PgDatabase {
    public static final int CLASS_INFANTRY = 0;
    public static final int CLASS_TANK = 1;
    public static final int CLASS_RECON = 2;
    public static final int CLASS_ANTI_TANK = 3;
    public static final int CLASS_ARTILLERY = 4;
    public static final int CLASS_ANTI_AIR = 5;
    public static final int CLASS_AIR_DEFENSE = 6;
    public static final int CLASS_FORT = 7;
    public static final int CLASS_FIGHTER = 8;
    public static final int CLASS_TAC_BOMBER = 9;
    public static final int CLASS_LEVEL_BOMBER = 10;
    public static final int CLASS_SUBMARINE = 11;
    public static final int CLASS_DESTROYER = 12;
    public static final int CLASS_CAPITAL_SHIP = 13;
    public static final int CLASS_CARRIER = 14;
    public static final int CLASS_LAND_TRANSPORT = 15;
    public static final int CLASS_AIR_TRANSPORT = 16;
    public static final int CLASS_SEA_TRANSPORT = 17;

    public static final String[] CLASS_NAMES = {
        "Infantry", "Tank", "Recon", "Anti-Tank", "Artillery",
        "Anti-Air", "Air Defense", "Fortification", "Fighter", "Tactical Bomber",
        "Level Bomber", "Submarine", "Destroyer", "Capital Ship", "Aircraft Carrier",
        "Truck Transport", "Air Transport", "Naval Transport"
    };

    public static final int TARGET_SOFT = 0;
    public static final int TARGET_HARD = 1;
    public static final int TARGET_AIR = 2;
    public static final int TARGET_NAVAL = 3;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnitDef implements Serializable {
        public int id;
        public String name;
        public int unitClass;
        public int softAttack;
        public int hardAttack;
        public int airAttack;
        public int navalAttack;
        public int groundDefense;
        public int airDefense;
        public int closeDefense;
        public int targetType;
        public int range;
        public int initiative;
        public int spotting;
        public int moveType;
        public int movement;
        public int maxFuel;
        public int maxAmmo;
        public int cost; // in Prestige points
        public int iconId;
        public int startMonth;
        public int startYear;
        public int endYear;
        public int nationMask;
        @Builder.Default
        public int entrenchmentRate = 1;

        public boolean isAir() {
            return unitClass == CLASS_FIGHTER || unitClass == CLASS_TAC_BOMBER
                    || unitClass == CLASS_LEVEL_BOMBER || unitClass == CLASS_AIR_TRANSPORT;
        }

        public boolean isNaval() {
            return unitClass >= CLASS_SUBMARINE && unitClass <= CLASS_CARRIER
                    || unitClass == CLASS_SEA_TRANSPORT;
        }

        public boolean isArtilleryOrAD() {
            return unitClass == CLASS_ARTILLERY || unitClass == CLASS_AIR_DEFENSE;
        }

        public boolean isSelfPropelled() {
            return isArtilleryOrAD() && movement > 1;
        }

        public boolean isTowedArtilleryOrAD() {
            return isArtilleryOrAD() && movement <= 1;
        }

        public boolean isBridgeEngineer() {
            if (name == null) return false;
            String n = name.toLowerCase(Locale.ROOT);
            return id == 330 || n.contains("bridge") || n.contains("brucken");
        }

        public boolean isPioneerOrEngineer() {
            if (isBridgeEngineer()) return true;
            if (name == null) return false;
            String n = name.toLowerCase(Locale.ROOT);
            return n.contains("pionier") || n.contains("engineer") || n.contains("pio");
        }

        public boolean isParatroop() {
            if (name == null) return false;
            String n = name.toLowerCase(Locale.ROOT);
            return n.contains("fallschirm") || n.contains("para") || n.contains("airborne");
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioMeta implements Serializable {
        public int number; // 1..38
        public String shortTitle;
        public String fullTitle;
        public String description;
        public String briefingText;
        public int turns;
        public int day, month, year;
        public int axisPrestige;
        public int alliedPrestige;
        public String nextDecisive;
        public String nextMarginal;
        public String nextLoss;
        public int decisiveTurnsRemaining;
        public int prestigeDecisive;
        public int prestigeMarginal;
        public int prestigeLoss;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HexCell implements Serializable {
        public int col;
        public int row;
        public int nameIndex;
        public String name;
        public int roadFlags;
        public int ownerNation; // 0 = neutral, >0 nation flag index
        public int tileSpriteId; // index in TACMAP.SHP / STRATMAP.SHP
        public int stmTerrain; // raw terrain ID from .STM
        public int terrainType; // normalized 0..13
        public boolean isObjective;
        public boolean isDeployHex;

        public String getTerrainName() {
            return getTerrainName(terrainType);
        }

        public static String getTerrainName(int terrainType) {
            switch (terrainType) {
                case 0: return "Clear";
                case 1: return "Coast";
                case 2: return "Ocean";
                case 3: return "Port";
                case 4: return "River";
                case 5: return "Mountain";
                case 6: return "Airfield";
                case 7: return "Swamp";
                case 8: return "City";
                case 9: return "Rough";
                case 10: return "Forest";
                case 11: return "Desert";
                case 12: return "Fortification";
                case 13: return "Bocage";
                default: return "Clear";
            }
        }

        public int getBaseEntrenchment() {
            switch (terrainType) {
                case 8: return 4; // City
                case 12: return 5; // Fortification
                case 5: return 3; // Mountain
                case 10: case 13: return 3; // Forest / Bocage
                case 9: case 7: return 2; // Rough / Swamp
                default: return 1;
            }
        }

        // Panzer General Official Strategy Guide Terrain Initiative Cap (Chapter 5)
        public int getInitiativeCap() {
            switch (terrainType) {
                case 8: return 1; // City
                case 10: case 12: case 13: return 3; // Forest, Bocage, Fortification
                case 7: return 4; // Swamp
                case 3: case 9: return 5; // Port, Rough
                case 5: return 8; // Mountain
                default: return 99; // Clear, Airfield, Desert, River, Ocean
            }
        }

        public boolean isCloseDefenseTerrain() {
            return terrainType == 8 || terrainType == 10 || terrainType == 5
                    || terrainType == 12 || terrainType == 13 || terrainType == 7;
        }

        public boolean isSupplyPoint() {
            return terrainType == 8 || terrainType == 3 || terrainType == 6 || terrainType == 12;
        }

        public boolean isWater() {
            return terrainType == 2 || (stmTerrain >= 4 && stmTerrain <= 7);
        }

        public boolean isPort() {
            return terrainType == 3 || stmTerrain == 8;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActiveUnit implements Serializable {
        public int instanceId;
        public int unitDefId;
        public int transportDefId;
        public int nation; // 8 = Germany, 10 = Italy, 16 = Poland, 23 = UK, 7 = France, 20 = USSR, 24 = USA
        public boolean isAxis;
        public boolean isCore;
        public int col;
        public int row;
        public int strength; // 1..15 (Overstrength up to 10 + stars)
        public int suppression;
        public int entrenchment;
        public int experience; // 100 per star (0..500)
        public int fuel;
        public int ammo;
        public int movePointsUsed;
        public boolean moved;
        public boolean attacked;
        public boolean mountedInTransport;
        public boolean inSeaTransport;
        public boolean inAirTransport;
        public boolean facingLeft;
        public String customTitle;

        public int getStars() {
            return Math.min(5, experience / 100);
        }

        // Panzer General Overstrength Rule: 10 base + 1 per experience star (max 15)
        public int getMaxOverstrength() {
            return 10 + getStars();
        }

        public int getEffectiveStrength() {
            return Math.max(0, strength - suppression);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioData implements Serializable {
        public ScenarioMeta meta;
        public int cols;
        public int rows;
        public int axisDirection;
        public int axisNations[];
        public int alliedNations[];
        public HexCell[][] grid;
        @Builder.Default
        public List<int[]> objectives = new ArrayList<>();
        @Builder.Default
        public List<int[]> deployHexes = new ArrayList<>();
        @Builder.Default
        public List<ActiveUnit> initialUnits = new ArrayList<>();
        @Builder.Default
        public boolean isDefensiveScenario = false;
    }

    public final List<UnitDef> equipment = new ArrayList<>();
    public final List<String> mapNames = new ArrayList<>();
    public final List<ScenarioMeta> scenarios = new ArrayList<>();
    private final PgAssetManager assetManager;

    public PgDatabase(PgAssetManager assetManager) {
        this.assetManager = assetManager;
        loadEquipment();
        loadMapNames();
        loadScenarioCatalog();
    }

    private void loadEquipment() {
        try {
            byte[] data = assetManager.readAssetBytes("dat/PANZEQUP.EQP");
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int count = Short.toUnsignedInt(bb.getShort(0));
            for (int i = 0; i < count && 2 + (i + 1) * 50 <= data.length; i++) {
                int off = 2 + i * 50;
                int nEnd = off;
                while (nEnd < off + 20 && data[nEnd] != 0) nEnd++;
                String name = new String(data, off, nEnd - off, StandardCharsets.US_ASCII).trim();
                int unitClass = data[off + 20] & 0xFF;
                int cost = (data[off + 41] & 0xFF) * 12;
                if (cost == 0) cost = 120;
                String nameLower = name.toLowerCase(Locale.ROOT);
                boolean isPio = i == 330 || nameLower.contains("bridge") || nameLower.contains("brucken")
                        || nameLower.contains("pionier") || nameLower.contains("engineer") || nameLower.contains("pio");
                int entRate = isPio ? 3 : (unitClass == CLASS_INFANTRY ? 2 : ((unitClass == CLASS_ANTI_TANK || unitClass == CLASS_ARTILLERY || unitClass == CLASS_AIR_DEFENSE) ? 1 : 0));

                UnitDef u = UnitDef.builder()
                        .id(i)
                        .name(name)
                        .unitClass(unitClass)
                        .softAttack(data[off + 21] & 0xFF)
                        .hardAttack(data[off + 22] & 0xFF)
                        .airAttack(data[off + 23] & 0xFF)
                        .navalAttack(data[off + 24] & 0xFF)
                        .groundDefense(data[off + 25] & 0xFF)
                        .airDefense(data[off + 26] & 0xFF)
                        .closeDefense(data[off + 27] & 0xFF)
                        .targetType(data[off + 28] & 0xFF)
                        .initiative(data[off + 31] & 0xFF)
                        .range(data[off + 32] & 0xFF)
                        .spotting(Math.max(1, data[off + 33] & 0xFF))
                        .moveType(data[off + 35] & 0xFF)
                        .movement(data[off + 36] & 0xFF)
                        .maxFuel(data[off + 37] & 0xFF)
                        .maxAmmo(data[off + 38] & 0xFF)
                        .cost(cost)
                        .iconId(Short.toUnsignedInt(bb.getShort(off + 42)))
                        .startMonth(data[off + 46] & 0xFF)
                        .startYear(data[off + 47] & 0xFF)
                        .endYear(data[off + 48] & 0xFF)
                        .nationMask(data[off + 49] & 0xFF)
                        .entrenchmentRate(entRate)
                        .build();
                equipment.add(u);
            }
        } catch (IOException ignored) {
        }
    }

    private void loadMapNames() {
        try {
            byte[] data = assetManager.readAssetBytes("dat/MAPNAMES.STR");
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int count = Short.toUnsignedInt(bb.getShort(0));
            for (int i = 0; i < count && 2 + (i + 1) * 20 <= data.length; i++) {
                int off = 2 + i * 20;
                int end = off;
                while (end < off + 20 && data[end] != 0) end++;
                mapNames.add(new String(data, off, end - off, StandardCharsets.US_ASCII).trim());
            }
        } catch (IOException ignored) {
        }
    }

    private void loadScenarioCatalog() {
        Map<String, String[]> tdbMap = new HashMap<>();
        try {
            byte[] tdbBytes = assetManager.readAssetBytes("SCENARIO.TDB");
            String text = new String(tdbBytes, StandardCharsets.US_ASCII);
            String[] lines = text.split("\r?\n");
            for (String line : lines) {
                if (!line.startsWith(".") || line.startsWith(".00010") || line.startsWith(".Scenario Name")) continue;
                String[] parts = line.substring(1).split("\\.");
                if (parts.length >= 10) {
                    String nameKey = parts[0].trim().toUpperCase(Locale.ROOT);
                    tdbMap.put(nameKey, parts);
                }
            }
        } catch (IOException ignored) {
        }

        // Map scenario index (1..38) to primary briefing file in SCNBRIEF
        int[] primaryBrfIds = {
            1, 5, 8, 12, 17, 25, 29, 33, 37, 41,
            45, 50, 54, 59, 65, 69, 73, 80, 84, 88,
            92, 96, 100, 104, 108, 112, 116, 120, 124, 128,
            132, 136, 140, 144, 148, 152, 156, 160
        };

        try {
            byte[] stat = assetManager.readAssetBytes("dat/SCENSTAT.BIN");
            for (int s = 1; s <= 38; s++) {
                int nameOff = 40 + (s - 1) * 14;
                int nEnd = nameOff;
                while (nEnd < nameOff + 14 && stat[nEnd] != 0) nEnd++;
                String shortTitle = new String(stat, nameOff, nEnd - nameOff, StandardCharsets.US_ASCII).trim();
                String fullTitle = shortTitle;

                int descOff = 600 + (s - 1) * 160;
                int dEnd = descOff;
                while (dEnd < descOff + 160 && dEnd < stat.length && stat[dEnd] != 0) dEnd++;
                String description = new String(stat, descOff, dEnd - descOff, StandardCharsets.US_ASCII).trim();

                int brfId = (s - 1 < primaryBrfIds.length) ? primaryBrfIds[s - 1] : s;
                String briefingText = assetManager.readBriefing(brfId);
                if (briefingText.isEmpty()) {
                    briefingText = description;
                }

                // Read header of GAME%03d.SCN for turns & date
                byte[] scn = assetManager.readAssetBytes(String.format(Locale.ROOT, "dat/GAME%03d.SCN", s));
                ByteBuffer scnBb = ByteBuffer.wrap(scn).order(ByteOrder.LITTLE_ENDIAN);
                int turns = scn[18] & 0xFF;
                int day = scn[22] & 0xFF;
                int month = scn[23] & 0xFF;
                int year = 1900 + (scn[24] & 0xFF);
                int deployCount = Short.toUnsignedInt(scnBb.getShort(0x75));
                int metaOff = 0x77 + deployCount * 4;
                int axisPrestige = Math.max(200, Short.toUnsignedInt(scnBb.getShort(metaOff)));
                int alliedPrestige = Math.max(200, Short.toUnsignedInt(scnBb.getShort(metaOff + 2)));

                // Match with SCENARIO.TDB for campaign branching
                String nextDecisive = (s < 38) ? String.valueOf(s + 1) : "VICTORY";
                String nextMarginal = (s < 38) ? String.valueOf(s + 1) : "VICTORY";
                String nextLoss = "LOSS";
                int decisiveTurnsRemaining = Math.max(3, turns / 4);
                int prestigeDecisive = 600;
                int prestigeMarginal = 400;
                int prestigeLoss = 200;

                String[] tdb = tdbMap.get(shortTitle.toUpperCase(Locale.ROOT));
                if (tdb == null) {
                    for (Map.Entry<String, String[]> entry : tdbMap.entrySet()) {
                        if (entry.getKey().startsWith(shortTitle.toUpperCase(Locale.ROOT))) {
                            tdb = entry.getValue();
                            fullTitle = tdb[0].trim();
                            break;
                        }
                    }
                }
                if (tdb != null && tdb.length >= 10) {
                    fullTitle = tdb[0].trim();
                    nextDecisive = tdb[2].trim();
                    nextMarginal = tdb[3].trim();
                    nextLoss = tdb[4].trim();
                    try { prestigeDecisive = Integer.parseInt(tdb[7].trim()); } catch (Exception ignored) {}
                    try { prestigeMarginal = Integer.parseInt(tdb[8].trim()); } catch (Exception ignored) {}
                    try { prestigeLoss = Integer.parseInt(tdb[9].trim()); } catch (Exception ignored) {}
                }

                ScenarioMeta m = ScenarioMeta.builder()
                        .number(s)
                        .shortTitle(shortTitle)
                        .fullTitle(fullTitle)
                        .description(description)
                        .briefingText(briefingText)
                        .turns(turns)
                        .day(day)
                        .month(month)
                        .year(year)
                        .axisPrestige(axisPrestige)
                        .alliedPrestige(alliedPrestige)
                        .nextDecisive(nextDecisive)
                        .nextMarginal(nextMarginal)
                        .nextLoss(nextLoss)
                        .decisiveTurnsRemaining(decisiveTurnsRemaining)
                        .prestigeDecisive(prestigeDecisive)
                        .prestigeMarginal(prestigeMarginal)
                        .prestigeLoss(prestigeLoss)
                        .build();
                scenarios.add(m);
            }
        } catch (IOException ignored) {
        }
    }

    public int findScenarioNumberByName(String title) {
        if (title == null) return -1;
        String clean = title.trim().toUpperCase(Locale.ROOT);
        if (clean.isEmpty() || clean.equals("????") || clean.equals("LOSS") || clean.equals("DRAW")
                || clean.equals("MWIN") || clean.equals("DWIN") || clean.equals("VICTORY")) {
            return -1;
        }
        try {
            return Integer.parseInt(clean);
        } catch (NumberFormatException ignored) {
        }
        for (ScenarioMeta m : scenarios) {
            if (m.fullTitle.equalsIgnoreCase(clean) || m.shortTitle.equalsIgnoreCase(clean)
                    || clean.startsWith(m.shortTitle.toUpperCase(Locale.ROOT))) {
                return m.number;
            }
        }
        return -1;
    }

    public ScenarioData loadScenario(int scenNum) throws IOException {
        ScenarioMeta meta = scenarios.get(Math.max(0, Math.min(scenarios.size() - 1, scenNum - 1)));
        byte[] set = assetManager.readAssetBytes(String.format(Locale.ROOT, "dat/MAP%02d.SET", scenNum));
        byte[] stm = assetManager.readAssetBytes(String.format(Locale.ROOT, "dat/MAP%02d.STM", scenNum));
        byte[] scn = assetManager.readAssetBytes(String.format(Locale.ROOT, "dat/GAME%03d.SCN", scenNum));

        ByteBuffer setBb = ByteBuffer.wrap(set).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer stmBb = ByteBuffer.wrap(stm).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer scnBb = ByteBuffer.wrap(scn).order(ByteOrder.LITTLE_ENDIAN);

        int cols = Short.toUnsignedInt(setBb.getShort(101)) + 1;
        int rows = Short.toUnsignedInt(setBb.getShort(103)) + 1;
        int nCells = cols * rows;

        int[] axisNations = new int[]{
            scn[0] & 0xFF, scn[2] & 0xFF, scn[4] & 0xFF,
            scn[6] & 0xFF, scn[8] & 0xFF, scn[10] & 0xFF
        };
        int[] alliedNations = new int[]{
            scn[1] & 0xFF, scn[3] & 0xFF, scn[5] & 0xFF,
            scn[7] & 0xFF, scn[9] & 0xFF, scn[11] & 0xFF
        };
        int axisDirection = scn[16] & 0xFF;

        int defaultAxisNation = axisNations[0] > 0 ? axisNations[0] : 8;
        int defaultAlliedNation = alliedNations[0] > 0 ? alliedNations[0] : 16;

        HexCell[][] grid = new HexCell[cols][rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                int nameIndex = Short.toUnsignedInt(setBb.getShort(123 + idx * 2));
                int roadFlags = set[123 + 2 * nCells + idx] & 0xFF;
                int rawOwnerNation = set[123 + 3 * nCells + idx] & 0xFF;
                int sideFlag = set[123 + 4 * nCells + idx] & 0xFF;
                int tileSpriteId = Short.toUnsignedInt(setBb.getShort(123 + 5 * nCells + idx * 2));
                int stmTerrain = Short.toUnsignedInt(stmBb.getShort(idx * 2));
                int terrainType = normalizeTerrain(stmTerrain, nameIndex, tileSpriteId);
                String name = (nameIndex >= 0 && nameIndex < mapNames.size()) ? mapNames.get(nameIndex) : HexCell.getTerrainName(terrainType);
                boolean isSupply = (terrainType == 8 || terrainType == 3 || terrainType == 6 || terrainType == 12);
                int ownerNation;
                if (rawOwnerNation > 0) {
                    ownerNation = rawOwnerNation;
                } else if (isSupply) {
                    ownerNation = (sideFlag == 0) ? defaultAxisNation : defaultAlliedNation;
                } else {
                    ownerNation = 0;
                }

                HexCell cell = HexCell.builder()
                        .col(c)
                        .row(r)
                        .nameIndex(nameIndex)
                        .name(name)
                        .roadFlags(roadFlags)
                        .ownerNation(ownerNation)
                        .tileSpriteId(tileSpriteId)
                        .stmTerrain(stmTerrain)
                        .terrainType(terrainType)
                        .build();
                grid[c][r] = cell;
            }
        }

        ScenarioData sd = ScenarioData.builder()
                .meta(meta)
                .cols(cols)
                .rows(rows)
                .axisNations(axisNations)
                .alliedNations(alliedNations)
                .axisDirection(axisDirection)
                .grid(grid)
                .build();

        // Parse up to 20 objective hex coordinates at offset 37..117
        for (int off = 37; off < 117 && off + 4 <= scn.length; off += 4) {
            int c = scnBb.getShort(off);
            int r = scnBb.getShort(off + 2);
            if (c < 0 || r < 0) {
                // In Scenario 1 (Poland), (-1, -1) terminates the active objective list (Breslau, Lodz, Kutno)
                // before leftover deleted entries from map editing such as (10,8) Bzura River and (4,3) Clear.
                if (scenNum == 1) break;
                continue;
            }
            if (c < sd.cols && r < sd.rows) {
                HexCell cell = sd.grid[c][r];
                // Valid Panzer General victory objectives are owned cities, ports, or airfields
                boolean isValidObjectiveTerrain = (cell.terrainType == 8 || cell.terrainType == 3 || cell.terrainType == 6);
                if (cell.ownerNation > 0 && isValidObjectiveTerrain && !cell.isObjective) {
                    cell.isObjective = true;
                    sd.objectives.add(new int[]{c, r});
                }
            }
        }

        // Parse deployment hexes
        int deployCount = Short.toUnsignedInt(scnBb.getShort(0x75));
        for (int i = 0; i < deployCount; i++) {
            int off = 0x77 + i * 4;
            if (off + 4 <= scn.length) {
                int c = scnBb.getShort(off);
                int r = scnBb.getShort(off + 2);
                if (c >= 0 && c < sd.cols && r >= 0 && r < sd.rows) {
                    sd.grid[c][r].isDeployHex = true;
                    sd.deployHexes.add(new int[]{c, r});
                }
            }
        }

        // Parse units (14 bytes each starting after deploy table + 16-byte unit header)
        int unitStart = 0x77 + deployCount * 4 + 16;
        int instId = 1;
        for (int off = unitStart; off + 14 <= scn.length; off += 14) {
            int uId = Short.toUnsignedInt(scnBb.getShort(off));
            int trpId = Short.toUnsignedInt(scnBb.getShort(off + 2));
            int nat = scn[off + 4] & 0xFF;
            int auxFlag = scn[off + 6] & 0xFF;
            int c = Short.toUnsignedInt(scnBb.getShort(off + 7));
            int r = Short.toUnsignedInt(scnBb.getShort(off + 9));
            int str = scn[off + 11] & 0xFF;
            int ent = scn[off + 12] & 0xFF;
            int expStars = scn[off + 13] & 0xFF;

            if (uId <= 0 || uId >= equipment.size() || c >= sd.cols || r >= sd.rows) continue;
            UnitDef def = equipment.get(uId);
            boolean isAxis = isAxisNation(nat, sd);
            int currentInstId = instId++;
            HexCell startCell = sd.grid[c][r];
            boolean inSea = !def.isAir() && !def.isNaval() && startCell.isWater();

            ActiveUnit u = ActiveUnit.builder()
                    .instanceId(currentInstId)
                    .unitDefId(uId)
                    .transportDefId((trpId > 0 && trpId < equipment.size()) ? trpId : 0)
                    .nation(nat)
                    .isAxis(isAxis)
                    .isCore(isAxis && (auxFlag == 0))
                    .col(c)
                    .row(r)
                    .strength((str > 0 && str <= 15) ? str : 10)
                    .entrenchment(ent)
                    .experience(expStars * 100)
                    .fuel(def.maxFuel > 0 ? def.maxFuel : 99)
                    .ammo(def.maxAmmo > 0 ? def.maxAmmo : 99)
                    .facingLeft(!isAxis)
                    .customTitle(currentInstId + ". " + def.name)
                    .inSeaTransport(inSea)
                    .build();

            sd.initialUnits.add(u);

            // Ensure any city/supply point/objective occupied by a ground unit at start belongs to that unit's nation
            if (!def.isAir() && (sd.grid[c][r].isObjective || sd.grid[c][r].isSupplyPoint())) {
                sd.grid[c][r].ownerNation = nat;
            }
        }

        if (meta != null && meta.fullTitle != null) {
            String title = meta.fullTitle.toLowerCase(Locale.ROOT);
            sd.isDefensiveScenario = title.contains("husky") || title.contains("anzio")
                    || title.contains("d-day") || title.contains("berlin") || title.contains("defense");
        }

        return sd;
    }

    public boolean isAxisNation(int nation, ScenarioData sd) {
        if (nation <= 0) return false;
        // Authentic Panzer General Axis nation IDs:
        // 3 = Bulgaria, 6 = Finland, 8 = Germany, 11 = Hungary, 13 = Italy, 18 = Romania
        if (nation == 8 || nation == 13 || nation == 11 || nation == 3 || nation == 18 || nation == 6) return true;
        if (sd != null && sd.axisNations != null) {
            for (int n : sd.axisNations) {
                if (n > 0 && n == nation) return true;
            }
        }
        return false;
    }

    private int normalizeTerrain(int stmVal, int nameIdx, int tileId) {
        int low = stmVal & 0xFF;
        if (low <= 3) return 0; // Clear
        if (low >= 4 && low <= 7) return 2; // Ocean
        if (low == 8) return 3; // Port
        if (low >= 9 && low <= 11) return 4; // River
        if (low >= 12 && low <= 18) return 5; // Mountain
        if (low == 19) return 6; // Airfield
        if (low == 20) return 7; // Swamp
        if (low == 21 || low == 29 || low == 37) return 8; // City
        if (low == 22 || low == 34 || low == 35 || low == 36) return 9; // Rough
        if (low == 23 || low == 42) return 10; // Forest
        if (low == 25 || low == 26 || low == 33) return 12; // Fortification
        if (low == 27 || low == 28) return 13; // Bocage
        if (low == 30 || low == 38) return 11; // Desert
        if (low == 31 || low == 32) return 5; // Mountain
        if (nameIdx > 0 && nameIdx <= 13) return nameIdx;
        if (tileId >= 124 && tileId <= 128) return 8; // City tiles
        return 0;
    }
}
