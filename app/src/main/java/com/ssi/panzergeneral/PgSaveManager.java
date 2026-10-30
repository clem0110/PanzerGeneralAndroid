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
        try (FileOutputStream fos = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
            fos.flush();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean loadGameFromStream(InputStream is) {
        try (ObjectInputStream ois = new ObjectInputStream(is)) {
            PgGameEngine.SaveState st = (PgGameEngine.SaveState) ois.readObject();
            engine.isCampaignMode = st.isCampaign;
            engine.campaignIndex = st.campaignEntryIndex;
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
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            PgGameEngine.SaveState st = (PgGameEngine.SaveState) ois.readObject();
            return String.format(Locale.ROOT, "Slot %d: %s (Turn %d/%d) - %s",
                    slot, st.scenarioTitle, st.turn, st.maxTurns, st.saveTimestamp);
        } catch (Exception e) {
            return "Slot " + slot + ": [Saved Game]";
        }
    }
}
