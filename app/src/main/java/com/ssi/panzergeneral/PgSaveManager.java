package com.ssi.panzergeneral;

import android.content.Context;
import java.io.*;
import java.util.*;

/**
 * Handles saving, loading, importing, and exporting Panzer General game state.
 */
public class PgSaveManager {
    private final PgGameEngine engine;
    private final PgDatabase db;

    public PgSaveManager(PgGameEngine engine) {
        this.engine = engine;
        this.db = engine.db;
    }

    public void saveGameToStream(OutputStream os) throws IOException {
        if (engine.scenario == null) throw new IOException("No active scenario to save.");
        int[][] hexOwners = new int[engine.scenario.cols][engine.scenario.rows];
        for (int c = 0; c < engine.scenario.cols; c++) {
            for (int r = 0; r < engine.scenario.rows; r++) {
                hexOwners[c][r] = engine.scenario.grid[c][r].ownerNation;
            }
        }
        String saveTimestamp = new java.text.SimpleDateFormat("MMM dd HH:mm", Locale.US).format(new Date());

        PgGameEngine.SaveState st = PgGameEngine.SaveState.builder()
                .isCampaign(engine.isCampaignMode)
                .campaignEntryIndex(engine.campaignIndex)
                .currentScenarioNumber(engine.currentScenarioNumber)
                .turn(engine.turn)
                .maxTurns(engine.maxTurns)
                .weather(engine.weather)
                .groundCondition(engine.groundCondition)
                .wetness(engine.wetness)
                .axisPrestige(engine.axisPrestige)
                .alliedPrestige(engine.alliedPrestige)
                .supplyEnabled(engine.supplyEnabled)
                .weatherEnabled(engine.weatherEnabled)
                .fogOfWarEnabled(engine.fogOfWarEnabled)
                .prestigeModifier(engine.prestigeModifier)
                .units(new ArrayList<>(engine.units))
                .coreRoster(new ArrayList<>(engine.coreUnits))
                .unplacedCoreUnits(new ArrayList<>(engine.unplacedCoreUnits))
                .isDeploymentPhase(engine.isDeploymentPhase)
                .hexOwners(hexOwners)
                .scenarioTitle(engine.scenario.meta != null ? engine.scenario.meta.fullTitle : "Panzer General Battle")
                .saveTimestamp(saveTimestamp)
                .westDefeated(engine.westDefeated)
                .eastDefeated(engine.eastDefeated)
                .lowCountriesLost(engine.lowCountriesLost)
                .playerPlayingAllied(!engine.playerIsAxis)
                .selectedCol(engine.lastSelectedCol >= 0 ? engine.lastSelectedCol : null)
                .selectedRow(engine.lastSelectedRow >= 0 ? engine.lastSelectedRow : null)
                .build();

        ObjectOutputStream oos = new ObjectOutputStream(os);
        oos.writeObject(st);
        oos.flush();
    }

    public void saveGame(Context ctx, int slot) throws IOException {
        File f = new File(ctx.getFilesDir(), "pg_save_" + slot + ".dat");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            saveGameToStream(fos);
        }
    }

    public boolean exportSlotToStream(Context ctx, int slot, OutputStream os) {
        File f = new File(ctx.getFilesDir(), "pg_save_" + slot + ".dat");
        if (!f.exists()) return false;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) != -1) {
                os.write(buf, 0, read);
            }
            os.flush();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean importStreamToSlot(Context ctx, InputStream is, int slot) {
        File f = new File(ctx.getFilesDir(), "pg_save_" + slot + ".dat");
        try (InputStream compIs = createCompatibleInputStream(is);
             FileOutputStream fos = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = compIs.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
            fos.flush();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static InputStream createCompatibleInputStream(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        byte[] data = baos.toByteArray();

        // Search for "com.ssi.panzergeneral.PgGameEngine$SaveState"
        byte[] marker = "com.ssi.panzergeneral.PgGameEngine$SaveState".getBytes("UTF-8");
        byte[] altSuidBytes = new byte[] {
            (byte) 0xFA, (byte) 0x82, (byte) 0xBD, (byte) 0x02,
            (byte) 0x97, (byte) 0x85, (byte) 0x24, (byte) 0xE9
        }; // -395545998425512727L
        byte[] origSuidBytes = new byte[] {
            (byte) 0xD8, (byte) 0x2D, (byte) 0x38, (byte) 0x92,
            (byte) 0x1E, (byte) 0x0B, (byte) 0x77, (byte) 0x4F
        }; // -2869575187344689329L

        for (int i = 0; i <= data.length - marker.length - 8; i++) {
            boolean match = true;
            for (int j = 0; j < marker.length; j++) {
                if (data[i + j] != marker[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                int suidPos = i + marker.length;
                boolean altMatch = true;
                for (int k = 0; k < 8; k++) {
                    if (data[suidPos + k] != altSuidBytes[k]) {
                        altMatch = false;
                        break;
                    }
                }
                if (altMatch) {
                    for (int k = 0; k < 8; k++) {
                        data[suidPos + k] = origSuidBytes[k];
                    }
                }
                break;
            }
        }
        return new ByteArrayInputStream(data);
    }

    public boolean loadGameFromStream(InputStream is) {
        try (ObjectInputStream ois = new ObjectInputStream(createCompatibleInputStream(is))) {
            PgGameEngine.SaveState st = (PgGameEngine.SaveState) ois.readObject();
            engine.isCampaignMode = st.isCampaign;
            engine.playerIsAxis = !st.playerPlayingAllied;
            engine.campaignIndex = st.campaignEntryIndex;
            engine.westDefeated = st.westDefeated;
            engine.eastDefeated = st.eastDefeated;
            engine.lowCountriesLost = st.lowCountriesLost;
            engine.scenario = db.loadScenario(st.currentScenarioNumber);
            engine.currentScenarioNumber = st.currentScenarioNumber;
            engine.turn = st.turn;
            engine.maxTurns = st.maxTurns;
            engine.weather = st.weather;
            engine.groundCondition = st.groundCondition;
            engine.wetness = st.wetness;
            engine.axisPrestige = st.axisPrestige;
            engine.alliedPrestige = st.alliedPrestige;
            engine.supplyEnabled = st.supplyEnabled;
            engine.weatherEnabled = st.weatherEnabled;
            if (!engine.weatherEnabled) {
                engine.weather = 0;
                engine.groundCondition = 0;
            }
            engine.fogOfWarEnabled = st.fogOfWarEnabled;
            if (st.prestigeModifier > 0) engine.prestigeModifier = st.prestigeModifier;
            engine.lastUndoState = null;
            engine.lastSurpriseReport = null;
            engine.units.clear();
            if (st.units != null) {
                for (PgDatabase.ActiveUnit u : st.units) {
                    if (u != null && u.strength > 0) {
                        engine.units.add(u);
                        engine.ensureNextInstanceIdAtLeast(u.instanceId + 1);
                    }
                }
            }
            engine.coreUnits.clear();
            if (st.coreRoster != null) {
                for (PgDatabase.ActiveUnit u : st.coreRoster) {
                    if (u != null) {
                        engine.coreUnits.add(u);
                        engine.ensureNextInstanceIdAtLeast(u.instanceId + 1);
                    }
                }
            }
            engine.unplacedCoreUnits.clear();
            if (st.unplacedCoreUnits != null) {
                for (PgDatabase.ActiveUnit u : st.unplacedCoreUnits) {
                    if (u != null) {
                        engine.unplacedCoreUnits.add(u);
                        engine.ensureNextInstanceIdAtLeast(u.instanceId + 1);
                    }
                }
                engine.sortUnplacedCoreUnits();
            }
            engine.isDeploymentPhase = st.isDeploymentPhase;
            for (int c = 0; c < engine.scenario.cols && c < st.hexOwners.length; c++) {
                for (int r = 0; r < engine.scenario.rows && r < st.hexOwners[c].length; r++) {
                    engine.scenario.grid[c][r].ownerNation = st.hexOwners[c][r];
                }
            }
            engine.scenarioOutcome = 0;
            engine.lastSelectedCol = (st.selectedCol != null) ? st.selectedCol : -1;
            engine.lastSelectedRow = (st.selectedRow != null) ? st.selectedRow : -1;
            engine.visibleHexes = new boolean[engine.scenario.cols][engine.scenario.rows];
            engine.recalculateFogOfWar();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean loadGame(Context ctx, int slot) {
        File f = new File(ctx.getFilesDir(), "pg_save_" + slot + ".dat");
        if (!f.exists()) return false;
        try (FileInputStream fis = new FileInputStream(f)) {
            return loadGameFromStream(fis);
        } catch (Exception e) {
            return false;
        }
    }

    public String getSaveSummary(Context ctx, int slot) {
        File f = new File(ctx.getFilesDir(), "pg_save_" + slot + ".dat");
        if (!f.exists()) return "Slot " + slot + ": [Empty]";
        try (ObjectInputStream ois = new ObjectInputStream(createCompatibleInputStream(new FileInputStream(f)))) {
            PgGameEngine.SaveState st = (PgGameEngine.SaveState) ois.readObject();
            return String.format(Locale.ROOT, "Slot %d: %s (Turn %d/%d) - %s",
                    slot, st.scenarioTitle, st.turn, st.maxTurns, st.saveTimestamp);
        } catch (Exception e) {
            return "Slot " + slot + ": [Saved Game]";
        }
    }

    public static final String CACHE_FILE_NAME = "pg_session_cache.dat";

    public void saveGameCache(File dir) throws IOException {
        if (engine.scenario == null) return;
        if (!dir.exists()) dir.mkdirs();
        File targetFile = new File(dir, CACHE_FILE_NAME);
        File tmpFile = new File(dir, CACHE_FILE_NAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            saveGameToStream(fos);
        }
        if (targetFile.exists()) {
            targetFile.delete();
        }
        if (!tmpFile.renameTo(targetFile)) {
            try (FileInputStream in = new FileInputStream(tmpFile);
                 FileOutputStream out = new FileOutputStream(targetFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            tmpFile.delete();
        }
    }

    public void saveGameCache(Context ctx) throws IOException {
        saveGameCache(ctx.getFilesDir());
    }

    public boolean hasGameCache(File dir) {
        File f = new File(dir, CACHE_FILE_NAME);
        return f.exists() && f.length() > 0;
    }

    public boolean hasGameCache(Context ctx) {
        return hasGameCache(ctx.getFilesDir());
    }

    public boolean loadGameCache(File dir) {
        File f = new File(dir, CACHE_FILE_NAME);
        if (!f.exists()) return false;
        try (FileInputStream fis = new FileInputStream(f)) {
            return loadGameFromStream(fis);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean loadGameCache(Context ctx) {
        return loadGameCache(ctx.getFilesDir());
    }

    public void clearGameCache(File dir) {
        File f = new File(dir, CACHE_FILE_NAME);
        if (f.exists()) f.delete();
        File tmp = new File(dir, CACHE_FILE_NAME + ".tmp");
        if (tmp.exists()) tmp.delete();
    }

    public void clearGameCache(Context ctx) {
        clearGameCache(ctx.getFilesDir());
    }

    public String getGameCacheSummary(File dir) {
        File f = new File(dir, CACHE_FILE_NAME);
        if (!f.exists()) return null;
        try (ObjectInputStream ois = new ObjectInputStream(createCompatibleInputStream(new FileInputStream(f)))) {
            PgGameEngine.SaveState st = (PgGameEngine.SaveState) ois.readObject();
            String side = st.playerPlayingAllied ? "Allied" : "Axis";
            return String.format(Locale.ROOT, "%s [%s] (Turn %d/%d)",
                    st.scenarioTitle, side, st.turn, st.maxTurns);
        } catch (Exception e) {
            return null;
        }
    }

    public String getGameCacheSummary(Context ctx) {
        return getGameCacheSummary(ctx.getFilesDir());
    }
}
