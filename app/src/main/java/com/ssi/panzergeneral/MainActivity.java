package com.ssi.panzergeneral;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity implements PgMapView.MapInteractionListener {
    private PgAssetManager assets;
    private PgDatabase db;
    private PgGameEngine engine;
    private PgSoundManager sounds;

    private FrameLayout rootContainer;
    private View titleOverlay;
    private Button btnResume;
    private LinearLayout hudContainer;
    private PgMapView mapView;

    // Top bar labels
    private TextView tvScenarioTitle;
    private TextView tvTurnWeather;
    private TextView tvPrestige;
    private TextView tvObjectivesCount;
    private Button btnRoster;
    private int rosterFilterMode = 0; // 0=All, 1=Can Move/Act, 2=Not Attacked, 3=Ground, 4=Air & Naval

    // Bottom bar controls
    private ImageView ivSelectedUnitIcon;
    private TextView tvSelectedTitle;
    private TextView tvSelectedStats;
    private Button btnSupply;
    private Button btnReinforce;
    private Button btnElite;
    private Button btnUpgrade;
    private Button btnMount;
    private Button btnUndo;
    private Button btnAirToggle;
    private Button btnPurchase;
    private Button btnNextUnit;
    private Button btnEndTurn;
    private volatile boolean isAiTurnRunning = false;
    private final PgTurnLossTracker turnLossTracker = new PgTurnLossTracker();
    private PgTurnLossTracker.TurnSummary lastTurnLossSummary = null;
    private static final int REQUEST_SAVE_TO_DRIVE = 1001;
    private static final int REQUEST_LOAD_FROM_DRIVE = 1002;
    private static final int REQUEST_IMPORT_TO_SLOT = 1003;
    private int pendingExportSlot = 0;
    private int pendingImportSlot = 0;

    private void setHudControlsEnabled(boolean enabled) {
        if (btnRoster != null) btnRoster.setEnabled(enabled);
        if (btnEndTurn != null) btnEndTurn.setEnabled(enabled);
        if (btnNextUnit != null) btnNextUnit.setEnabled(enabled);
        if (btnPurchase != null) btnPurchase.setEnabled(enabled);
        if (btnUndo != null) btnUndo.setEnabled(enabled);
        if (btnSupply != null) btnSupply.setEnabled(enabled);
        if (btnReinforce != null) btnReinforce.setEnabled(enabled);
        if (btnElite != null) btnElite.setEnabled(enabled);
        if (btnUpgrade != null) btnUpgrade.setEnabled(enabled);
        if (btnMount != null) btnMount.setEnabled(enabled);
        if (btnAirToggle != null) btnAirToggle.setEnabled(enabled);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();

        assets = new PgAssetManager(getAssets());
        db = new PgDatabase(assets);
        engine = new PgGameEngine(db);
        sounds = new PgSoundManager(this);
        loadPreferences();

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(0xFF121613);

        buildTacticalHud();
        buildTitleOverlay();

        setContentView(rootContainer);
        sounds.startMusic(0);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void loadPreferences() {
        android.content.SharedPreferences prefs = getSharedPreferences("pg_prefs", MODE_PRIVATE);
        engine.supplyEnabled = prefs.getBoolean("supplyEnabled", true);
        engine.weatherEnabled = prefs.getBoolean("weatherEnabled", true);
        engine.fogOfWarEnabled = prefs.getBoolean("fogOfWarEnabled", true);
        engine.showHexGrid = prefs.getBoolean("showHexGrid", true);
        engine.showUnitStrength = prefs.getBoolean("showUnitStrength", true);
        engine.prestigeModifier = prefs.getInt("prestigeModifier", 100);
        sounds.setSoundEnabled(prefs.getBoolean("soundEnabled", true));
        sounds.setMusicEnabled(prefs.getBoolean("musicEnabled", true));
    }

    private void savePreferences() {
        getSharedPreferences("pg_prefs", MODE_PRIVATE).edit()
                .putBoolean("supplyEnabled", engine.supplyEnabled)
                .putBoolean("weatherEnabled", engine.weatherEnabled)
                .putBoolean("fogOfWarEnabled", engine.fogOfWarEnabled)
                .putBoolean("showHexGrid", engine.showHexGrid)
                .putBoolean("showUnitStrength", engine.showUnitStrength)
                .putInt("prestigeModifier", engine.prestigeModifier)
                .putBoolean("soundEnabled", sounds.isSoundEnabled())
                .putBoolean("musicEnabled", sounds.isMusicEnabled())
                .apply();
    }

    private void buildTitleOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFF141815);

        // Background artwork from CAMPMAP.SHP or INTRO2.SHP decoded from PGBUDV1_2.iso
        ImageView bgImage = new ImageView(this);
        Bitmap campBmp = assets.getSprite("CAMPMAP.SHP", 0);
        if (campBmp == null) campBmp = assets.getSprite("INTRO2.SHP", 0);
        if (campBmp != null) {
            bgImage.setImageBitmap(campBmp);
            bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bgImage.setAlpha(0.55f);
        }
        overlay.addView(bgImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(48, 28, 48, 28);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xDD1B221D);
        cardBg.setCornerRadius(20f);
        cardBg.setStroke(3, 0xFFC5A059);
        card.setBackground(cardBg);

        TextView title = new TextView(this);
        title.setText("PANZER GENERAL");
        title.setTextColor(0xFFFFD54F);
        title.setTextSize(30f);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("SSI Classic Tactical Wargame • PGBUDV1_2 Edition (38 Scenarios • 425 Units)");
        subtitle.setTextColor(0xFFCFD8DC);
        subtitle.setTextSize(13f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 4, 0, 16);
        card.addView(subtitle);

        LinearLayout btnGrid = new LinearLayout(this);
        btnGrid.setOrientation(LinearLayout.VERTICAL);
        btnGrid.setGravity(Gravity.CENTER);

        btnResume = createStyledButton("\u2605 RESUME GAME \u2605", 0xFF1B4D3E, 0xFFFFD54F);
        btnResume.setOnClickListener(v -> resumeGame());
        btnGrid.addView(btnResume, createMenuBtnParams());

        Button btnCampaign = createStyledButton("START CAMPAIGN (1939–1945)", 0xFF2E4C34, 0xFFFFD54F);
        btnCampaign.setOnClickListener(v -> {
            sounds.playClick();
            showCampaignSelectDialog();
        });
        btnGrid.addView(btnCampaign, createMenuBtnParams());

        Button btnScenario = createStyledButton("PLAY SINGLE SCENARIO (ALL 38 BATTLES)", 0xFF263849, 0xFFFFFFFF);
        btnScenario.setOnClickListener(v -> {
            sounds.playClick();
            showScenarioSelectDialog();
        });
        btnGrid.addView(btnScenario, createMenuBtnParams());

        Button btnOptions = createStyledButton("GAME OPTIONS & PREFERENCES (SUPPLY / WEATHER / FOW)", 0xFF4A3B2C, 0xFFFFE082);
        btnOptions.setOnClickListener(v -> {
            sounds.playClick();
            showPreferencesDialog();
        });
        btnGrid.addView(btnOptions, createMenuBtnParams());

        Button btnLoad = createStyledButton("LOAD SAVED GAME (LOCAL / GOOGLE DRIVE)", 0xFF373B3E, 0xFFFFFFFF);
        btnLoad.setOnClickListener(v -> {
            sounds.playClick();
            showSaveLoadDialog(false);
        });
        btnGrid.addView(btnLoad, createMenuBtnParams());

        Button btnEncyclopedia = createStyledButton("INSTRUCTION MANUAL & EQUIPMENT ENCYCLOPEDIA", 0xFF3E352B, 0xFFFFE082);
        btnEncyclopedia.setOnClickListener(v -> {
            sounds.playClick();
            new AlertDialog.Builder(this)
                    .setTitle("Reference Library & Documentation")
                    .setItems(new String[]{
                        "📖 Official Instruction Manual & Tactical Rules",
                        "🎖️ Official SSI Strategy Guide (Tactics & Combined Arms)",
                        "🗺️ Campaign Operations Compendium & Scenario Notes",
                        "⚔️ Equipment Encyclopedia (All 425 Units)"
                    }, (d, which) -> {
                        sounds.playSelect();
                        if (which == 0) showFieldManualDialog(0);
                        else if (which == 1) showFieldManualDialog(1);
                        else if (which == 2) showFieldManualDialog(2);
                        else if (which == 3) showEncyclopediaDialog();
                    })
                    .setNegativeButton("Close", null)
                    .show();
        });
        btnGrid.addView(btnEncyclopedia, createMenuBtnParams());

        card.addView(btnGrid);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(card, cardLp);

        this.titleOverlay = overlay;
        rootContainer.addView(titleOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateResumeButton();
    }

    private LinearLayout.LayoutParams createMenuBtnParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(720, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 5, 0, 5);
        return lp;
    }

    private void updateResumeButton() {
        if (btnResume == null) return;
        if (engine.scenario != null && engine.scenarioOutcome == 0) {
            String title = engine.scenario.meta != null ? engine.scenario.meta.fullTitle : "Battle";
            String side = engine.playerIsAxis ? "Axis" : "Allied";
            btnResume.setText(String.format(Locale.ROOT, "\u2605 RESUME GAME: %s [%s] (Turn %d/%d) \u2605",
                    title, side, engine.turn, engine.maxTurns));
            btnResume.setVisibility(View.VISIBLE);
        } else if (engine.hasGameCache(this)) {
            String summary = engine.getGameCacheSummary(this);
            if (summary != null && !summary.isEmpty()) {
                btnResume.setText(String.format(Locale.ROOT, "\u2605 RESUME GAME: %s \u2605", summary));
            } else {
                btnResume.setText("\u2605 RESUME GAME \u2605");
            }
            btnResume.setVisibility(View.VISIBLE);
        } else {
            btnResume.setVisibility(View.GONE);
        }
    }

    private void resumeGame() {
        sounds.playClick();
        if (engine.scenario != null && engine.scenarioOutcome == 0) {
            if (titleOverlay != null) {
                titleOverlay.setVisibility(View.GONE);
            }
            mapView.purchasePlacementMode = false;
            mapView.strategicMapOverlay = false;
            if (mapView.selectedUnit != null && engine.isPlayerUnit(mapView.selectedUnit)) {
                mapView.centerOnHex(mapView.selectedUnit.col, mapView.selectedUnit.row);
                mapView.selectUnit(mapView.selectedUnit);
            } else {
                centerCameraOnActiveUnits();
            }
            refreshHud();
            mapView.invalidate();
            sounds.playSelect();
            if (sounds.isMusicEnabled()) {
                sounds.startMusic(engine.currentScenarioNumber % 6);
            }
            Toast.makeText(this, "Resumed battle: " + (engine.scenario.meta != null ? engine.scenario.meta.fullTitle : "Battle"), Toast.LENGTH_SHORT).show();
            return;
        }

        if (engine.hasGameCache(this)) {
            if (engine.loadGameCache(this)) {
                if (titleOverlay != null) {
                    titleOverlay.setVisibility(View.GONE);
                }
                mapView.purchasePlacementMode = false;
                mapView.strategicMapOverlay = false;
                centerCameraOnActiveUnits();
                refreshHud();
                mapView.invalidate();
                sounds.playSelect();
                if (sounds.isMusicEnabled()) {
                    sounds.startMusic(engine.currentScenarioNumber % 6);
                }
                String name = (engine.scenario != null && engine.scenario.meta != null) ? engine.scenario.meta.fullTitle : "Battle";
                Toast.makeText(this, "Resumed battle: " + name + " (Turn " + engine.turn + "/" + engine.maxTurns + ")", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Could not load cached battle.", Toast.LENGTH_SHORT).show();
                engine.clearGameCache(this);
                updateResumeButton();
            }
        }
    }

    private void centerCameraOnActiveUnits() {
        if (engine.lastSelectedCol >= 0 && engine.lastSelectedRow >= 0
                && engine.scenario != null
                && engine.lastSelectedCol < engine.scenario.cols
                && engine.lastSelectedRow < engine.scenario.rows) {
            int sc = engine.lastSelectedCol;
            int sr = engine.lastSelectedRow;
            mapView.post(() -> {
                mapView.centerOnHex(sc, sr);
                PgDatabase.ActiveUnit u = engine.getUnitAt(sc, sr, false);
                if (u == null) u = engine.getUnitAt(sc, sr, true);
                if (u != null && engine.isPlayerUnit(u)) {
                    mapView.selectUnit(u);
                }
                refreshHud();
                mapView.invalidate();
            });
            return;
        }
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u != null && engine.isPlayerUnit(u) && u.strength > 0 && u.col >= 0 && u.row >= 0) {
                mapView.post(() -> {
                    mapView.centerOnHex(u.col, u.row);
                    mapView.selectUnit(u);
                    refreshHud();
                    mapView.invalidate();
                });
                return;
            }
        }
        if (engine.scenario != null && !engine.scenario.deployHexes.isEmpty()) {
            int[] dh = engine.scenario.deployHexes.get(0);
            mapView.post(() -> {
                mapView.centerOnHex(dh[0], dh[1]);
                refreshHud();
                mapView.invalidate();
            });
        }
    }

    private void showTitleScreen() {
        if (mapView != null && mapView.pendingOverrunUnit != null) {
            mapView.cancelOverrunMoveSelection();
        }
        if (engine.scenario != null && engine.scenarioOutcome == 0) {
            try {
                if (mapView != null) {
                    engine.lastSelectedCol = mapView.selectedCol;
                    engine.lastSelectedRow = mapView.selectedRow;
                }
                engine.saveGameCache(this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (titleOverlay != null) {
            titleOverlay.setVisibility(View.VISIBLE);
            updateResumeButton();
        }
        sounds.stopMusic();
    }

    private void buildTacticalHud() {
        hudContainer = new LinearLayout(this);
        hudContainer.setOrientation(LinearLayout.VERTICAL);

        // 1. Top Command Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xFF1D241F);
        topBar.setPadding(16, 6, 16, 6);

        tvScenarioTitle = new TextView(this);
        tvScenarioTitle.setTextColor(0xFFFFD54F);
        tvScenarioTitle.setTextSize(15f);
        tvScenarioTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        topBar.addView(tvScenarioTitle, titleLp);

        tvTurnWeather = new TextView(this);
        tvTurnWeather.setTextColor(Color.WHITE);
        tvTurnWeather.setTextSize(13f);
        tvTurnWeather.setOnClickListener(v -> {
            sounds.playClick();
            engine.weatherEnabled = !engine.weatherEnabled;
            if (!engine.weatherEnabled) {
                engine.weather = 0;
                engine.groundCondition = 0;
            }
            savePreferences();
            engine.recalculateFogOfWar();
            if (mapView.selectedUnit != null) mapView.selectUnit(mapView.selectedUnit);
            refreshHud();
            mapView.invalidate();
            Toast.makeText(this, "Weather Rules: " + (engine.weatherEnabled ? "ON (Dynamic)" : "OFF (Always Clear & Dry)"), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams turnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        topBar.addView(tvTurnWeather, turnLp);

        tvObjectivesCount = new TextView(this);
        tvObjectivesCount.setTextColor(0xFF81C784);
        tvObjectivesCount.setTextSize(13f);
        LinearLayout.LayoutParams objLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f);
        topBar.addView(tvObjectivesCount, objLp);

        tvPrestige = new TextView(this);
        tvPrestige.setTextColor(0xFFFFE082);
        tvPrestige.setTextSize(14f);
        tvPrestige.setTypeface(Typeface.DEFAULT_BOLD);
        tvPrestige.setPadding(8, 0, 14, 0);
        tvPrestige.setOnClickListener(v -> {
            sounds.playClick();
            showRequisitionDialog();
        });
        topBar.addView(tvPrestige);

        btnRoster = createCompactButton("ROSTER", 0xFF2E5A44);
        btnRoster.setOnClickListener(v -> {
            if (isAiTurnRunning || engine.isDeploymentPhase) return;
            sounds.playClick();
            showUnitRosterDialog();
        });
        btnRoster.setVisibility(engine.isDeploymentPhase ? View.GONE : View.VISIBLE);
        topBar.addView(btnRoster);

        Button btnStratMap = createCompactButton("STRAT MAP", 0xFF2C3E50);
        btnStratMap.setOnClickListener(v -> {
            sounds.playClick();
            mapView.strategicMapOverlay = !mapView.strategicMapOverlay;
            mapView.invalidate();
        });
        topBar.addView(btnStratMap);

        Button btnBriefing = createCompactButton("BRIEFING", 0xFF3E4A3D);
        btnBriefing.setOnClickListener(v -> {
            sounds.playClick();
            showBriefingDialog();
        });
        topBar.addView(btnBriefing);

        Button btnOptions = createCompactButton("OPTIONS", 0xFF37474F);
        btnOptions.setOnClickListener(v -> {
            sounds.playClick();
            showPreferencesDialog();
        });
        topBar.addView(btnOptions);

        Button btnMenu = createCompactButton("MENU", 0xFF4A3531);
        btnMenu.setOnClickListener(v -> {
            sounds.playClick();
            showInGameMenuDialog();
        });
        topBar.addView(btnMenu);

        hudContainer.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Center Hex Map View
        mapView = new PgMapView(this, assets, engine, sounds);
        mapView.setListener(this);
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        hudContainer.addView(mapView, mapLp);

        // 3. Bottom Unit Inspector & Tactical Action Bar
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(0xFF1A201C);
        bottomBar.setPadding(12, 8, 12, 16);

        ivSelectedUnitIcon = new ImageView(this);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(96, 72);
        iconLp.setMargins(0, 0, 10, 0);
        bottomBar.addView(ivSelectedUnitIcon, iconLp);

        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        tvSelectedTitle = new TextView(this);
        tvSelectedTitle.setTextColor(0xFFFFD54F);
        tvSelectedTitle.setTextSize(13f);
        tvSelectedTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvSelectedTitle.setSingleLine(true);
        tvSelectedTitle.setText("Tap any hex or unit");
        infoBox.addView(tvSelectedTitle);

        tvSelectedStats = new TextView(this);
        tvSelectedStats.setTextColor(0xFFECEFF1);
        tvSelectedStats.setTextSize(11.5f);
        tvSelectedStats.setSingleLine(true);
        tvSelectedStats.setText("Pinch to zoom • Drag to pan map");
        infoBox.addView(tvSelectedStats);

        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        infoBox.setOnClickListener(v -> {
            if (mapView.selectedUnit != null) {
                sounds.playClick();
                showActiveUnitActionsModal(mapView.selectedUnit);
            }
        });
        ivSelectedUnitIcon.setOnClickListener(v -> {
            if (mapView.selectedUnit != null) {
                sounds.playClick();
                showActiveUnitActionsModal(mapView.selectedUnit);
            }
        });
        bottomBar.addView(infoBox, infoLp);

        // Tactical Action Buttons (hidden by default; shown only when actually available to the active unit)
        btnUndo = createCompactButton("UNDO", 0xFF546E7A);
        btnUndo.setVisibility(View.GONE);
        btnUndo.setOnClickListener(v -> {
            PgDatabase.ActiveUnit undone = engine.undoLastMove();
            if (undone != null) {
                sounds.playSelect();
                mapView.centerOnHex(undone.col, undone.row);
                mapView.selectUnit(undone);
                refreshHud();
                Toast.makeText(this, "Move undone for " + db.equipment.get(undone.unitDefId).name, Toast.LENGTH_SHORT).show();
            }
        });
        bottomBar.addView(btnUndo);

        btnSupply = createCompactButton("SUPPLY", 0xFF2E5A36);
        btnSupply.setVisibility(View.GONE);
        btnSupply.setOnClickListener(v -> {
            if (mapView.pendingOverrunUnit != null) {
                sounds.playClick();
                mapView.cancelOverrunMoveSelection();
                refreshHud();
                Toast.makeText(this, "Holding current position.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (engine.isDeploymentPhase && mapView.selectedUnit != null && engine.isPlayerUnit(mapView.selectedUnit) && mapView.selectedUnit.isCore) {
                PgDatabase.ActiveUnit u = mapView.selectedUnit;
                if (engine.undeployCoreUnit(u)) {
                    sounds.playSelect();
                    mapView.pendingDeployCoreUnit = u;
                    mapView.selectedUnit = u;
                    mapView.selectedCol = -1;
                    mapView.selectedRow = -1;
                    mapView.reachableHexes.clear();
                    mapView.validTargets.clear();
                    PgDatabase.UnitDef uDef = db.equipment.get(u.unitDefId);
                    if (uDef != null) {
                        mapView.airModePreferred = uDef.isAir();
                        if (btnAirToggle != null) {
                            btnAirToggle.setText(uDef.isAir() ? "AIR TOP" : "GND TOP");
                        }
                    }
                    refreshHud();
                    mapView.invalidate();
                    Toast.makeText(this, "Returned " + (uDef != null ? uDef.name : "Unit") + " to Reserve Pool (tap cyan hex to re-deploy).", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if (mapView.selectedUnit != null && engine.resupplyUnit(mapView.selectedUnit)) {
                sounds.playSelect();
                mapView.selectUnit(mapView.selectedUnit);
                refreshHud();
            }
        });
        bottomBar.addView(btnSupply);

        btnReinforce = createCompactButton("REPL", 0xFF3A536B);
        btnReinforce.setVisibility(View.GONE);
        btnReinforce.setOnClickListener(v -> {
            if (mapView.selectedUnit != null && engine.reinforceUnit(mapView.selectedUnit, false)) {
                sounds.playSelect();
                mapView.selectUnit(mapView.selectedUnit);
                refreshHud();
            }
        });
        bottomBar.addView(btnReinforce);

        btnElite = createCompactButton("ELITE+", 0xFF5D4037);
        btnElite.setVisibility(View.GONE);
        btnElite.setOnClickListener(v -> {
            if (mapView.selectedUnit != null && engine.reinforceUnit(mapView.selectedUnit, true)) {
                sounds.playSelect();
                mapView.selectUnit(mapView.selectedUnit);
                refreshHud();
            }
        });
        bottomBar.addView(btnElite);

        btnUpgrade = createCompactButton("UPGRADE", 0xFF4E342E);
        btnUpgrade.setVisibility(View.GONE);
        btnUpgrade.setOnClickListener(v -> {
            if (mapView.selectedUnit != null && engine.canUpgradeUnit(mapView.selectedUnit)) {
                sounds.playClick();
                showUpgradeUnitDialog(mapView.selectedUnit);
            }
        });
        bottomBar.addView(btnUpgrade);

        btnMount = createCompactButton("MOUNT", 0xFF455A64);
        btnMount.setVisibility(View.GONE);
        btnMount.setOnClickListener(v -> {
            PgDatabase.ActiveUnit u = mapView.selectedUnit;
            if (u != null && engine.canUseTransportAction(u)) {
                boolean canOrganic = engine.canToggleOrganicTransport(u);
                boolean canSea = engine.canSeaEmbark(u);
                boolean canAir = engine.canAirliftEmbark(u);
                if (canOrganic && (canSea || canAir)) {
                    sounds.playClick();
                    showTransportChoiceDialog(u, canSea, canAir);
                    return;
                }
                String msg = engine.executeTransportAction(u);
                sounds.playSelect();
                mapView.selectUnit(u);
                refreshHud();
                if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            }
        });
        bottomBar.addView(btnMount);

        btnAirToggle = createCompactButton("AIR/GND", 0xFF37474F);
        btnAirToggle.setOnClickListener(v -> {
            boolean currentAirTop = mapView.isShowingAirOnTop();
            mapView.airModePreferred = !currentAirTop;
            if (currentAirTop && mapView.selectedUnit != null && engine.isUnitOnAirLayer(mapView.selectedUnit)) {
                mapView.clearSelection();
            }
            btnAirToggle.setText(mapView.isShowingAirOnTop() ? "AIR TOP" : "GND TOP");
            sounds.playClick();
            refreshHud();
            mapView.invalidate();
        });
        bottomBar.addView(btnAirToggle);

        btnPurchase = createCompactButton("BUY UNIT", 0xFF6D4C41);
        btnPurchase.setOnClickListener(v -> {
            sounds.playClick();
            showRequisitionDialog();
        });
        bottomBar.addView(btnPurchase);

        btnNextUnit = createCompactButton("NEXT", 0xFF283593);
        btnNextUnit.setOnClickListener(v -> {
            sounds.playClick();
            if (isAiTurnRunning) return;
            if (engine.isDeploymentPhase) {
                showCoreDeploymentDialog();
            } else {
                selectNextUnmovedUnit();
            }
        });
        bottomBar.addView(btnNextUnit);

        btnEndTurn = createCompactButton("END TURN", 0xFF8E2424);
        btnEndTurn.setOnClickListener(v -> {
            sounds.playClick();
            if (engine.isDeploymentPhase) {
                confirmStartBattle();
            } else {
                performEndTurn();
            }
        });
        bottomBar.addView(btnEndTurn);

        hudContainer.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rootContainer.addView(hudContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private Button createStyledButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(24, 16, 24, 16);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(12f);
        gd.setStroke(2, 0xFFC5A059);
        b.setBackground(gd);
        return b;
    }

    private Button createCompactButton(String text, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(10.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(10, 6, 10, 6);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(8f);
        gd.setStroke(1, 0xFF90A4AE);
        b.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 64);
        lp.setMargins(3, 0, 3, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void showCampaignSelectDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Select Panzer General Campaign");
        b.setItems(PgGameEngine.CAMPAIGN_NAMES, (dialog, which) -> {
            try {
                engine.clearGameCache(this);
                engine.startCampaign(which);
                try {
                    engine.saveGameCache(this);
                } catch (Exception ignored) {}
                launchTacticalScreen();
            } catch (IOException e) {
                Toast.makeText(this, "Failed to load campaign: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void showScenarioSelectDialog() {
        String[] items = new String[db.scenarios.size()];
        for (int i = 0; i < db.scenarios.size(); i++) {
            PgDatabase.ScenarioMeta m = db.scenarios.get(i);
            items[i] = String.format(Locale.ROOT, "#%02d %s (%02d/%d • %d Turns)",
                    m.number, m.fullTitle, m.month, m.year, m.turns);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Select Historical Scenario (PGBUDV1_2)");
        b.setItems(items, (dialog, which) -> {
            showScenarioSideSelectDialog(which + 1);
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void showScenarioSideSelectDialog(final int scenNum) {
        if (scenNum < 1 || scenNum > db.scenarios.size()) return;
        final PgDatabase.ScenarioMeta meta = db.scenarios.get(scenNum - 1);
        PgDatabase.ScenarioData sdCandidate = null;
        try {
            sdCandidate = db.loadScenario(scenNum);
        } catch (Exception ignored) {}
        final PgDatabase.ScenarioData sd = sdCandidate;

        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141915);
        root.setPadding(32, 24, 32, 24);

        LinearLayout headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setPadding(20, 14, 20, 14);
        GradientDrawable hdrBg = new GradientDrawable();
        hdrBg.setColor(0xFF1F2721);
        hdrBg.setCornerRadius(10f);
        hdrBg.setStroke(2, 0xFFC5A059);
        headerBox.setBackground(hdrBg);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(String.format(Locale.ROOT, "#%02d %s", meta.number, meta.fullTitle.toUpperCase(Locale.ROOT)));
        tvTitle.setTextColor(0xFFFFD54F);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        headerBox.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText(String.format(Locale.ROOT,
                "Date: %02d/%d   •   Turn Limit: %d Turns   •   Select Operational Command Faction",
                meta.month, meta.year, meta.turns));
        tvSubtitle.setTextColor(0xFFB0BEC5);
        tvSubtitle.setTextSize(12.5f);
        tvSubtitle.setPadding(0, 4, 0, 0);
        headerBox.addView(tvSubtitle);

        root.addView(headerBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout cardsRow = new LinearLayout(this);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        crLp.setMargins(0, 18, 0, 18);
        cardsRow.setLayoutParams(crLp);
        root.addView(cardsRow);

        int axisPrimaryNat = (sd != null && sd.axisNations != null && sd.axisNations.length > 0 && sd.axisNations[0] > 0)
                ? sd.axisNations[0] : 8;
        StringBuilder axNatsSb = new StringBuilder();
        if (sd != null && sd.axisNations != null) {
            for (int n : sd.axisNations) {
                if (n > 0) {
                    if (axNatsSb.length() > 0) axNatsSb.append(", ");
                    axNatsSb.append(PgDatabase.getNationName(n));
                }
            }
        }
        if (axNatsSb.length() == 0) axNatsSb.append("Germany");
        boolean isDefensiveScenario = (sd != null) && sd.isDefensiveScenario;
        String axRole = isDefensiveScenario ? "Defensive Operation (Hold Objectives)" : "Offensive Assault (Capture All Objectives)";
        int axPrestigeVal = (meta.axisPrestige * engine.prestigeModifier) / 100;

        int alliedPrimaryNat = (sd != null && sd.alliedNations != null && sd.alliedNations.length > 0 && sd.alliedNations[0] > 0)
                ? sd.alliedNations[0] : 16;
        StringBuilder alNatsSb = new StringBuilder();
        if (sd != null && sd.alliedNations != null) {
            for (int n : sd.alliedNations) {
                if (n > 0) {
                    if (alNatsSb.length() > 0) alNatsSb.append(", ");
                    alNatsSb.append(PgDatabase.getNationName(n));
                }
            }
        }
        if (alNatsSb.length() == 0) alNatsSb.append(PgDatabase.getNationName(alliedPrimaryNat));
        String alRole = isDefensiveScenario ? "Offensive Counter-Attack (Break Enemy Lines)" : "Defensive Stance (Hold Key Objectives)";
        int alPrestigeVal = (meta.alliedPrestige * engine.prestigeModifier) / 100;

        // AXIS CARD
        LinearLayout axisCard = new LinearLayout(this);
        axisCard.setOrientation(LinearLayout.VERTICAL);
        axisCard.setPadding(24, 20, 24, 20);
        GradientDrawable axCardBg = new GradientDrawable();
        axCardBg.setColor(0xFF1E2822);
        axCardBg.setCornerRadius(12f);
        axCardBg.setStroke(2, 0xFFC5A059);
        axisCard.setBackground(axCardBg);

        LinearLayout axHdr = new LinearLayout(this);
        axHdr.setOrientation(LinearLayout.HORIZONTAL);
        axHdr.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ivAxFlag = new ImageView(this);
        Bitmap axFlagBmp = assets.getTrimmedSprite("FLAGS.SHP", Math.max(0, axisPrimaryNat - 1));
        if (axFlagBmp != null) ivAxFlag.setImageBitmap(Bitmap.createScaledBitmap(axFlagBmp, 52, 38, false));
        axHdr.addView(ivAxFlag, new LinearLayout.LayoutParams(52, 38));

        TextView tvAxTitle = new TextView(this);
        tvAxTitle.setText("  AXIS COMMAND");
        tvAxTitle.setTextColor(0xFFFFD54F);
        tvAxTitle.setTextSize(16f);
        tvAxTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        axHdr.addView(tvAxTitle);
        axisCard.addView(axHdr);

        TextView tvAxBody = new TextView(this);
        tvAxBody.setText(String.format(Locale.ROOT,
                "\n• Forces: %s\n• Tactical Role: %s\n• Starting Prestige: ★ %d\n• Core Command: Wehrmacht & Axis Allies",
                axNatsSb.toString(), axRole, axPrestigeVal));
        tvAxBody.setTextColor(0xFFCFD8DC);
        tvAxBody.setTextSize(13f);
        tvAxBody.setLineSpacing(4f, 1f);
        axisCard.addView(tvAxBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button btnPlayAxis = createCompactButton("⚔ PLAY AS AXIS", 0xFF2E7D32);
        btnPlayAxis.setTextSize(14f);
        btnPlayAxis.setPadding(0, 14, 0, 14);
        btnPlayAxis.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
            try {
                engine.clearGameCache(this);
                engine.startSingleScenario(scenNum, true);
                try {
                    engine.saveGameCache(this);
                } catch (Exception ignored) {}
                launchTacticalScreen();
            } catch (IOException e) {
                Toast.makeText(this, "Error starting scenario: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        axisCard.addView(btnPlayAxis, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams axCardLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        axCardLp.setMargins(0, 0, 12, 0);
        cardsRow.addView(axisCard, axCardLp);

        // ALLIED CARD
        LinearLayout alliedCard = new LinearLayout(this);
        alliedCard.setOrientation(LinearLayout.VERTICAL);
        alliedCard.setPadding(24, 20, 24, 20);
        GradientDrawable alCardBg = new GradientDrawable();
        alCardBg.setColor(0xFF221F28);
        alCardBg.setCornerRadius(12f);
        alCardBg.setStroke(2, 0xFF90CAF9);
        alliedCard.setBackground(alCardBg);

        LinearLayout alHdr = new LinearLayout(this);
        alHdr.setOrientation(LinearLayout.HORIZONTAL);
        alHdr.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ivAlFlag = new ImageView(this);
        Bitmap alFlagBmp = assets.getTrimmedSprite("FLAGS.SHP", Math.max(0, alliedPrimaryNat - 1));
        if (alFlagBmp != null) ivAlFlag.setImageBitmap(Bitmap.createScaledBitmap(alFlagBmp, 52, 38, false));
        alHdr.addView(ivAlFlag, new LinearLayout.LayoutParams(52, 38));

        TextView tvAlTitle = new TextView(this);
        tvAlTitle.setText("  ALLIED COMMAND");
        tvAlTitle.setTextColor(0xFF90CAF9);
        tvAlTitle.setTextSize(16f);
        tvAlTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        alHdr.addView(tvAlTitle);
        alliedCard.addView(alHdr);

        TextView tvAlBody = new TextView(this);
        tvAlBody.setText(String.format(Locale.ROOT,
                "\n• Forces: %s\n• Tactical Role: %s\n• Starting Prestige: ★ %d\n• Core Command: Allied Forces & National Arsenal",
                alNatsSb.toString(), alRole, alPrestigeVal));
        tvAlBody.setTextColor(0xFFCFD8DC);
        tvAlBody.setTextSize(13f);
        tvAlBody.setLineSpacing(4f, 1f);
        alliedCard.addView(tvAlBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button btnPlayAllied = createCompactButton("⚔ PLAY AS ALLIED", 0xFF1565C0);
        btnPlayAllied.setTextSize(14f);
        btnPlayAllied.setPadding(0, 14, 0, 14);
        btnPlayAllied.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
            try {
                engine.clearGameCache(this);
                engine.startSingleScenario(scenNum, false);
                try {
                    engine.saveGameCache(this);
                } catch (Exception ignored) {}
                launchTacticalScreen();
            } catch (IOException e) {
                Toast.makeText(this, "Error starting scenario: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        alliedCard.addView(btnPlayAllied, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams alCardLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        alCardLp.setMargins(12, 0, 0, 0);
        cardsRow.addView(alliedCard, alCardLp);

        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);
        footerRow.setGravity(Gravity.END);

        Button btnBack = createCompactButton("❮ BACK TO SCENARIOS", 0xFF37474F);
        btnBack.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
            showScenarioSelectDialog();
        });
        footerRow.addView(btnBack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 60));

        Button btnCancel = createCompactButton("✕ CANCEL", 0xFF7F2424);
        btnCancel.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 60);
        cancelLp.setMargins(14, 0, 0, 0);
        footerRow.addView(btnCancel, cancelLp);

        root.addView(footerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        dialog.show();
    }

    private void launchTacticalScreen() {
        if (titleOverlay != null) {
            titleOverlay.setVisibility(View.GONE);
        }
        lastTurnLossSummary = null;
        mapView.purchasePlacementMode = false;
        mapView.strategicMapOverlay = false;
        mapView.clearSelection();

        // Center camera on first Axis unit
        boolean centered = false;
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u != null && engine.isPlayerUnit(u) && u.strength > 0 && u.col >= 0 && u.row >= 0) {
                mapView.post(() -> {
                    mapView.centerOnHex(u.col, u.row);
                    mapView.selectUnit(u);
                });
                centered = true;
                break;
            }
        }
        if (!centered && engine.scenario != null && !engine.scenario.deployHexes.isEmpty()) {
            int[] dh = engine.scenario.deployHexes.get(0);
            mapView.post(() -> mapView.centerOnHex(dh[0], dh[1]));
        }
        sounds.startMusic(engine.currentScenarioNumber % 6);
        refreshHud();
        showBriefingDialog();
    }

    public void refreshHud() {
        if (engine.scenario == null) return;
        PgDatabase.ScenarioMeta m = engine.scenario.meta;
        tvScenarioTitle.setText(String.format(Locale.ROOT, "#%d %s (%s)",
                m.number, m.fullTitle, engine.isCampaignMode ? "Campaign" : (engine.playerIsAxis ? "Scenario • Axis" : "Scenario • Allied")));

        if (isAiTurnRunning) {
            String enemySide = engine.getEnemySideName().toUpperCase(Locale.ROOT);
            tvTurnWeather.setText(String.format(Locale.ROOT, "\u2605 %s TURN IN PROGRESS (Turn %d/%d) \u2605", enemySide, engine.turn, engine.maxTurns));
            btnNextUnit.setText("WAIT");
            btnNextUnit.setBackgroundColor(0xFF37474F);
            btnEndTurn.setText(enemySide + " TURN");
            btnEndTurn.setBackgroundColor(0xFF8E2424);
        } else if (engine.isDeploymentPhase) {
            tvTurnWeather.setText(String.format(Locale.ROOT, "★ DEPLOYMENT PHASE ★ • %d in Reserve", engine.unplacedCoreUnits.size()));
            btnNextUnit.setText("RESERVES (" + engine.unplacedCoreUnits.size() + ")");
            btnNextUnit.setBackgroundColor(0xFFF57F17);
            btnEndTurn.setText("START BATTLE");
            btnEndTurn.setBackgroundColor(0xFF2E7D32);
        } else {
            tvTurnWeather.setText(String.format(Locale.ROOT, "Turn %d/%d • %02d/%02d/%d • %s",
                    engine.turn, engine.maxTurns, m.month, m.day, m.year, engine.getWeatherLabel()));
            btnNextUnit.setText("NEXT");
            btnNextUnit.setBackgroundColor(0xFF283593);
            btnEndTurn.setText("END TURN");
            btnEndTurn.setBackgroundColor(0xFF8E2424);
        }

        tvPrestige.setText(String.format(Locale.ROOT, "★ %d Prestige", engine.getPlayerPrestige()));

        int heldObj = 0;
        for (int[] obj : engine.scenario.objectives) {
            if (engine.isPlayerOwnedHex(engine.scenario.grid[obj[0]][obj[1]])) {
                heldObj++;
            }
        }
        tvObjectivesCount.setText(String.format(Locale.ROOT, "Objectives: %d/%d",
                heldObj, engine.scenario.objectives.size()));

        if (btnRoster != null) {
            if (engine.isDeploymentPhase) {
                btnRoster.setVisibility(View.GONE);
            } else {
                btnRoster.setVisibility(View.VISIBLE);
                int playerCount = 0;
                for (PgDatabase.ActiveUnit au : engine.units) {
                    if (au != null && engine.isPlayerUnit(au) && au.strength > 0) playerCount++;
                }
                btnRoster.setText("ROSTER (" + playerCount + ")");
            }
        }

        updateUnitInspector(mapView.selectedCol, mapView.selectedRow, mapView.selectedUnit);

        if (btnAirToggle != null && mapView != null) {
            btnAirToggle.setText(mapView.isShowingAirOnTop() ? "AIR TOP" : "GND TOP");
        }
    }

    private void setUnitActionButtonAvailable(Button btn, boolean available) {
        if (btn == null) return;
        btn.setEnabled(available);
        btn.setVisibility(available ? View.VISIBLE : View.GONE);
    }

    private void updateCompactButtonColor(Button btn, int bgColor) {
        if (btn == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(8f);
        gd.setStroke(1, 0xFF90A4AE);
        btn.setBackground(gd);
    }

    private void updateUnitInspector(int col, int row, PgDatabase.ActiveUnit u) {
        if (isAiTurnRunning) {
            setHudControlsEnabled(false);
            setUnitActionButtonAvailable(btnUndo, false);
            setUnitActionButtonAvailable(btnSupply, false);
            setUnitActionButtonAvailable(btnReinforce, false);
            setUnitActionButtonAvailable(btnElite, false);
            setUnitActionButtonAvailable(btnUpgrade, false);
            setUnitActionButtonAvailable(btnMount, false);
            if (u != null) {
                PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
                Bitmap bmp = assets.getSprite("TACICONS.SHP", engine.getEffectiveIconId(u), u.facingLeft);
                ivSelectedUnitIcon.setImageBitmap(bmp);
                PgDatabase.HexCell cell = (engine.scenario != null && u.col >= 0 && u.col < engine.scenario.cols && u.row >= 0 && u.row < engine.scenario.rows)
                        ? engine.scenario.grid[u.col][u.row] : null;
                String sideTag = u.isAxis ? (u.isCore ? "[AXIS CORE]" : "[AXIS AUX]") : "[ALLIED]";
                int maxStr = u.getMaxOverstrength();
                tvSelectedTitle.setText(String.format(Locale.ROOT, "%s %s  |  Str: %d/%d (Sup:%d)  |  Exp: %d (%d★)",
                        sideTag, def.name, u.strength, maxStr, u.suppression, u.experience, u.getStars()));
                PgDatabase.UnitDef effDef = engine.getEffectiveUnitDef(u);
                String hexPosStr = cell != null ? String.format(Locale.ROOT, "Hex(%d,%d) %s", u.col, u.row, cell.name) : "";
                tvSelectedStats.setText(String.format(Locale.ROOT,
                        "SA:%d HA:%d AA:%d GD:%d AD:%d CD:%d • Rng:%d Ini:%d • %s",
                        effDef.softAttack, effDef.hardAttack, effDef.airAttack,
                        effDef.groundDefense, effDef.airDefense, effDef.closeDefense,
                        effDef.range, effDef.initiative, hexPosStr));
            }
            return;
        }
        setUnitActionButtonAvailable(btnUndo, engine.canUndoLastMove());
        if (u != null) {
            PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
            Bitmap bmp = assets.getSprite("TACICONS.SHP", engine.getEffectiveIconId(u), u.facingLeft);
            ivSelectedUnitIcon.setImageBitmap(bmp);
            PgDatabase.HexCell cell = (engine.scenario != null && u.col >= 0 && u.col < engine.scenario.cols && u.row >= 0 && u.row < engine.scenario.rows)
                    ? engine.scenario.grid[u.col][u.row] : null;
            String sideTag = u.isAxis ? (u.isCore ? "[AXIS CORE]" : "[AXIS AUX]") : "[ALLIED]";
            String trpTag = "";
            if (u.inAirTransport) trpTag = " • [AIRBORNE Ju 52]";
            else if (u.inSeaTransport) trpTag = " • [NAVAL TRANSPORT]";
            else if (u.transportDefId > 0) {
                trpTag = " • Trp: " + db.equipment.get(u.transportDefId).name + (u.mountedInTransport ? " (Mounted)" : "");
            }
            if (mapView.pendingOverrunUnit != null && mapView.pendingOverrunUnit == u) {
                trpTag += " • [OVERRUN: TAP HEX TO MOVE]";
            }
            int maxStr = u.getMaxOverstrength();
            tvSelectedTitle.setText(String.format(Locale.ROOT, "%s %s  |  Str: %d/%d (Sup:%d)  |  Exp: %d (%d★)  |  Ent: %d%s",
                    sideTag, def.name, u.strength, maxStr, u.suppression, u.experience, u.getStars(), u.entrenchment, trpTag));

            PgDatabase.UnitDef effDef = engine.getEffectiveUnitDef(u);
            String className = (effDef.unitClass >= 0 && effDef.unitClass < PgDatabase.CLASS_NAMES.length)
                    ? PgDatabase.CLASS_NAMES[effDef.unitClass] : "Unit";
            String supplyInfo = engine.supplyEnabled
                    ? String.format(Locale.ROOT, "Ammo:%d Fuel:%d", u.ammo, u.fuel)
                    : "Ammo:∞ Fuel:∞ (Supply OFF)";
            int remMov = engine.getRemainingMovementPoints(u);
            int maxMov = engine.getMaxMovementPoints(u);
            String hexPosStr = cell != null ? String.format(Locale.ROOT, "Hex(%d,%d) %s", u.col, u.row, cell.name) : "[In Reserve Pool]";
            tvSelectedStats.setText(String.format(Locale.ROOT,
                    "%s • SA:%d HA:%d AA:%d GD:%d AD:%d CD:%d • Rng:%d Ini:%d Mov:%d/%d • %s • %s",
                    className, effDef.softAttack, effDef.hardAttack, effDef.airAttack,
                    effDef.groundDefense, effDef.airDefense, effDef.closeDefense,
                    effDef.range, effDef.initiative, remMov, maxMov, supplyInfo,
                    hexPosStr));

            if (engine.isDeploymentPhase) {
                setUnitActionButtonAvailable(btnUndo, false);
                boolean canUndeploy = engine.isPlayerUnit(u) && u.isCore && u.col >= 0 && u.row >= 0;
                if (canUndeploy) {
                    btnSupply.setText("UNDEPLOY");
                    updateCompactButtonColor(btnSupply, 0xFFD32F2F);
                }
                setUnitActionButtonAvailable(btnSupply, canUndeploy);

                setUnitActionButtonAvailable(btnReinforce, engine.canReinforceUnit(u, false));
                boolean canElite = engine.canReinforceUnit(u, true);
                if (canElite) {
                    btnElite.setText(u.strength >= 10 && u.getStars() > 0 ? "OVRST+1" : "ELITE+");
                }
                setUnitActionButtonAvailable(btnElite, canElite);
                setUnitActionButtonAvailable(btnUpgrade, engine.canUpgradeUnit(u));
                setUnitActionButtonAvailable(btnMount, false);
            } else if (mapView.pendingOverrunUnit != null && mapView.pendingOverrunUnit == u) {
                setUnitActionButtonAvailable(btnUndo, false);
                btnSupply.setText("STAY HERE");
                updateCompactButtonColor(btnSupply, 0xFF546E7A);
                setUnitActionButtonAvailable(btnSupply, true);
                setUnitActionButtonAvailable(btnReinforce, false);
                setUnitActionButtonAvailable(btnElite, false);
                setUnitActionButtonAvailable(btnUpgrade, false);
                setUnitActionButtonAvailable(btnMount, false);
            } else {
                boolean canResupply = engine.canResupplyUnit(u);
                if (canResupply) {
                    btnSupply.setText("SUPPLY");
                    updateCompactButtonColor(btnSupply, 0xFF2E5A36);
                }
                setUnitActionButtonAvailable(btnSupply, canResupply);

                setUnitActionButtonAvailable(btnReinforce, engine.canReinforceUnit(u, false));
                boolean canElite = engine.canReinforceUnit(u, true);
                if (canElite) {
                    btnElite.setText(u.strength >= 10 && u.getStars() > 0 ? "OVRST+1" : "ELITE+");
                }
                setUnitActionButtonAvailable(btnElite, canElite);

                setUnitActionButtonAvailable(btnUpgrade, engine.canUpgradeUnit(u));

                boolean canTransport = engine.canUseTransportAction(u);
                if (canTransport) {
                    btnMount.setText(engine.getTransportActionLabel(u));
                }
                setUnitActionButtonAvailable(btnMount, canTransport);
            }
        } else if (col >= 0 && row >= 0 && engine.scenario != null) {
            PgDatabase.HexCell cell = engine.scenario.grid[col][row];
            Bitmap tile = assets.getSprite("TACMAP.SHP", cell.tileSpriteId);
            ivSelectedUnitIcon.setImageBitmap(tile);
            tvSelectedTitle.setText(String.format(Locale.ROOT, "Hex (%d, %d): %s [%s]%s",
                    col, row, cell.name, cell.getTerrainName(),
                    cell.isObjective ? " ★ VICTORY OBJECTIVE" : ""));
            tvSelectedStats.setText(String.format(Locale.ROOT,
                    "Base Entrenchment: %d • Initiative Cap: %s • Supply Point: %s",
                    cell.getBaseEntrenchment(),
                    cell.getInitiativeCap() >= 99 ? "None" : String.valueOf(cell.getInitiativeCap()),
                    cell.isSupplyPoint() ? "Yes" : "No"));
            setUnitActionButtonAvailable(btnSupply, false);
            setUnitActionButtonAvailable(btnReinforce, false);
            setUnitActionButtonAvailable(btnElite, false);
            setUnitActionButtonAvailable(btnUpgrade, false);
            setUnitActionButtonAvailable(btnMount, false);
        } else {
            ivSelectedUnitIcon.setImageBitmap(null);
            tvSelectedTitle.setText("No Hex Selected");
            tvSelectedStats.setText("Tap any unit to view combat stats, move, or attack.");
            setUnitActionButtonAvailable(btnSupply, false);
            setUnitActionButtonAvailable(btnReinforce, false);
            setUnitActionButtonAvailable(btnElite, false);
            setUnitActionButtonAvailable(btnUpgrade, false);
            setUnitActionButtonAvailable(btnMount, false);
        }
    }

    @Override
    public void onHexSelected(int col, int row, PgDatabase.HexCell cell, PgDatabase.ActiveUnit unit) {
        refreshHud();
    }

    @Override
    public void onUnitMoved(PgDatabase.ActiveUnit unit, int col, int row) {
        refreshHud();
        checkScenarioCompletion();
        PgDatabase.HexCell cell = (engine.scenario != null && col >= 0 && col < engine.scenario.cols && row >= 0 && row < engine.scenario.rows)
                ? engine.scenario.grid[col][row] : null;
        if (unit != null && unit.isAxis && cell != null && (cell.terrainType == 8 || cell.terrainType == 6 || cell.isObjective)) {
            if (!engine.canUnitCaptureCityOrAirport(unit) && !db.isAxisNation(cell.ownerNation, engine.scenario)) {
                Toast.makeText(this, db.equipment.get(unit.unitDefId).name + " cannot capture cities or airports. Use infantry, recon, tanks, or AT guns.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onCombatInitiated(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender, PgGameEngine.CombatReport report) {
        refreshHud();
        checkScenarioCompletion();
    }

    @Override
    public void onOverrunMoveOption(PgDatabase.ActiveUnit attacker, PgGameEngine.CombatReport report) {
        if (isFinishing() || isDestroyed() || isAiTurnRunning || engine.scenario == null
                || !engine.isPlayerTurn || engine.scenarioOutcome != 0
                || attacker == null || attacker.strength <= 0 || !engine.units.contains(attacker)) {
            return;
        }

        Map<Integer, Integer> reach = engine.getReachableHexes(attacker);
        if (reach.size() <= 1) {
            attacker.moved = true;
            mapView.selectUnit(attacker);
            refreshHud();
            return;
        }

        PgDatabase.UnitDef def = db.equipment.get(attacker.unitDefId);
        String tankName = (attacker.customTitle != null && !attacker.customTitle.isEmpty())
                ? attacker.customTitle : (def != null ? def.name : "Tank");
        String defName = (report != null && report.defenderName != null) ? report.defenderName : "enemy unit";
        int remMp = engine.getRemainingMovementPoints(attacker);

        new AlertDialog.Builder(this)
                .setTitle("Tank Overrun!")
                .setMessage(String.format(Locale.ROOT,
                        "%s overran %s with 0 losses and retains its attack!\n\nWould you like to move %s (%d MP available)?",
                        tankName, defName, tankName, remMp))
                .setCancelable(false)
                .setPositiveButton("Move (Select Hex)", (d, w) -> {
                    sounds.playSelect();
                    mapView.startOverrunMoveSelection(attacker);
                    refreshHud();
                })
                .setNegativeButton("Stay Here", (d, w) -> {
                    sounds.playClick();
                    attacker.moved = true;
                    mapView.pendingOverrunUnit = null;
                    mapView.selectUnit(attacker);
                    refreshHud();
                })
                .show();
    }

    private void showTransportChoiceDialog(PgDatabase.ActiveUnit u, boolean canSea, boolean canAir) {
        if (u == null || !engine.units.contains(u) || u.strength <= 0) return;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        PgDatabase.UnitDef trp = (u.transportDefId > 0 && u.transportDefId < db.equipment.size())
                ? db.equipment.get(u.transportDefId) : null;

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (engine.canToggleOrganicTransport(u) && trp != null) {
            String mountLabel = u.mountedInTransport
                    ? String.format(Locale.ROOT, "Unmount %s (Foot Combat • %d MP)", trp.name, def.movement)
                    : String.format(Locale.ROOT, "Mount %s (Land Transport • %d MP)", trp.name, trp.movement);
            labels.add(mountLabel);
            actions.add(() -> {
                String msg = engine.executeOrganicTransportToggle(u);
                sounds.playSelect();
                mapView.selectUnit(u);
                refreshHud();
                if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        }

        if (canSea && engine.canSeaEmbark(u)) {
            int seaMp = Math.max(4, engine.getSeaTransportDef(u.isAxis).movement);
            labels.add(String.format(Locale.ROOT, "Sea Embark (Naval Transport • %d MP)", seaMp));
            actions.add(() -> {
                String msg = engine.executeSeaEmbark(u);
                sounds.playSelect();
                mapView.selectUnit(u);
                refreshHud();
                if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        }

        if (canAir && engine.canAirliftEmbark(u)) {
            int airMp = Math.max(10, engine.getAirTransportDef().movement);
            labels.add(String.format(Locale.ROOT, "Airlift (Ju 52 Air Transport • %d MP)", airMp));
            actions.add(() -> {
                String msg = engine.executeAirliftEmbark(u);
                sounds.playSelect();
                mapView.selectUnit(u);
                refreshHud();
                if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        }

        if (labels.isEmpty()) return;

        new AlertDialog.Builder(this)
                .setTitle("Transport Options — " + (def != null ? def.name : "Unit"))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onPurchasePlacementRequested(int col, int row) {
        List<int[]> validSpots = engine.getAvailableDeployHexes(mapView.pendingPurchaseUnitId);
        boolean ok = false;
        for (int[] s : validSpots) {
            if (s[0] == col && s[1] == row) {
                ok = true;
                break;
            }
        }
        PgDatabase.UnitDef pendingDef = (mapView.pendingPurchaseUnitId > 0 && mapView.pendingPurchaseUnitId < db.equipment.size())
                ? db.equipment.get(mapView.pendingPurchaseUnitId) : null;
        boolean isAir = pendingDef != null && pendingDef.isAir();

        if (!ok) {
            if (isAir) {
                Toast.makeText(this, "Planes can only be placed on or next to secure controlled airports free from enemy contact.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Ground units can only be placed on or next to secure controlled cities or supply areas free from enemy contact.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        PgDatabase.ActiveUnit created = engine.purchaseUnit(
                mapView.pendingPurchaseUnitId, mapView.pendingPurchaseTransportId, col, row);
        mapView.purchasePlacementMode = false;
        if (created != null) {
            sounds.playSelect();
            mapView.selectUnit(created);
            refreshHud();
            Toast.makeText(this, "Deployed " + db.equipment.get(created.unitDefId).name + " at (" + col + "," + row + ")",
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Cannot deploy on that hex.", Toast.LENGTH_SHORT).show();
        }
        mapView.invalidate();
    }

    @Override
    public void onCoreUnitDeployRequested(PgDatabase.ActiveUnit unit, int col, int row) {
        if (unit == null) return;
        if (engine.deployCoreUnit(unit, col, row)) {
            sounds.playSelect();
            boolean inSea = unit.inSeaTransport;
            boolean isAir = db.equipment.get(unit.unitDefId).isAir();
            boolean isWater = engine.scenario != null && engine.scenario.grid[col][row].isWater();
            String tag = inSea ? " [Naval Transport]" : (isAir && isWater ? " [Airborne Over Sea]" : "");
            String msg = "Deployed " + db.equipment.get(unit.unitDefId).name + " at (" + col + "," + row + ")" + tag;

            // Automatically pick the next unit in the reserve list to deploy
            PgDatabase.ActiveUnit nextUnit = null;
            if (!engine.unplacedCoreUnits.isEmpty()) {
                for (PgDatabase.ActiveUnit candidate : engine.unplacedCoreUnits) {
                    if (candidate != null && !engine.getValidCoreDeploymentHexes(candidate).isEmpty()) {
                        nextUnit = candidate;
                        break;
                    }
                }
                if (nextUnit == null) {
                    nextUnit = engine.unplacedCoreUnits.get(0);
                }
            }

            if (nextUnit != null) {
                mapView.pendingDeployCoreUnit = nextUnit;
                mapView.selectedUnit = nextUnit;
                mapView.selectedCol = -1;
                mapView.selectedRow = -1;
                mapView.reachableHexes.clear();
                mapView.validTargets.clear();

                PgDatabase.UnitDef nextDef = db.equipment.get(nextUnit.unitDefId);
                if (nextDef != null) {
                    mapView.airModePreferred = nextDef.isAir();
                    if (btnAirToggle != null) {
                        btnAirToggle.setText(nextDef.isAir() ? "AIR TOP" : "GND TOP");
                    }
                }

                String nextName = nextDef != null ? nextDef.name : "Unit";
                Toast.makeText(this, msg + "\nNext to deploy: " + nextName + " (" + engine.unplacedCoreUnits.size() + " in reserve)", Toast.LENGTH_SHORT).show();
            } else {
                mapView.pendingDeployCoreUnit = null;
                mapView.selectUnit(unit);
                if (engine.unplacedCoreUnits.isEmpty()) {
                    Toast.makeText(this, msg + "\n★ All core units deployed! Tap START BATTLE to begin. ★", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                }
            }
            refreshHud();
            mapView.invalidate();
        } else {
            // Hex was not valid for deploying this unit.
            // Check if player tapped an already deployed player unit to select / inspect / undeploy it:
            PgDatabase.ActiveUnit existing = engine.getUnitAt(col, row, false);
            if (existing == null) existing = engine.getUnitAt(col, row, true);
            if (existing != null && engine.isPlayerUnit(existing)) {
                sounds.playSelect();
                mapView.pendingDeployCoreUnit = null;
                mapView.selectUnit(existing);
                refreshHud();
                mapView.invalidate();
                return;
            }
            Toast.makeText(this, "Cannot deploy on that hex. Tap a highlighted deployment hex.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDeployHexTapped(int col, int row) {
        if (!engine.isDeploymentPhase) return;
        showCoreDeploymentDialog(col, row);
    }

    private void confirmStartBattle() {
        if (!engine.unplacedCoreUnits.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Start Battle Confirmation")
                    .setMessage(String.format(Locale.ROOT,
                            "You still have %d core unit(s) in reserve.\n\nAny unplaced core units will remain in reserve until the next scenario.\n\nAre you sure you want to lock in deployment and begin Turn 1?",
                            engine.unplacedCoreUnits.size()))
                    .setPositiveButton("Begin Turn 1", (d, w) -> {
                        engine.startBattleAfterDeployment();
                        sounds.playSelect();
                        mapView.pendingDeployCoreUnit = null;
                        refreshHud();
                        mapView.invalidate();
                        mapView.showYourTurnBanner(engine.turn, engine.maxTurns, engine.getWeatherLabel());
                        Toast.makeText(this, "★ Turn 1 Begins! ★", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Continue Deploying", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Begin Operation")
                    .setMessage("All core units have been deployed!\n\nLock in deployment and begin Turn 1?")
                    .setPositiveButton("Start Battle", (d, w) -> {
                        engine.startBattleAfterDeployment();
                        sounds.playSelect();
                        mapView.pendingDeployCoreUnit = null;
                        refreshHud();
                        mapView.invalidate();
                        mapView.showYourTurnBanner(engine.turn, engine.maxTurns, engine.getWeatherLabel());
                        Toast.makeText(this, "★ Turn 1 Begins! ★", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    public void showCoreDeploymentDialog() {
        showCoreDeploymentDialog(-1, -1);
    }

    public void showCoreDeploymentDialogForHex(int targetCol, int targetRow) {
        showCoreDeploymentDialog(targetCol, targetRow);
    }

    public void showCoreDeploymentDialog(final int targetCol, final int targetRow) {
        if (engine.scenario == null || !engine.isDeploymentPhase) return;
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final boolean hasTargetHex = (targetCol >= 0 && targetCol < engine.scenario.cols
                && targetRow >= 0 && targetRow < engine.scenario.rows);
        final PgDatabase.HexCell targetCell = hasTargetHex ? engine.scenario.grid[targetCol][targetRow] : null;
        final PgDatabase.ActiveUnit gndOcc = hasTargetHex ? engine.getUnitAt(targetCol, targetRow, false) : null;
        final PgDatabase.ActiveUnit airOcc = hasTargetHex ? engine.getUnitAt(targetCol, targetRow, true) : null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141915);
        root.setPadding(20, 14, 20, 20);

        // Header
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(16, 10, 16, 10);
        GradientDrawable hdrBg = new GradientDrawable();
        hdrBg.setColor(0xFF1F2721);
        hdrBg.setCornerRadius(10f);
        hdrBg.setStroke(2, 0xFFC5A059);
        headerRow.setBackground(hdrBg);

        ImageView ivFlag = new ImageView(this);
        Bitmap flagBmp = assets.getTrimmedSprite("FLAGS.SHP", engine.getPlayerFlagSpriteIndex());
        if (flagBmp != null) {
            ivFlag.setImageBitmap(Bitmap.createScaledBitmap(flagBmp, 48, 36, false));
        }
        LinearLayout.LayoutParams flagLp = new LinearLayout.LayoutParams(52, 40);
        flagLp.setMargins(0, 0, 14, 0);
        headerRow.addView(ivFlag, flagLp);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText("OKW RESERVE POOL — CORE DEPLOYMENT");
        tvHeaderTitle.setTextColor(0xFFFFD54F);
        tvHeaderTitle.setTextSize(15f);
        tvHeaderTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titleStack.addView(tvHeaderTitle);

        TextView tvSubHeader = new TextView(this);
        if (hasTargetHex) {
            String hexDesc = targetCell != null && targetCell.name != null && !targetCell.name.isEmpty()
                    ? targetCell.name + " (" + targetCell.getTerrainName() + ")"
                    : (targetCell != null ? targetCell.getTerrainName() : "");
            tvSubHeader.setText(String.format(Locale.ROOT,
                    "Target Hex: (%d, %d) %s   •   Reserve Units: %d remaining",
                    targetCol, targetRow, hexDesc, engine.unplacedCoreUnits.size()));
        } else {
            tvSubHeader.setText(String.format(Locale.ROOT,
                    "Reserve Core Units: %d remaining   •   Deploy onto marked deployment hexes",
                    engine.unplacedCoreUnits.size()));
        }
        tvSubHeader.setTextColor(0xFFB0BEC5);
        tvSubHeader.setTextSize(11.5f);
        titleStack.addView(tvSubHeader);
        headerRow.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Prestige Pill
        TextView tvPrestigePill = new TextView(this);
        tvPrestigePill.setText(String.format(Locale.ROOT, "★ %d PRESTIGE", engine.getPlayerPrestige()));
        tvPrestigePill.setTextColor(0xFF1B1F1B);
        tvPrestigePill.setTextSize(13.5f);
        tvPrestigePill.setTypeface(Typeface.DEFAULT_BOLD);
        tvPrestigePill.setPadding(16, 8, 16, 8);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xFFFFD54F);
        pillBg.setCornerRadius(8f);
        tvPrestigePill.setBackground(pillBg);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.setMargins(10, 0, 12, 0);
        headerRow.addView(tvPrestigePill, pillLp);

        Button btnStartBattleTop = createCompactButton("START BATTLE", 0xFF2E7D32);
        btnStartBattleTop.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
            confirmStartBattle();
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 60);
        sbLp.setMargins(0, 0, 10, 0);
        headerRow.addView(btnStartBattleTop, sbLp);

        Button btnClose = createCompactButton("✕ CLOSE", 0xFF7F2424);
        btnClose.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
            if (mapView.pendingDeployCoreUnit == null && !engine.unplacedCoreUnits.isEmpty()) {
                PgDatabase.ActiveUnit first = null;
                for (PgDatabase.ActiveUnit c : engine.unplacedCoreUnits) {
                    if (c != null && !engine.getValidCoreDeploymentHexes(c).isEmpty()) {
                        first = c;
                        break;
                    }
                }
                if (first == null) first = engine.unplacedCoreUnits.get(0);
                mapView.pendingDeployCoreUnit = first;
                mapView.selectedUnit = first;
                mapView.selectedCol = -1;
                mapView.selectedRow = -1;
                mapView.reachableHexes.clear();
                mapView.validTargets.clear();
                PgDatabase.UnitDef fDef = db.equipment.get(first.unitDefId);
                if (fDef != null) {
                    mapView.airModePreferred = fDef.isAir();
                    if (btnAirToggle != null) {
                        btnAirToggle.setText(fDef.isAir() ? "AIR TOP" : "GND TOP");
                    }
                }
                refreshHud();
                mapView.invalidate();
                String fName = fDef != null ? fDef.name : "Unit";
                Toast.makeText(MainActivity.this,
                        "Ready to deploy: " + fName + " (tap cyan hex)",
                        Toast.LENGTH_SHORT).show();
            }
        });
        headerRow.addView(btnClose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 60));
        root.addView(headerRow);

        // If target hex currently has a deployed core unit, show return-to-reserves bar
        PgDatabase.ActiveUnit coreOnTarget = null;
        if (gndOcc != null && gndOcc.isCore) coreOnTarget = gndOcc;
        else if (airOcc != null && airOcc.isCore) coreOnTarget = airOcc;

        if (hasTargetHex && coreOnTarget != null) {
            LinearLayout undepBar = new LinearLayout(this);
            undepBar.setOrientation(LinearLayout.HORIZONTAL);
            undepBar.setGravity(Gravity.CENTER_VERTICAL);
            undepBar.setPadding(16, 8, 16, 8);
            GradientDrawable undepBg = new GradientDrawable();
            undepBg.setColor(0xFF2C2219);
            undepBg.setCornerRadius(6f);
            undepBg.setStroke(1, 0xFFC5A059);
            undepBar.setBackground(undepBg);

            LinearLayout.LayoutParams ubLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ubLp.setMargins(0, 8, 0, 8);

            TextView tvUndep = new TextView(this);
            PgDatabase.UnitDef cDef = db.equipment.get(coreOnTarget.unitDefId);
            String cName = cDef != null ? cDef.name : "Unit";
            tvUndep.setText("Target hex currently occupied by: " + cName);
            tvUndep.setTextColor(0xFFFFCC80);
            tvUndep.setTextSize(12.5f);
            undepBar.addView(tvUndep, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnUndep = createCompactButton("RETURN " + cName.toUpperCase(Locale.ROOT) + " TO RESERVES", 0xFF8D6E63);
            final PgDatabase.ActiveUnit toReturn = coreOnTarget;
            btnUndep.setOnClickListener(v -> {
                sounds.playSelect();
                if (engine.undeployCoreUnit(toReturn)) {
                    mapView.selectedUnit = null;
                    refreshHud();
                    mapView.invalidate();
                    dialog.dismiss();
                    showCoreDeploymentDialog(targetCol, targetRow);
                }
            });
            undepBar.addView(btnUndep);
            root.addView(undepBar, ubLp);
        }

        // Content
        if (engine.unplacedCoreUnits.isEmpty()) {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            emptyBox.setPadding(30, 60, 30, 60);

            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("★ All Core Units Have Been Deployed! ★\n\nAll your veteran units are currently positioned on the battlefield.\nYou can tap any deployed core unit on the tactical map to undeploy or reposition it,\nor begin Turn 1 now.");
            tvEmpty.setTextColor(0xFFCFD8DC);
            tvEmpty.setTextSize(16f);
            tvEmpty.setGravity(Gravity.CENTER);
            emptyBox.addView(tvEmpty);

            Button btnStartBattleNow = createStyledButton("BEGIN BATTLE (TURN 1)", 0xFF2E7D32, 0xFFFFFFFF);
            btnStartBattleNow.setOnClickListener(v -> {
                sounds.playClick();
                dialog.dismiss();
                confirmStartBattle();
            });
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(400, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.setMargins(0, 30, 0, 0);
            emptyBox.addView(btnStartBattleNow, btnLp);

            root.addView(emptyBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            engine.sortUnplacedCoreUnits();
            ScrollView scroll = new ScrollView(this);
            LinearLayout listLayout = new LinearLayout(this);
            listLayout.setOrientation(LinearLayout.VERTICAL);
            listLayout.setPadding(0, 10, 0, 20);

            int lastClass = -1;
            for (PgDatabase.ActiveUnit u : engine.unplacedCoreUnits) {
                PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
                if (def == null) continue;

                if (def.unitClass != lastClass) {
                    lastClass = def.unitClass;
                    String categoryName = (def.unitClass >= 0 && def.unitClass < PgDatabase.CLASS_NAMES.length)
                            ? PgDatabase.CLASS_NAMES[def.unitClass] : "Unit";
                    TextView tvHeader = new TextView(this);
                    tvHeader.setText("── " + categoryName.toUpperCase(Locale.ROOT) + " ──");
                    tvHeader.setTextColor(0xFFFFD54F);
                    tvHeader.setTextSize(12.5f);
                    tvHeader.setTypeface(Typeface.DEFAULT_BOLD);
                    tvHeader.setPadding(10, 14, 10, 4);
                    listLayout.addView(tvHeader);
                }

                List<int[]> validHexes = engine.getValidCoreDeploymentHexes(u);
                boolean hasAnyDeployHex = !validHexes.isEmpty();
                boolean canDeployOnTargetHex = false;
                if (hasTargetHex) {
                    for (int[] h : validHexes) {
                        if (h[0] == targetCol && h[1] == targetRow) {
                            canDeployOnTargetHex = true;
                            break;
                        }
                    }
                }

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(14, 10, 14, 10);
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(0xFF1E2520);
                cardBg.setCornerRadius(8f);
                if (hasTargetHex && canDeployOnTargetHex) {
                    cardBg.setStroke(2, 0xFF4CAF50);
                } else {
                    cardBg.setStroke(1, 0xFF3E4F42);
                }
                card.setBackground(cardBg);

                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.setMargins(0, 4, 0, 4);

                // Unit Icon
                ImageView ivIcon = new ImageView(this);
                Bitmap iconBmp = assets.getSprite("TACICONS.SHP", engine.getEffectiveIconId(u), u.facingLeft);
                if (iconBmp != null) {
                    ivIcon.setImageBitmap(Bitmap.createScaledBitmap(iconBmp, 56, 44, false));
                }
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(56, 44);
                iconLp.setMargins(0, 0, 12, 0);
                card.addView(ivIcon, iconLp);

                // Info block
                LinearLayout infoCol = new LinearLayout(this);
                infoCol.setOrientation(LinearLayout.VERTICAL);

                String className = (def.unitClass >= 0 && def.unitClass < PgDatabase.CLASS_NAMES.length)
                        ? PgDatabase.CLASS_NAMES[def.unitClass] : "Unit";
                String stars = u.getStars() > 0 ? (" ★".repeat(u.getStars())) : "";
                String trpText = "";
                if (u.transportDefId > 0 && u.transportDefId < db.equipment.size()) {
                    trpText = " • Trp: " + db.equipment.get(u.transportDefId).name;
                }

                TextView tvName = new TextView(this);
                tvName.setText(String.format(Locale.ROOT, "%s  [%s%s]  •  Str: %d/%d (Exp: %d)%s",
                        def.name, className, stars, u.strength, u.getMaxOverstrength(), u.experience, trpText));
                tvName.setTextColor(0xFFFFD54F);
                tvName.setTextSize(14f);
                tvName.setTypeface(Typeface.DEFAULT_BOLD);
                infoCol.addView(tvName);

                TextView tvStats = new TextView(this);
                tvStats.setText(String.format(Locale.ROOT,
                        "SA:%d  HA:%d  AA:%d  GD:%d  AD:%d  CD:%d  •  Mov:%d  Rng:%d  Ini:%d  •  Ammo:%d  Fuel:%d",
                        def.softAttack, def.hardAttack, def.airAttack,
                        def.groundDefense, def.airDefense, def.closeDefense,
                        def.movement, def.range, def.initiative, u.ammo, u.fuel));
                tvStats.setTextColor(0xFFCFD8DC);
                tvStats.setTextSize(11.5f);
                infoCol.addView(tvStats);

                card.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                // Action buttons: Replenish, Elite, Upgrade, Deploy
                LinearLayout btnStack = new LinearLayout(this);
                btnStack.setOrientation(LinearLayout.HORIZONTAL);
                btnStack.setGravity(Gravity.CENTER_VERTICAL);

                if (engine.canReinforceUnit(u, false)) {
                    Button btnRepl = createCompactButton("REPL", 0xFF3A536B);
                    btnRepl.setOnClickListener(v -> {
                        sounds.playSelect();
                        engine.reinforceUnit(u, false);
                        refreshHud();
                        dialog.dismiss();
                        showCoreDeploymentDialog(targetCol, targetRow);
                    });
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 56);
                    blp.setMargins(4, 0, 4, 0);
                    btnStack.addView(btnRepl, blp);
                }

                if (engine.canReinforceUnit(u, true)) {
                    Button btnElite = createCompactButton(u.strength >= 10 && u.getStars() > 0 ? "OVRST+1" : "ELITE+", 0xFF5D4037);
                    btnElite.setOnClickListener(v -> {
                        sounds.playSelect();
                        engine.reinforceUnit(u, true);
                        refreshHud();
                        dialog.dismiss();
                        showCoreDeploymentDialog(targetCol, targetRow);
                    });
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 56);
                    blp.setMargins(4, 0, 4, 0);
                    btnStack.addView(btnElite, blp);
                }

                if (engine.canUpgradeUnit(u)) {
                    Button btnUpg = createCompactButton("UPGRADE", 0xFF4E342E);
                    btnUpg.setOnClickListener(v -> {
                        sounds.playClick();
                        dialog.dismiss();
                        showUpgradeUnitDialog(u);
                    });
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 56);
                    blp.setMargins(4, 0, 4, 0);
                    btnStack.addView(btnUpg, blp);
                }

                if (hasAnyDeployHex) {
                    Button btnDeploy;
                    if (hasTargetHex && canDeployOnTargetHex) {
                        btnDeploy = createCompactButton("DEPLOY HERE", 0xFF2E7D32);
                        btnDeploy.setOnClickListener(v -> {
                            sounds.playClick();
                            dialog.dismiss();
                            onCoreUnitDeployRequested(u, targetCol, targetRow);
                        });
                    } else if (hasTargetHex) {
                        btnDeploy = createCompactButton("DEPLOY ELSEWHERE", 0xFF455A64);
                        btnDeploy.setOnClickListener(v -> {
                            sounds.playClick();
                            dialog.dismiss();
                            mapView.pendingDeployCoreUnit = u;
                            mapView.selectedUnit = u;
                            mapView.selectedCol = -1;
                            mapView.selectedRow = -1;
                            mapView.reachableHexes.clear();
                            mapView.validTargets.clear();
                            mapView.airModePreferred = def.isAir();
                            if (btnAirToggle != null) {
                                btnAirToggle.setText(def.isAir() ? "AIR TOP" : "GND TOP");
                            }
                            refreshHud();
                            mapView.invalidate();
                            Toast.makeText(MainActivity.this,
                                    "Tap any highlighted deployment hex (cyan) to place " + def.name,
                                    Toast.LENGTH_LONG).show();
                        });
                    } else {
                        btnDeploy = createCompactButton("DEPLOY ON MAP", 0xFF00695C);
                        btnDeploy.setOnClickListener(v -> {
                            sounds.playClick();
                            dialog.dismiss();
                            mapView.pendingDeployCoreUnit = u;
                            mapView.selectedUnit = u;
                            mapView.selectedCol = -1;
                            mapView.selectedRow = -1;
                            mapView.reachableHexes.clear();
                            mapView.validTargets.clear();
                            mapView.airModePreferred = def.isAir();
                            if (btnAirToggle != null) {
                                btnAirToggle.setText(def.isAir() ? "AIR TOP" : "GND TOP");
                            }
                            refreshHud();
                            mapView.invalidate();
                            Toast.makeText(MainActivity.this,
                                    "Tap any highlighted deployment hex (cyan) to place " + def.name,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                    LinearLayout.LayoutParams depLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 56);
                    depLp.setMargins(6, 0, 0, 0);
                    btnStack.addView(btnDeploy, depLp);
                }

                card.addView(btnStack);
                listLayout.addView(card, cardLp);
            }

            scroll.addView(listLayout);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        dialog.setContentView(root);
        dialog.show();
    }

    private void selectNextUnmovedUnit() {
        if (engine.units.isEmpty() || !engine.isPlayerTurn) return;
        if (mapView.pendingOverrunUnit != null) {
            mapView.cancelOverrunMoveSelection();
        }
        int startIdx = 0;
        if (mapView.selectedUnit != null) {
            int idx = engine.units.indexOf(mapView.selectedUnit);
            if (idx >= 0) startIdx = (idx + 1) % engine.units.size();
        }
        for (int i = 0; i < engine.units.size(); i++) {
            PgDatabase.ActiveUnit u = engine.units.get((startIdx + i) % engine.units.size());
            if (u != null && engine.hasAvailableAction(u)) {
                if (u == mapView.selectedUnit) {
                    Toast.makeText(this, "Only " + db.equipment.get(u.unitDefId).name + " has actions remaining.", Toast.LENGTH_SHORT).show();
                }
                mapView.centerOnHex(u.col, u.row);
                mapView.selectUnit(u);
                refreshHud();
                return;
            }
        }
        Toast.makeText(this, "All Axis units have completed their orders this turn.", Toast.LENGTH_SHORT).show();
    }

    private TextView createRosterStatusPill(String text, int bgColor, int strokeColor, int textColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(12, 5, 12, 5);
        tv.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(6f);
        bg.setStroke(1, strokeColor);
        tv.setBackground(bg);
        return tv;
    }

    private void activateUnitFromRoster(PgDatabase.ActiveUnit u) {
        if (u == null || engine.scenario == null || isAiTurnRunning) return;
        if (u.col < 0 || u.row < 0) {
            if (mapView.pendingOverrunUnit != null) {
                mapView.cancelOverrunMoveSelection();
            }
            mapView.purchasePlacementMode = false;
            mapView.strategicMapOverlay = false;
            mapView.pendingDeployCoreUnit = u;
            mapView.invalidate();
            refreshHud();
            PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
            Toast.makeText(this,
                    "Tap any highlighted deployment hex (cyan) to place " + (def != null ? def.name : "unit"),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (mapView.pendingOverrunUnit != null && mapView.pendingOverrunUnit != u) {
            mapView.cancelOverrunMoveSelection();
        }
        mapView.purchasePlacementMode = false;
        mapView.pendingDeployCoreUnit = null;
        mapView.strategicMapOverlay = false;

        boolean onAir = engine.isUnitOnAirLayer(u);
        mapView.airModePreferred = onAir;
        if (btnAirToggle != null) {
            btnAirToggle.setText(onAir ? "AIR TOP" : "GND TOP");
        }

        mapView.centerOnHex(u.col, u.row);
        mapView.selectUnit(u);
        refreshHud();
    }

    public void showUnitRosterDialog() {
        if (engine.scenario == null || isAiTurnRunning || engine.isDeploymentPhase) return;

        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Gather all available Axis units in the scenario (deployed + unplaced reserves in deployment phase)
        final List<PgDatabase.ActiveUnit> allUnits = new ArrayList<>();
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u != null && engine.isPlayerUnit(u) && u.strength > 0) {
                allUnits.add(u);
            }
        }
        if (engine.isDeploymentPhase && !engine.unplacedCoreUnits.isEmpty()) {
            for (PgDatabase.ActiveUnit u : engine.unplacedCoreUnits) {
                if (u != null && u.strength > 0) {
                    allUnits.add(u);
                }
            }
        }

        // Sort by Reserve status (deployed first, then reserves), then unit class, then Core before Aux, then experience desc
        Collections.sort(allUnits, (a, b) -> {
            boolean aRes = (a.col < 0 || a.row < 0);
            boolean bRes = (b.col < 0 || b.row < 0);
            if (aRes != bRes) return aRes ? 1 : -1;
            PgDatabase.UnitDef da = db.equipment.get(a.unitDefId);
            PgDatabase.UnitDef dbDef = db.equipment.get(b.unitDefId);
            int ca = da != null ? da.unitClass : 99;
            int cb = dbDef != null ? dbDef.unitClass : 99;
            if (ca != cb) return Integer.compare(ca, cb);
            if (a.isCore != b.isCore) return a.isCore ? -1 : 1;
            if (a.experience != b.experience) return Integer.compare(b.experience, a.experience);
            return Integer.compare(a.instanceId, b.instanceId);
        });

        int canMoveCount = 0;
        int notAttackedCount = 0;
        int canActCount = 0;
        int groundCount = 0;
        int airNavalCount = 0;

        for (PgDatabase.ActiveUnit u : allUnits) {
            PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
            if (def == null) continue;
            boolean isAirOrNav = def.isAir() || def.isNaval() || u.inAirTransport || u.inSeaTransport;
            if (isAirOrNav) airNavalCount++;
            else groundCount++;

            boolean inReserve = (u.col < 0 || u.row < 0);
            if (!inReserve) {
                int remMp = engine.getRemainingMovementPoints(u);
                if (!engine.isDeploymentPhase && !def.isFortification() && !u.moved && remMp > 0) {
                    canMoveCount++;
                }
                if (!engine.isDeploymentPhase && !u.attacked) {
                    notAttackedCount++;
                }
                if (engine.isDeploymentPhase || (!def.isFortification() && !u.moved && remMp > 0) || !u.attacked) {
                    canActCount++;
                }
            } else {
                canActCount++;
            }
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141915);
        root.setPadding(20, 14, 20, 20);

        // Header Bar
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(16, 10, 16, 10);
        GradientDrawable hdrBg = new GradientDrawable();
        hdrBg.setColor(0xFF1F2721);
        hdrBg.setCornerRadius(10f);
        hdrBg.setStroke(2, 0xFFC5A059);
        headerRow.setBackground(hdrBg);

        ImageView ivFlag = new ImageView(this);
        Bitmap flagBmp = assets.getTrimmedSprite("FLAGS.SHP", engine.getPlayerFlagSpriteIndex());
        if (flagBmp != null) {
            ivFlag.setImageBitmap(Bitmap.createScaledBitmap(flagBmp, 48, 36, false));
        }
        LinearLayout.LayoutParams flagLp = new LinearLayout.LayoutParams(52, 40);
        flagLp.setMargins(0, 0, 14, 0);
        headerRow.addView(ivFlag, flagLp);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText((engine.playerIsAxis ? "AXIS FORCES ROSTER — " : "ALLIED FORCES ROSTER — ") + engine.scenario.meta.fullTitle.toUpperCase(Locale.ROOT));
        tvHeaderTitle.setTextColor(0xFFFFD54F);
        tvHeaderTitle.setTextSize(15f);
        tvHeaderTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titleStack.addView(tvHeaderTitle);

        TextView tvSubHeader = new TextView(this);
        if (engine.isDeploymentPhase) {
            int onMap = allUnits.size() - engine.unplacedCoreUnits.size();
            tvSubHeader.setText(String.format(Locale.ROOT,
                    "Deployment Phase   •   Total Units: %d (%d On Map, %d In Reserve)   •   Tap any unit to select it",
                    allUnits.size(), onMap, engine.unplacedCoreUnits.size()));
        } else {
            tvSubHeader.setText(String.format(Locale.ROOT,
                    "Turn %d/%d   •   Available Units: %d   •   Can Move: %d   •   Not Attacked: %d   •   Tap a unit to make it active",
                    engine.turn, engine.maxTurns, allUnits.size(), canMoveCount, notAttackedCount));
        }
        tvSubHeader.setTextColor(0xFFB0BEC5);
        tvSubHeader.setTextSize(11.5f);
        titleStack.addView(tvSubHeader);
        headerRow.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!engine.isDeploymentPhase) {
            Button btnNextReady = createCompactButton("NEXT READY", 0xFF283593);
            btnNextReady.setOnClickListener(v -> {
                sounds.playClick();
                dialog.dismiss();
                selectNextUnmovedUnit();
            });
            LinearLayout.LayoutParams nrLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 58);
            nrLp.setMargins(0, 0, 10, 0);
            headerRow.addView(btnNextReady, nrLp);
        }

        Button btnClose = createCompactButton("✕ CLOSE", 0xFF7F2424);
        btnClose.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
        });
        headerRow.addView(btnClose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 58));
        root.addView(headerRow);

        // Filter Tabs Bar
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fbLp.setMargins(0, 10, 0, 6);

        final String[] filterLabels = {
            "ALL UNITS (" + allUnits.size() + ")",
            "UNMOVED / CAN ACT (" + canActCount + ")",
            "NOT ATTACKED (" + notAttackedCount + ")",
            "GROUND (" + groundCount + ")",
            "AIR & NAVAL (" + airNavalCount + ")"
        };
        final Button[] filterBtns = new Button[filterLabels.length];

        ScrollView scroll = new ScrollView(this);
        final LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(0, 4, 0, 20);
        scroll.addView(listLayout);

        final Runnable[] renderRoster = new Runnable[1];
        renderRoster[0] = () -> {
            for (int i = 0; i < filterBtns.length; i++) {
                boolean sel = (rosterFilterMode == i);
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(sel ? 0xFF2E5A44 : 0xFF1F2721);
                gd.setCornerRadius(8f);
                gd.setStroke(sel ? 2 : 1, sel ? 0xFFFFD54F : 0xFF455A64);
                filterBtns[i].setBackground(gd);
                filterBtns[i].setTextColor(sel ? 0xFFFFF59D : 0xFFCFD8DC);
            }

            listLayout.removeAllViews();
            int lastGroupKey = -999;
            int shownCount = 0;

            for (final PgDatabase.ActiveUnit u : allUnits) {
                PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
                if (def == null) continue;

                boolean inReserve = (u.col < 0 || u.row < 0);
                boolean isAirOrNav = def.isAir() || def.isNaval() || u.inAirTransport || u.inSeaTransport;
                int remMp = inReserve ? def.movement : engine.getRemainingMovementPoints(u);
                int maxMp = inReserve ? def.movement : engine.getMaxMovementPoints(u);
                boolean canStillMove = !inReserve && !engine.isDeploymentPhase && !def.isFortification() && !u.moved && remMp > 0;
                boolean hasNotAttacked = !inReserve && !engine.isDeploymentPhase && !u.attacked;

                if (rosterFilterMode == 1 && !(engine.isDeploymentPhase || inReserve || canStillMove || hasNotAttacked)) {
                    continue;
                }
                if (rosterFilterMode == 2 && !hasNotAttacked && !engine.isDeploymentPhase) {
                    continue;
                }
                if (rosterFilterMode == 3 && isAirOrNav) {
                    continue;
                }
                if (rosterFilterMode == 4 && !isAirOrNav) {
                    continue;
                }

                shownCount++;
                int groupKey = inReserve ? 1000 : def.unitClass;
                if (groupKey != lastGroupKey) {
                    lastGroupKey = groupKey;
                    String headerText;
                    if (inReserve) {
                        headerText = "── IN RESERVE POOL (NOT YET DEPLOYED) ──";
                    } else {
                        String catName = (def.unitClass >= 0 && def.unitClass < PgDatabase.CLASS_NAMES.length)
                                ? PgDatabase.CLASS_NAMES[def.unitClass] : "Units";
                        headerText = "── " + catName.toUpperCase(Locale.ROOT) + " ──";
                    }
                    TextView tvGroup = new TextView(this);
                    tvGroup.setText(headerText);
                    tvGroup.setTextColor(0xFFFFD54F);
                    tvGroup.setTextSize(12f);
                    tvGroup.setTypeface(Typeface.DEFAULT_BOLD);
                    tvGroup.setPadding(10, 12, 10, 4);
                    listLayout.addView(tvGroup);
                }

                final boolean isSelectedActive = (u == mapView.selectedUnit);
                boolean fullySpent = !inReserve && !engine.isDeploymentPhase
                        && (def.isFortification() || u.moved || remMp <= 0) && u.attacked;

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(14, 10, 14, 10);
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(isSelectedActive ? 0xFF28362B : (fullySpent ? 0xFF181D1A : 0xFF1E2621));
                cardBg.setCornerRadius(8f);
                if (isSelectedActive) {
                    cardBg.setStroke(2, 0xFFFFD54F);
                } else {
                    cardBg.setStroke(1, fullySpent ? 0xFF2F3832 : 0xFF435748);
                }
                card.setBackground(cardBg);

                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.setMargins(0, 4, 0, 4);

                // Unit Sprite
                ImageView ivIcon = new ImageView(this);
                Bitmap iconBmp = assets.getSprite("TACICONS.SHP", engine.getEffectiveIconId(u), u.facingLeft);
                if (iconBmp != null) {
                    ivIcon.setImageBitmap(Bitmap.createScaledBitmap(iconBmp, 56, 44, false));
                    ivIcon.setAlpha(fullySpent && !isSelectedActive ? 0.65f : 1.0f);
                }
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(56, 44);
                iconLp.setMargins(0, 0, 12, 0);
                card.addView(ivIcon, iconLp);

                // Unit Name, Strength, Stars & Hex Location
                LinearLayout infoCol = new LinearLayout(this);
                infoCol.setOrientation(LinearLayout.VERTICAL);

                String unitLabel = (u.customTitle != null && !u.customTitle.isEmpty())
                        ? (u.customTitle + " (" + def.name + ")")
                        : def.name;
                String coreTag = u.isCore ? "[CORE]" : "[AUX]";
                String stars = u.getStars() > 0 ? (" " + "★".repeat(u.getStars())) : "";
                String supStr = u.suppression > 0 ? (" (Sup:" + u.suppression + ")") : "";
                String activePrefix = isSelectedActive ? "★ [ACTIVE] " : "";

                TextView tvName = new TextView(this);
                tvName.setText(String.format(Locale.ROOT, "%s%s  %s%s  •  Str: %d/%d%s",
                        activePrefix, unitLabel, coreTag, stars, u.strength, u.getMaxOverstrength(), supStr));
                tvName.setTextColor(isSelectedActive ? 0xFFFFF59D : (fullySpent ? 0xFFB0BEC5 : 0xFFFFD54F));
                tvName.setTextSize(13.5f);
                tvName.setTypeface(Typeface.DEFAULT_BOLD);
                infoCol.addView(tvName);

                String locStr;
                if (inReserve) {
                    locStr = "Reserve Pool (Tap to deploy onto map)";
                } else {
                    PgDatabase.HexCell cell = (u.col >= 0 && u.col < engine.scenario.cols && u.row >= 0 && u.row < engine.scenario.rows)
                            ? engine.scenario.grid[u.col][u.row] : null;
                    String hexName = (cell != null && cell.name != null && !cell.name.isEmpty())
                            ? cell.name : (cell != null ? cell.getTerrainName() : "");
                    locStr = String.format(Locale.ROOT, "Hex (%d,%d) %s", u.col, u.row, hexName);
                }

                String trpInfo = "";
                if (u.inAirTransport) {
                    trpInfo = " • [Airborne Ju 52]";
                } else if (u.inSeaTransport) {
                    trpInfo = " • [Naval Transport]";
                } else if (u.transportDefId > 0 && u.transportDefId < db.equipment.size()) {
                    trpInfo = " • Trp: " + db.equipment.get(u.transportDefId).name + (u.mountedInTransport ? " (Mounted)" : "");
                }

                String supplyStr = engine.supplyEnabled
                        ? String.format(Locale.ROOT, " • Ammo:%d Fuel:%d", u.ammo, u.fuel)
                        : "";
                String entStr = (!inReserve && !def.isAir() && !def.isNaval())
                        ? (" • Ent:" + u.entrenchment) : "";

                TextView tvMeta = new TextView(this);
                tvMeta.setText(locStr + entStr + supplyStr + trpInfo);
                tvMeta.setTextColor(0xFFCFD8DC);
                tvMeta.setTextSize(11.5f);
                infoCol.addView(tvMeta);

                card.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                // Right-side Movement Status Pill, Attack Status Pill & Activate Button
                LinearLayout statusRow = new LinearLayout(this);
                statusRow.setOrientation(LinearLayout.HORIZONTAL);
                statusRow.setGravity(Gravity.CENTER_VERTICAL);

                // 1. Movement Status Pill
                TextView movePill;
                if (inReserve) {
                    movePill = createRosterStatusPill("IN RESERVE", 0xFF4E2A0E, 0xFFFFB74D, 0xFFFFE0B2);
                } else if (engine.isDeploymentPhase) {
                    movePill = createRosterStatusPill("DEPLOYED", 0xFF004D40, 0xFF4DB6AC, 0xFFE0F2F1);
                } else if (def.isFortification()) {
                    movePill = createRosterStatusPill("STATIC (0 MP)", 0xFF263238, 0xFF546E7A, 0xFFB0BEC5);
                } else if (u.moved || remMp <= 0) {
                    movePill = createRosterStatusPill("MOVED", 0xFF263238, 0xFF546E7A, 0xFF90A4AE);
                } else if (u.movePointsUsed > 0) {
                    movePill = createRosterStatusPill(
                            String.format(Locale.ROOT, "MOVED (%d/%d MP)", remMp, maxMp),
                            0xFF5D3A00, 0xFFFFB74D, 0xFFFFF3E0);
                } else {
                    movePill = createRosterStatusPill(
                            String.format(Locale.ROOT, "UNMOVED (%d MP)", remMp),
                            0xFF1B4D24, 0xFF66BB6A, 0xFFE8F5E9);
                }
                LinearLayout.LayoutParams mpLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mpLp.setMargins(6, 0, 4, 0);
                statusRow.addView(movePill, mpLp);

                // 2. Attack Status Pill
                TextView atkPill;
                if (engine.isDeploymentPhase || inReserve) {
                    atkPill = createRosterStatusPill("PRE-BATTLE", 0xFF263238, 0xFF546E7A, 0xFFB0BEC5);
                } else if (u.attacked) {
                    atkPill = createRosterStatusPill("ATTACKED", 0xFF263238, 0xFF546E7A, 0xFF90A4AE);
                } else {
                    int tgtCount = engine.getValidTargets(u).size();
                    if (tgtCount > 0) {
                        atkPill = createRosterStatusPill(
                                String.format(Locale.ROOT, "CAN ATTACK (%d TGT)", tgtCount),
                                0xFF6E1A1A, 0xFFFF8A80, 0xFFFFEBEE);
                    } else {
                        atkPill = createRosterStatusPill("NOT ATTACKED", 0xFF103B66, 0xFF64B5F6, 0xFFE3F2FD);
                    }
                }
                LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                apLp.setMargins(4, 0, 8, 0);
                statusRow.addView(atkPill, apLp);

                // 3. Select / Active Button
                String selBtnLabel = inReserve ? "DEPLOY" : (isSelectedActive ? "ACTIVE" : "SELECT");
                int selBtnColor = inReserve ? 0xFF00695C : (isSelectedActive ? 0xFF827717 : 0xFF2E5A44);
                Button btnSelect = createCompactButton(selBtnLabel, selBtnColor);
                View.OnClickListener selectAction = v -> {
                    sounds.playSelect();
                    dialog.dismiss();
                    activateUnitFromRoster(u);
                };
                btnSelect.setOnClickListener(selectAction);
                card.setOnClickListener(selectAction);

                LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, 52);
                statusRow.addView(btnSelect, sbLp);

                card.addView(statusRow);
                listLayout.addView(card, cardLp);
            }

            if (shownCount == 0) {
                TextView tvEmpty = new TextView(this);
                tvEmpty.setText("No units match the selected filter.");
                tvEmpty.setTextColor(0xFFB0BEC5);
                tvEmpty.setTextSize(14f);
                tvEmpty.setGravity(Gravity.CENTER);
                tvEmpty.setPadding(24, 48, 24, 48);
                listLayout.addView(tvEmpty);
            }
        };

        for (int i = 0; i < filterLabels.length; i++) {
            final int fIdx = i;
            Button fb = createCompactButton(filterLabels[i], 0xFF1F2721);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, 52, 1f);
            lp.setMargins(i == 0 ? 0 : 4, 0, i == filterLabels.length - 1 ? 0 : 4, 0);
            fb.setLayoutParams(lp);
            fb.setOnClickListener(v -> {
                sounds.playClick();
                rosterFilterMode = fIdx;
                renderRoster[0].run();
            });
            filterBtns[i] = fb;
            filterBar.addView(fb);
        }

        root.addView(filterBar, fbLp);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        renderRoster[0].run();
        dialog.setContentView(root);
        dialog.show();
    }

    private View createLossKpiPill(String title, String value, int valColor, int bgColor) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(10, 8, 10, 8);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(8f);
        gd.setStroke(1, 0xFF455A64);
        pill.setBackground(gd);

        TextView tvVal = new TextView(this);
        tvVal.setText(value);
        tvVal.setTextColor(valColor);
        tvVal.setTextSize(16f);
        tvVal.setTypeface(Typeface.DEFAULT_BOLD);
        tvVal.setGravity(Gravity.CENTER);
        pill.addView(tvVal);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(0xFFCFD8DC);
        tvTitle.setTextSize(10.5f);
        tvTitle.setGravity(Gravity.CENTER);
        pill.addView(tvTitle);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(4, 0, 4, 0);
        pill.setLayoutParams(lp);
        return pill;
    }

    private View createSectionHeaderView(String title, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(12, 12, 12, 6);

        View stripe = new View(this);
        stripe.setBackgroundColor(color);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(6, 26);
        sLp.setMargins(0, 0, 10, 0);
        row.addView(stripe, sLp);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(color);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        row.addView(tv);

        return row;
    }

    private View createEmptySectionBanner(String text, int textColor) {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(16, 10, 16, 10);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF1B221E);
        gd.setCornerRadius(6f);
        banner.setBackground(gd);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        banner.addView(tv);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(6, 2, 6, 6);
        banner.setLayoutParams(lp);
        return banner;
    }

    private View createBadgePill(String text, int textColor, int bgColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(9.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(8, 2, 8, 2);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(6f);
        tv.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(4, 0, 0, 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View createLossRecordCard(final PgTurnLossTracker.UnitLossRecord r, final Dialog dialog, boolean isFriendly, boolean isDamaged) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(12, 10, 12, 10);

        GradientDrawable cardBg = new GradientDrawable();
        int strokeColor;
        if (isFriendly) {
            if (isDamaged) {
                cardBg.setColor(0xFF22261E);
                strokeColor = 0xFFF57C00;
            } else {
                cardBg.setColor(0xFF2A1C1C);
                strokeColor = 0xFFD32F2F;
            }
        } else {
            cardBg.setColor(0xFF1B231F);
            strokeColor = 0xFF388E3C;
        }
        cardBg.setCornerRadius(8f);
        cardBg.setStroke(2, strokeColor);
        card.setBackground(cardBg);

        // Unit Sprite Icon
        ImageView ivIcon = new ImageView(this);
        Bitmap iconBmp = assets != null ? assets.getSprite("TACICONS.SHP", r.iconId) : null;
        if (iconBmp != null) {
            ivIcon.setImageBitmap(Bitmap.createScaledBitmap(iconBmp, 56, 44, false));
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(56, 44);
        iconLp.setMargins(0, 0, 12, 0);
        card.addView(ivIcon, iconLp);

        // Information Column
        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        // Row 1: Name, Class, Core badge, Stars
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setText(r.unitName != null ? r.unitName : "Unit");
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(13f);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        nameRow.addView(tvName);

        String className = (r.unitClass >= 0 && r.unitClass < PgDatabase.CLASS_NAMES.length)
                ? PgDatabase.CLASS_NAMES[r.unitClass] : "";
        if (!className.isEmpty()) {
            TextView tvClass = new TextView(this);
            tvClass.setText("  •  " + className);
            tvClass.setTextColor(0xFFB0BEC5);
            tvClass.setTextSize(11f);
            nameRow.addView(tvClass);
        }

        if (r.isCore) {
            nameRow.addView(createBadgePill("CORE", 0xFFFFD54F, 0x33F57F17));
        } else if (isFriendly) {
            nameRow.addView(createBadgePill("AUX", 0xFF90CAF9, 0x221565C0));
        }

        if (r.stars > 0) {
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < r.stars; s++) sb.append("★");
            TextView tvStars = new TextView(this);
            tvStars.setText(" " + sb.toString());
            tvStars.setTextColor(0xFFFFD54F);
            tvStars.setTextSize(11f);
            nameRow.addView(tvStars);
        }

        infoCol.addView(nameRow);

        // Row 2: Status & Strength metrics
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, 3, 0, 2);

        TextView tvStatus = new TextView(this);
        if (isFriendly) {
            if (isDamaged) {
                tvStatus.setText(String.format(Locale.ROOT, "Strength: %d / %d  (-%d lost)",
                        r.currentStrength, r.startStrength, r.strengthLost));
                tvStatus.setTextColor(0xFFFFB74D);
            } else {
                if (r.surrendered) {
                    tvStatus.setText(String.format(Locale.ROOT, "STATUS: SURRENDERED & DESTROYED (-%d str)", r.strengthLost));
                } else if (r.fuelCrash) {
                    tvStatus.setText(String.format(Locale.ROOT, "STATUS: FUEL EXHAUSTION CRASH (-%d str)", r.strengthLost));
                } else {
                    tvStatus.setText(String.format(Locale.ROOT, "STATUS: DESTROYED (-%d str)", r.strengthLost));
                }
                tvStatus.setTextColor(0xFFFF5252);
            }
        } else {
            if (isDamaged) {
                tvStatus.setText(String.format(Locale.ROOT, "STATUS: DAMAGED  (Remaining: %d, -%d str)",
                        r.currentStrength, r.strengthLost));
                tvStatus.setTextColor(0xFFA5D6A7);
            } else {
                tvStatus.setText(String.format(Locale.ROOT, "STATUS: DESTROYED (-%d str)", r.strengthLost));
                tvStatus.setTextColor(0xFF69F0AE);
            }
        }
        tvStatus.setTextSize(11.5f);
        tvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        statusRow.addView(tvStatus);

        if (r.retreated) {
            statusRow.addView(createBadgePill("RETREATED", 0xFFFFD54F, 0x33F57F17));
        }
        if (r.ruggedDefenseTriggered) {
            statusRow.addView(createBadgePill("RUGGED DEFENSE", 0xFF4DD0E1, 0x3300838F));
        }

        TextView tvLoc = new TextView(this);
        tvLoc.setText(String.format(Locale.ROOT, "   •   Hex (%d,%d)", r.col, r.row));
        tvLoc.setTextColor(0xFF90A4AE);
        tvLoc.setTextSize(11f);
        statusRow.addView(tvLoc);

        infoCol.addView(statusRow);

        // Row 3: Attack causes or notes
        if (!r.attackCauses.isEmpty()) {
            TextView tvCauses = new TextView(this);
            StringBuilder cb = new StringBuilder();
            for (int i = 0; i < r.attackCauses.size(); i++) {
                if (i > 0) cb.append("  |  ");
                cb.append(r.attackCauses.get(i));
            }
            tvCauses.setText(cb.toString());
            tvCauses.setTextColor(0xFFB0BEC5);
            tvCauses.setTextSize(10.5f);
            infoCol.addView(tvCauses);
        }

        card.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // If friendly and alive (damaged), show an interactive "LOCATE" button and make the card clickable
        if (isFriendly && isDamaged) {
            Button btnLocate = createCompactButton("📍 LOCATE", 0xFFC5A059);
            btnLocate.setTextColor(0xFF1B221D);
            btnLocate.setTextSize(10f);
            View.OnClickListener jumpAction = v -> {
                sounds.playSelect();
                dialog.dismiss();
                PgDatabase.ActiveUnit targetUnit = null;
                for (PgDatabase.ActiveUnit u : engine.units) {
                    if (u != null && u.instanceId == r.instanceId) {
                        targetUnit = u;
                        break;
                    }
                }
                if (targetUnit != null && targetUnit.col >= 0 && targetUnit.row >= 0) {
                    final PgDatabase.ActiveUnit sel = targetUnit;
                    mapView.centerOnHex(sel.col, sel.row);
                    mapView.selectUnit(sel);
                    refreshHud();
                    mapView.invalidate();
                    Toast.makeText(MainActivity.this, "Focused on " + r.unitName + " at (" + sel.col + "," + sel.row + ")", Toast.LENGTH_SHORT).show();
                }
            };
            btnLocate.setOnClickListener(jumpAction);
            card.setOnClickListener(jumpAction);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 56);
            bLp.setMargins(10, 0, 0, 0);
            card.addView(btnLocate, bLp);
        }

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(6, 3, 6, 3);
        card.setLayoutParams(cardLp);
        return card;
    }

    private View createCombatLogCard(PgTurnLossTracker.CombatLogEntry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(12, 8, 12, 8);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF1D221E);
        gd.setCornerRadius(8f);
        gd.setStroke(1, 0xFF37474F);
        card.setBackground(gd);

        // Header: Attacker vs Defender
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvVs = new TextView(this);
        String atkColorTag = entry.isAttackerPlayer ? "#81C784" : "#E57373";
        String defColorTag = entry.isAttackerPlayer ? "#E57373" : "#81C784";
        tvVs.setText(android.text.Html.fromHtml(String.format(Locale.ROOT,
                "<font color='%s'><b>%s</b></font> <font color='#B0BEC5'>attacked</font> <font color='%s'><b>%s</b></font> <font color='#90A4AE'>at (%d,%d)</font>",
                atkColorTag, entry.attackerName, defColorTag, entry.defenderName, entry.targetCol, entry.targetRow)));
        tvVs.setTextSize(12f);
        topRow.addView(tvVs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(topRow);

        // Result row
        LinearLayout resRow = new LinearLayout(this);
        resRow.setOrientation(LinearLayout.HORIZONTAL);
        resRow.setGravity(Gravity.CENTER_VERTICAL);
        resRow.setPadding(0, 4, 0, 0);

        TextView tvLosses = new TextView(this);
        tvLosses.setText(String.format(Locale.ROOT, "Casualties: Attacker -%d str   •   Defender -%d str",
                entry.attackerLosses, entry.defenderLosses));
        tvLosses.setTextColor(0xFFCFD8DC);
        tvLosses.setTextSize(11f);
        resRow.addView(tvLosses, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (entry.defenderDestroyed) {
            resRow.addView(createBadgePill("DESTROYED", 0xFFFF5252, 0x33D32F2F));
        } else if (entry.defenderSurrendered) {
            resRow.addView(createBadgePill("SURRENDERED", 0xFFFF7043, 0x33E64A19));
        } else if (entry.defenderRetreated) {
            resRow.addView(createBadgePill("RETREATED", 0xFFFFD54F, 0x33F57F17));
        }
        if (entry.attackerDestroyed) {
            resRow.addView(createBadgePill("ATK DESTROYED", 0xFFFF5252, 0x33D32F2F));
        }
        if (entry.ruggedDefense) {
            resRow.addView(createBadgePill("RUGGED DEFENSE", 0xFF4DD0E1, 0x3300838F));
        }
        if (entry.defensiveSupportFired) {
            String supText = entry.supportUnitName != null ? ("ARTILLERY: " + entry.supportUnitName) : "DEFENSIVE SUPPORT";
            resRow.addView(createBadgePill(supText, 0xFF81C784, 0x332E7D32));
        }

        card.addView(resRow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(6, 3, 6, 3);
        card.setLayoutParams(lp);
        return card;
    }

    public void showTurnLossSummaryDialog(final PgTurnLossTracker.TurnSummary summary) {
        if (summary == null || isFinishing() || isDestroyed()) return;

        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141915);
        root.setPadding(20, 14, 20, 20);

        // Header Bar
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(16, 10, 16, 10);
        GradientDrawable hdrBg = new GradientDrawable();
        hdrBg.setColor(0xFF1F2721);
        hdrBg.setCornerRadius(10f);
        hdrBg.setStroke(2, 0xFFC5A059);
        headerRow.setBackground(hdrBg);

        ImageView ivFlag = new ImageView(this);
        Bitmap flagBmp = assets != null ? assets.getTrimmedSprite("FLAGS.SHP", engine != null ? engine.getPlayerFlagSpriteIndex() : 0) : null;
        if (flagBmp != null) {
            ivFlag.setImageBitmap(Bitmap.createScaledBitmap(flagBmp, 48, 36, false));
        }
        LinearLayout.LayoutParams flagLp = new LinearLayout.LayoutParams(52, 40);
        flagLp.setMargins(0, 0, 14, 0);
        headerRow.addView(ivFlag, flagLp);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText(String.format(Locale.ROOT, "TURN %d AFTER-ACTION COMBAT & LOSS SUMMARY", summary.turnNumber));
        tvHeaderTitle.setTextColor(0xFFFFD54F);
        tvHeaderTitle.setTextSize(15f);
        tvHeaderTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titleStack.addView(tvHeaderTitle);

        TextView tvSubHeader = new TextView(this);
        tvSubHeader.setText(String.format(Locale.ROOT,
                "Enemy Turn Casualty Report   •   %s Forces   •   Tap any damaged unit to focus & select on map",
                summary.playerSideName));
        tvSubHeader.setTextColor(0xFFB0BEC5);
        tvSubHeader.setTextSize(11.5f);
        titleStack.addView(tvSubHeader);
        headerRow.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnCloseHeader = createCompactButton("✕ CLOSE", 0xFF7F2424);
        btnCloseHeader.setTextColor(Color.WHITE);
        btnCloseHeader.setTextSize(12f);
        btnCloseHeader.setPadding(18, 10, 18, 10);
        btnCloseHeader.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams closeHdrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeHdrLp.setMargins(14, 0, 0, 0);
        btnCloseHeader.setLayoutParams(closeHdrLp);
        headerRow.addView(btnCloseHeader);

        root.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Quick KPI Metric Pills (Total Player Units Lost, Total Player Str Lost, Total Enemy Units Lost, Total Enemy Str Lost)
        LinearLayout kpiBar = new LinearLayout(this);
        kpiBar.setOrientation(LinearLayout.HORIZONTAL);
        kpiBar.setPadding(0, 10, 0, 8);

        View pLossPill = createLossKpiPill("Friendly Destroyed", String.valueOf(summary.totalPlayerUnitsLost),
                summary.totalPlayerUnitsLost > 0 ? 0xFFFF5252 : 0xFF81C784,
                summary.totalPlayerUnitsLost > 0 ? 0x33D32F2F : 0x22388E3C);
        kpiBar.addView(pLossPill);

        View pStrLossPill = createLossKpiPill("Friendly Str Lost", "-" + summary.totalPlayerStrengthLost,
                summary.totalPlayerStrengthLost > 0 ? 0xFFFF7043 : 0xFFB0BEC5,
                summary.totalPlayerStrengthLost > 0 ? 0x33E64A19 : 0x2237474F);
        kpiBar.addView(pStrLossPill);

        View eLossPill = createLossKpiPill("Enemy Destroyed", String.valueOf(summary.totalEnemyUnitsLost),
                summary.totalEnemyUnitsLost > 0 ? 0xFF69F0AE : 0xFFB0BEC5,
                summary.totalEnemyUnitsLost > 0 ? 0x332E7D32 : 0x2237474F);
        kpiBar.addView(eLossPill);

        View eStrLossPill = createLossKpiPill("Enemy Str Lost", "-" + summary.totalEnemyStrengthLost,
                summary.totalEnemyStrengthLost > 0 ? 0xFFFFD54F : 0xFFB0BEC5,
                summary.totalEnemyStrengthLost > 0 ? 0x33F57F17 : 0x2237474F);
        kpiBar.addView(eStrLossPill);

        root.addView(kpiBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Scrollable sections container
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(0, 4, 0, 8);

        // Section 1: Friendly Units Destroyed
        contentLayout.addView(createSectionHeaderView("💥 FRIENDLY UNITS DESTROYED (" + summary.playerDestroyed.size() + ")", 0xFFFF5252));
        if (summary.playerDestroyed.isEmpty()) {
            contentLayout.addView(createEmptySectionBanner("✓ No friendly units were destroyed during the enemy turn. Perimeter held!", 0xFF81C784));
        } else {
            for (PgTurnLossTracker.UnitLossRecord r : summary.playerDestroyed) {
                contentLayout.addView(createLossRecordCard(r, dialog, true, false));
            }
        }

        // Section 2: Friendly Damaged Units
        contentLayout.addView(createSectionHeaderView("⚠️ FRIENDLY UNITS DAMAGED / ATTACKED (" + summary.playerDamaged.size() + ")", 0xFFFFB74D));
        if (summary.playerDamaged.isEmpty()) {
            contentLayout.addView(createEmptySectionBanner("✓ No friendly units sustained casualties this turn.", 0xFF81C784));
        } else {
            for (PgTurnLossTracker.UnitLossRecord r : summary.playerDamaged) {
                contentLayout.addView(createLossRecordCard(r, dialog, true, true));
            }
        }

        // Section 3: Enemy Casualties
        int totalEnemyCasualtyCards = summary.enemyDestroyed.size() + summary.enemyDamaged.size();
        contentLayout.addView(createSectionHeaderView("🎯 ENEMY CASUALTIES INFLICTED (" + totalEnemyCasualtyCards + ")", 0xFF69F0AE));
        if (totalEnemyCasualtyCards == 0) {
            contentLayout.addView(createEmptySectionBanner("No enemy casualties inflicted during defensive counter-fire.", 0xFF90A4AE));
        } else {
            for (PgTurnLossTracker.UnitLossRecord r : summary.enemyDestroyed) {
                contentLayout.addView(createLossRecordCard(r, dialog, false, false));
            }
            for (PgTurnLossTracker.UnitLossRecord r : summary.enemyDamaged) {
                contentLayout.addView(createLossRecordCard(r, dialog, false, true));
            }
        }

        // Section 4: Combat Engagement Log
        contentLayout.addView(createSectionHeaderView("📜 COMBAT ENGAGEMENT LOG (" + summary.combatLog.size() + ")", 0xFF90CAF9));
        if (summary.combatLog.isEmpty()) {
            contentLayout.addView(createEmptySectionBanner("No combat engagements took place during the enemy turn.", 0xFF90A4AE));
        } else {
            for (PgTurnLossTracker.CombatLogEntry entry : summary.combatLog) {
                contentLayout.addView(createCombatLogCard(entry));
            }
        }

        scrollView.addView(contentLayout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Footer Bar
        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);
        footerRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footerRow.setPadding(0, 12, 8, 16);

        Button btnCloseFooter = createCompactButton("✕ CLOSE", 0xFF7F2424);
        btnCloseFooter.setTextColor(Color.WHITE);
        btnCloseFooter.setTextSize(12.5f);
        btnCloseFooter.setPadding(24, 12, 24, 12);
        btnCloseFooter.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams closeFooterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeFooterLp.setMargins(0, 0, 14, 0);
        btnCloseFooter.setLayoutParams(closeFooterLp);
        footerRow.addView(btnCloseFooter);

        Button btnDismiss = createStyledButton("⚔️ RESUME BATTLE / PROCEED", 0xFFC5A059, 0xFF1B221D);
        btnDismiss.setTextSize(13f);
        btnDismiss.setPadding(26, 12, 26, 12);
        btnDismiss.setOnClickListener(v -> {
            sounds.playSelect();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnDismiss.setLayoutParams(btnLp);
        footerRow.addView(btnDismiss);

        root.addView(footerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        });
        dialog.setOnDismissListener(d -> hideSystemBars());

        dialog.setContentView(root);
        dialog.show();
    }

    private void performEndTurn() {
        if (isAiTurnRunning) return;
        if (mapView.pendingOverrunUnit != null) {
            mapView.cancelOverrunMoveSelection();
        }
        mapView.purchasePlacementMode = false;
        mapView.clearSelection();
        isAiTurnRunning = true;
        setHudControlsEnabled(false);
        String aiSideLabel = engine.playerIsAxis ? "ALLIED" : "AXIS";
        tvTurnWeather.setText(String.format(Locale.ROOT, "\u2605 %s TURN IN PROGRESS (Turn %d/%d) \u2605", aiSideLabel, engine.turn, engine.maxTurns));

        new Thread(() -> {
            try {
                Thread.sleep(300);
                turnLossTracker.beginEnemyTurn(engine, db);

                engine.endPlayerTurnAndRunAi(new PgGameEngine.AiTurnCallback() {
                    @Override
                    public void onAiUnitMoved(PgDatabase.ActiveUnit unit, int fromCol, int fromRow, int toCol, int toRow) {
                        boolean wasVis = fromCol >= 0 && fromRow >= 0 && engine.visibleHexes != null && fromCol < engine.scenario.cols && fromRow < engine.scenario.rows && engine.visibleHexes[fromCol][fromRow];
                        boolean nowVis = toCol >= 0 && toRow >= 0 && engine.visibleHexes != null && toCol < engine.scenario.cols && toRow < engine.scenario.rows && engine.visibleHexes[toCol][toRow];

                        if (wasVis || nowVis) {
                            if (!isFinishing() && !isDestroyed()) {
                                runOnUiThread(() -> {
                                    mapView.centerOnHex(toCol, toRow);
                                    mapView.selectUnit(unit);
                                    PgDatabase.UnitDef def = db.equipment.get(unit.unitDefId);
                                    sounds.playMoveSound(def);
                                    refreshHud();
                                    mapView.invalidate();
                                });
                            }
                            try {
                                Thread.sleep(380);
                            } catch (InterruptedException ignored) {}
                        }
                    }

                    @Override
                    public void onAiCombatExecuted(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender, PgGameEngine.CombatReport report) {
                        turnLossTracker.recordCombat(report, attacker, defender, engine, db);
                        boolean tVis = report.targetCol >= 0 && report.targetRow >= 0 && engine.visibleHexes != null && report.targetCol < engine.scenario.cols && report.targetRow < engine.scenario.rows && engine.visibleHexes[report.targetCol][report.targetRow];
                        boolean involvesAxis = (defender != null && defender.isAxis) || (attacker != null && attacker.isAxis);
                        boolean involvesPlayer = (defender != null && engine.isPlayerUnit(defender)) || (attacker != null && engine.isPlayerUnit(attacker));

                        if (tVis || involvesAxis || involvesPlayer) {
                            if (!isFinishing() && !isDestroyed()) {
                                if (report.supportReport != null) {
                                    // Step 1: Show defensive artillery fire on attacker first
                                    runOnUiThread(() -> {
                                        mapView.centerOnHex(report.supportReport.targetCol, report.supportReport.targetRow);
                                        if (report.supportReport.supportUnit != null) {
                                            mapView.selectUnit(report.supportReport.supportUnit);
                                        }
                                        PgDatabase.UnitDef sDef = report.supportReport.supportUnit != null ? db.equipment.get(report.supportReport.supportUnit.unitDefId) : null;
                                        sounds.playCombatSound(sDef);
                                        mapView.triggerExplosion(report.supportReport.targetCol, report.supportReport.targetRow, report.supportReport);
                                        refreshHud();
                                        mapView.invalidate();
                                    });
                                    try {
                                        Thread.sleep(850);
                                    } catch (InterruptedException ignored) {}
                                }

                                if (!report.attackerDestroyed || report.supportReport == null) {
                                    // Step 2: Show main combat attack on defender
                                    runOnUiThread(() -> {
                                        mapView.centerOnHex(report.targetCol, report.targetRow);
                                        if (attacker != null) {
                                            mapView.selectUnit(attacker);
                                        }
                                        PgDatabase.UnitDef aDef = attacker != null ? db.equipment.get(attacker.unitDefId) : null;
                                        sounds.playCombatSound(aDef);
                                        mapView.triggerExplosion(report.targetCol, report.targetRow, report);
                                        refreshHud();
                                        mapView.invalidate();
                                    });
                                    try {
                                        Thread.sleep(850);
                                    } catch (InterruptedException ignored) {}
                                }
                            }
                        }
                    }
                });

                Thread.sleep(400);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                final PgTurnLossTracker.TurnSummary summary = turnLossTracker.endEnemyTurnAndCompile(engine, db, engine.campaignManager.lastTurnCrashedUnits);
                lastTurnLossSummary = summary;
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> {
                        isAiTurnRunning = false;
                        setHudControlsEnabled(true);
                        mapView.clearSelection();
                        refreshHud();
                        checkScenarioCompletion();
                        if (engine.scenarioOutcome == 0 && engine.isPlayerTurn && engine.scenario != null) {
                            try {
                                engine.saveGameCache(MainActivity.this);
                            } catch (Exception ignored) {}
                            sounds.playSelect();
                            mapView.showYourTurnBanner(engine.turn, engine.maxTurns, engine.getWeatherLabel());
                            Toast.makeText(MainActivity.this, "\u2605 YOUR TURN! Turn " + engine.turn + " of " + engine.maxTurns + " \u2605", Toast.LENGTH_SHORT).show();
                            if (summary != null && summary.hasActivity()) {
                                showTurnLossSummaryDialog(summary);
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void checkScenarioCompletion() {
        if (engine.scenarioOutcome == 0 || engine.scenario == null) return;
        int outcome = engine.scenarioOutcome;
        engine.scenarioOutcome = 0; // prevent duplicate dialogs

        PgDatabase.ScenarioMeta meta = engine.scenario.meta;
        PgCampaignManager.CampaignStepResult step = engine.resolveScenarioOutcome(outcome);

        String title;
        if (!engine.isCampaignMode || step.hasNextScenario()) {
            if (outcome == 1) {
                title = "MAJOR VICTORY!";
            } else if (outcome == 2) {
                title = "MINOR VICTORY!";
            } else {
                title = (engine.isCampaignMode && step.hasNextScenario())
                        ? "TACTICAL DEFEAT — CAMPAIGN CONTINUES"
                        : "DEFEAT";
            }
        } else {
            switch (step.endType) {
                case DECISIVE_VICTORY:
                    title = "SUPREME CAMPAIGN VICTORY!";
                    break;
                case MAJOR_VICTORY:
                    title = "CAMPAIGN VICTORY!";
                    break;
                case ARMISTICE_DRAW:
                    title = "ARMISTICE SIGNED";
                    break;
                case HONORABLE_RETIRE:
                    title = "HONORABLE RETIREMENT";
                    break;
                default:
                    title = "DEFEAT";
                    break;
            }
        }

        int completedTurn = Math.min(engine.turn, engine.maxTurns);
        StringBuilder msg = new StringBuilder();
        msg.append("Scenario: ").append(meta.fullTitle).append("\n");
        msg.append("Completed on Turn ").append(completedTurn).append(" of ").append(engine.maxTurns).append("\n");
        if (step.bonusPrestige > 0 || outcome <= 2) {
            msg.append("Prestige Awarded: +").append(step.bonusPrestige).append("\n");
        }
        msg.append("\n");

        if (engine.isCampaignMode && !step.outcomeBriefingText.isEmpty()) {
            msg.append(step.outcomeBriefingText).append("\n\n");
        }

        if (engine.isCampaignMode) {
            if (step.requiresPlayerChoice()) {
                msg.append("High Command Strategic Directive: Choose your next theater of operations.");
            } else if (step.hasNextScenario()) {
                PgCampaignManager.BranchOption nextOpt = step.options.get(0);
                msg.append("Next Campaign Assignment: #").append(nextOpt.scenarioNumber)
                   .append(" ").append(nextOpt.scenarioTitle);
            } else if (step.outcomeBriefingText.isEmpty()) {
                if (step.endType == PgCampaignManager.CampaignEndType.DEFEAT) {
                    msg.append("High Command has relieved you of command.");
                } else {
                    msg.append("Congratulations, Herr General! You have completed the campaign.");
                }
            }
        } else {
            msg.append(outcome <= 2
                    ? "Congratulations, Herr General! Scenario objectives achieved."
                    : "Scenario objectives were not met.");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(title);
        b.setMessage(msg.toString());
        b.setCancelable(false);
        if (engine.isCampaignMode && step.hasNextScenario()) {
            if (step.requiresPlayerChoice()) {
                b.setPositiveButton("High Command Orders...", (d, w) -> {
                    sounds.playSelect();
                    showCampaignChoiceDialog(step);
                });
            } else {
                PgCampaignManager.BranchOption nextOpt = step.options.get(0);
                b.setPositiveButton("Advance to " + nextOpt.scenarioTitle, (d, w) -> {
                    sounds.playSelect();
                    advanceCampaignWithOption(step, nextOpt);
                });
            }
            b.setNegativeButton("Main Menu", (d, w) -> {
                showTitleScreen();
            });
        } else {
            b.setPositiveButton("Main Menu", (d, w) -> {
                engine.scenario = null;
                engine.clearGameCache(this);
                showTitleScreen();
            });
        }
        b.show();
    }

    private void showCampaignChoiceDialog(PgCampaignManager.CampaignStepResult step) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(36, 24, 36, 16);

        TextView tvBrief = new TextView(this);
        StringBuilder info = new StringBuilder();
        if (!step.choiceBriefingText.isEmpty()) {
            info.append(step.choiceBriefingText).append("\n\n");
        } else {
            info.append("High Command offers you a strategic choice for your next operation.\n\n");
        }
        info.append("Available Prestige Treasury: ").append(engine.getPlayerPrestige() + step.bonusPrestige);
        tvBrief.setText(info.toString());
        tvBrief.setTextSize(15f);
        tvBrief.setPadding(0, 0, 0, 20);
        container.addView(tvBrief);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("High Command Strategic Directive");
        b.setView(scroll);
        b.setCancelable(false);
        b.setNegativeButton("Main Menu", (d, w) -> {
            showTitleScreen();
        });
        scroll.addView(container);
        AlertDialog dlg = b.create();

        for (PgCampaignManager.BranchOption opt : step.options) {
            Button btn = new Button(this);
            btn.setAllCaps(false);
            btn.setText("#" + opt.scenarioNumber + "  " + opt.optionLabel);
            btn.setTextSize(14.5f);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setTextColor(Color.parseColor("#FFF4D2"));
            btn.setPadding(24, 22, 24, 22);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#2E3B27"));
            bg.setCornerRadius(10f);
            bg.setStroke(2, Color.parseColor("#C5A059"));
            btn.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 14);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> {
                sounds.playSelect();
                dlg.dismiss();
                advanceCampaignWithOption(step, opt);
            });
            container.addView(btn);
        }

        dlg.show();
    }

    private void advanceCampaignWithOption(PgCampaignManager.CampaignStepResult step,
                                           PgCampaignManager.BranchOption chosen) {
        try {
            engine.commitCampaignTransition(step, chosen);
            try {
                engine.saveGameCache(this);
            } catch (Exception ignored) {}
            launchTacticalScreen();
        } catch (IOException e) {
            Toast.makeText(this, "Error loading next scenario: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showBriefingDialog() {
        if (engine.scenario == null) return;
        PgDatabase.ScenarioMeta m = engine.scenario.meta;
        sounds.playVoiceBriefing(m.number);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("High Command Briefing — " + m.fullTitle + " (" + m.year + ")");
        StringBuilder sb = new StringBuilder();
        sb.append(m.description).append("\n\n");
        if (!m.briefingText.equals(m.description)) {
            sb.append(m.briefingText).append("\n\n");
        }
        sb.append("• Turns Allowed: ").append(engine.maxTurns);
        if (m.decisiveTurnsRemaining > 0) {
            sb.append(" (Major Victory within ").append(engine.maxTurns - m.decisiveTurnsRemaining).append(" turns)\n");
        } else if (engine.scenario.isDefensiveScenario) {
            sb.append(" (Defensive Operation — Hold Required Objectives)\n");
        } else {
            sb.append("\n");
        }
        sb.append("• Victory Objectives (").append(engine.scenario.objectives.size()).append("): ");
        for (int i = 0; i < engine.scenario.objectives.size(); i++) {
            int[] oc = engine.scenario.objectives.get(i);
            if (i > 0) sb.append(", ");
            sb.append(engine.scenario.grid[oc[0]][oc[1]].name)
              .append(" (").append(oc[0]).append(",").append(oc[1]).append(")");
        }
        b.setMessage(sb.toString());
        b.setPositiveButton("Begin Operation", (d, w) -> {
            sounds.stopVoice();
            sounds.playSelect();
            if (engine.scenario != null) {
                if (engine.isDeploymentPhase) {
                    mapView.showYourTurnBanner(engine.turn, engine.maxTurns, "DEPLOYMENT PHASE");
                    showCoreDeploymentDialog();
                } else {
                    mapView.showYourTurnBanner(engine.turn, engine.maxTurns, engine.getWeatherLabel());
                }
            }
        });
        b.setOnDismissListener(d -> sounds.stopVoice());
        b.show();
    }

    private int selectedPurchaseCategory = PgDatabase.CLASS_TANK;
    private boolean purchaseShowAllYears = false;

    private static final int[] PURCHASE_CATEGORY_CLASSES = {
        PgDatabase.CLASS_INFANTRY,
        PgDatabase.CLASS_TANK,
        PgDatabase.CLASS_RECON,
        PgDatabase.CLASS_ANTI_TANK,
        PgDatabase.CLASS_ARTILLERY,
        PgDatabase.CLASS_ANTI_AIR,
        PgDatabase.CLASS_AIR_DEFENSE,
        PgDatabase.CLASS_FIGHTER,
        PgDatabase.CLASS_TAC_BOMBER,
        PgDatabase.CLASS_LEVEL_BOMBER
    };

    private static final String[] PURCHASE_CATEGORY_LABELS = {
        "Infantry",
        "Tanks",
        "Recon",
        "Anti-Tank",
        "Artillery",
        "Anti-Air",
        "Air Defense",
        "Fighters",
        "Tac Bombers",
        "Level Bombers"
    };

    private List<PgDatabase.UnitDef> getAvailableUnitsForPlayer(boolean allYears) {
        if (engine.playerIsAxis) {
            return getAvailableAxisUnitsForPurchase(allYears);
        }
        return getAvailableAlliedUnitsForPurchase(allYears);
    }

    private List<PgDatabase.UnitDef> getAvailableAlliedUnitsForPurchase(boolean allYears) {
        int curYear = (engine.scenario != null) ? (engine.scenario.meta.year % 100) : 39;
        List<PgDatabase.UnitDef> available = new ArrayList<>();
        for (PgDatabase.UnitDef u : db.equipment) {
            if (u.id == 0 || u.name == null || u.name.isEmpty() || u.name.equalsIgnoreCase("RESERVED")) continue;
            if (u.unitClass == PgDatabase.CLASS_LAND_TRANSPORT
                    || u.unitClass == PgDatabase.CLASS_AIR_TRANSPORT
                    || u.unitClass == PgDatabase.CLASS_SEA_TRANSPORT
                    || u.unitClass == PgDatabase.CLASS_FORT
                    || u.isFortification()) continue;
            if (db.isUnitBelongsToSide(u, false, engine.scenario)) {
                if (allYears || (u.startYear <= curYear && u.endYear >= curYear)) {
                    available.add(u);
                }
            }
        }
        if (available.isEmpty()) {
            for (PgDatabase.UnitDef u : db.equipment) {
                if (u.id == 0 || u.name == null || u.name.isEmpty() || u.name.equalsIgnoreCase("RESERVED")) continue;
                if (u.unitClass == PgDatabase.CLASS_LAND_TRANSPORT
                        || u.unitClass == PgDatabase.CLASS_AIR_TRANSPORT
                        || u.unitClass == PgDatabase.CLASS_SEA_TRANSPORT
                        || u.unitClass == PgDatabase.CLASS_FORT
                        || u.isFortification()) continue;
                if (db.isUnitBelongsToSide(u, false, engine.scenario)) {
                    available.add(u);
                }
            }
        }
        return available;
    }

    private int[] getAvailableLandTransportIdsForPlayer() {
        if (engine.playerIsAxis) {
            return new int[]{0, 86, 87, 88};
        }
        int nation = engine.getPrimaryPlayerNation();
        switch (nation) {
            case 20: return new int[]{0, 272, 125};
            case 10: return new int[]{0, 370, 355};
            case 23: return new int[]{0, 212, 211, 210};
            case 7: return new int[]{0, 140, 407, 406};
            case 16: return new int[]{0, 414, 413, 125};
            default: return new int[]{0, 125, 212};
        }
    }

    private List<PgDatabase.UnitDef> getAvailableAxisUnitsForPurchase(boolean allYears) {
        int curYear = (engine.scenario != null) ? (engine.scenario.meta.year % 100) : 39;
        List<PgDatabase.UnitDef> available = new ArrayList<>();
        for (PgDatabase.UnitDef u : db.equipment) {
            if (u.id == 0 || u.name.isEmpty() || u.name.equalsIgnoreCase("RESERVED")) continue;
            if (u.unitClass == PgDatabase.CLASS_LAND_TRANSPORT
                    || u.unitClass == PgDatabase.CLASS_AIR_TRANSPORT
                    || u.unitClass == PgDatabase.CLASS_SEA_TRANSPORT
                    || u.unitClass == PgDatabase.CLASS_FORT
                    || u.isFortification()) continue;
            // Authentic German Wehrmacht / Luftwaffe equipment entries in PANZEQUP.EQP
            if (u.id <= 112 || u.id == 160 || u.id == 330 || u.id == 422) {
                if (allYears || (u.startYear <= curYear && u.endYear >= curYear)) {
                    available.add(u);
                }
            }
        }
        if (available.isEmpty()) {
            for (int i = 1; i < Math.min(115, db.equipment.size()); i++) {
                PgDatabase.UnitDef u = db.equipment.get(i);
                if (!u.name.isEmpty() && !u.name.equalsIgnoreCase("RESERVED")) {
                    if (u.unitClass != PgDatabase.CLASS_LAND_TRANSPORT
                            && u.unitClass != PgDatabase.CLASS_AIR_TRANSPORT
                            && u.unitClass != PgDatabase.CLASS_SEA_TRANSPORT
                            && u.unitClass != PgDatabase.CLASS_FORT
                            && !u.isFortification()) {
                        available.add(u);
                    }
                }
            }
        }
        return available;
    }

    private int selectedPurchaseUnitId = -1;
    private int selectedTransportId = 0;

    private static final int[] CATEGORY_REP_UNIT_IDS = {
        105, // Infantry (Wehrmacht Inf)
        43,  // Tanks (PzIIIE)
        90,  // Recon (PSW 231)
        61,  // Anti-Tank (StuG IIIG)
        99,  // Artillery (10.5 leFH 18)
        85,  // Anti-Air (SdKfz 10/4)
        104, // Air Defense (8.8 FlaK 18)
        1,   // Fighters (BF109e)
        20,  // Tac Bombers (JU87B)
        25   // Level Bombers (He111 H2)
    };

    private View createStatBadge(String label, String value, int barValue, int maxBar, int barColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(10, 6, 10, 6);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A221B);
        bg.setCornerRadius(6f);
        bg.setStroke(1, 0xFF3E4E40);
        box.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView tvLbl = new TextView(this);
        tvLbl.setText(label);
        tvLbl.setTextColor(0xFF9EABA0);
        tvLbl.setTextSize(10.5f);
        top.addView(tvLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvVal = new TextView(this);
        tvVal.setText(value);
        tvVal.setTextColor(0xFFFFF59D);
        tvVal.setTextSize(12f);
        tvVal.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(tvVal);
        box.addView(top);

        // Mini tactical stat bar
        FrameLayout track = new FrameLayout(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0xFF0E130F);
        trackBg.setCornerRadius(3f);
        track.setBackground(trackBg);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 7);
        trackLp.setMargins(0, 4, 0, 0);
        box.addView(track, trackLp);

        int pct = Math.max(4, Math.min(100, (barValue * 100) / Math.max(1, maxBar)));
        View fill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(barColor);
        fillBg.setCornerRadius(3f);
        fill.setBackground(fillBg);
        LinearLayout fillWrap = new LinearLayout(this);
        fillWrap.setOrientation(LinearLayout.HORIZONTAL);
        fillWrap.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
        View spacer = new View(this);
        fillWrap.addView(spacer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
        track.addView(fillWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return box;
    }

    private void showRequisitionDialog() {
        if (engine.scenario == null) return;
        if (mapView.pendingOverrunUnit != null) {
            mapView.cancelOverrunMoveSelection();
            refreshHud();
        }

        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141915);
        root.setPadding(20, 14, 20, 20);

        // 1. Top Command Header Bar
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(16, 10, 16, 10);
        GradientDrawable hdrBg = new GradientDrawable();
        hdrBg.setColor(0xFF1F2721);
        hdrBg.setCornerRadius(10f);
        hdrBg.setStroke(2, 0xFFC5A059);
        headerRow.setBackground(hdrBg);

        ImageView ivFlag = new ImageView(this);
        Bitmap flagBmp = assets.getTrimmedSprite("FLAGS.SHP", engine.getPlayerFlagSpriteIndex());
        if (flagBmp != null) {
            ivFlag.setImageBitmap(Bitmap.createScaledBitmap(flagBmp, 48, 36, false));
        }
        LinearLayout.LayoutParams flagLp = new LinearLayout.LayoutParams(52, 40);
        flagLp.setMargins(0, 0, 14, 0);
        headerRow.addView(ivFlag, flagLp);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText(engine.playerIsAxis
                ? "OBERKOMMANDO DER WEHRMACHT — REQUISITION & DEPLOYMENT"
                : "SUPREME ALLIED COMMAND — REQUISITION & DEPLOYMENT");
        tvHeaderTitle.setTextColor(0xFFFFD54F);
        tvHeaderTitle.setTextSize(15f);
        tvHeaderTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titleStack.addView(tvHeaderTitle);

        TextView tvSubHeader = new TextView(this);
        tvSubHeader.setText(String.format(Locale.ROOT,
                "Operation: %s (%d)   •   Select a category tab, inspect unit specifications, and deploy",
                engine.scenario.meta.fullTitle, engine.scenario.meta.year));
        tvSubHeader.setTextColor(0xFFB0BEC5);
        tvSubHeader.setTextSize(11.5f);
        titleStack.addView(tvSubHeader);
        headerRow.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Prestige Pill
        TextView tvPrestigePill = new TextView(this);
        tvPrestigePill.setText(String.format(Locale.ROOT, "★ %d PRESTIGE", engine.getPlayerPrestige()));
        tvPrestigePill.setTextColor(0xFF1B1F1B);
        tvPrestigePill.setTextSize(13.5f);
        tvPrestigePill.setTypeface(Typeface.DEFAULT_BOLD);
        tvPrestigePill.setPadding(20, 8, 20, 8);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xFFFFD54F);
        pillBg.setCornerRadius(8f);
        tvPrestigePill.setBackground(pillBg);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.setMargins(12, 0, 16, 0);
        headerRow.addView(tvPrestigePill, pillLp);

        CheckBox cbAllYears = new CheckBox(this);
        cbAllYears.setText("All Years (1939–45)");
        cbAllYears.setTextColor(0xFFE0E0E0);
        cbAllYears.setTextSize(12f);
        cbAllYears.setChecked(purchaseShowAllYears);
        headerRow.addView(cbAllYears);

        Button btnClose = createCompactButton("✕ CLOSE", 0xFF7F2424);
        btnClose.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 60);
        closeLp.setMargins(14, 0, 0, 0);
        btnClose.setLayoutParams(closeLp);
        headerRow.addView(btnClose);

        root.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Illustrated Category Tab Strip
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setPadding(0, 10, 0, 10);
        tabScroll.addView(tabStrip);
        root.addView(tabScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 3. Main Master-Detail Split Area
        LinearLayout splitBody = new LinearLayout(this);
        splitBody.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(splitBody, bodyLp);

        // Left Pane: Illustrated Unit Roster (52% width)
        ScrollView rosterScroll = new ScrollView(this);
        LinearLayout rosterContainer = new LinearLayout(this);
        rosterContainer.setOrientation(LinearLayout.VERTICAL);
        rosterContainer.setPadding(0, 0, 12, 0);
        rosterScroll.addView(rosterContainer);
        splitBody.addView(rosterScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.08f));

        // Right Pane: Detailed Unit Dossier & Transport Picker (48% width)
        ScrollView detailScroll = new ScrollView(this);
        LinearLayout detailCard = new LinearLayout(this);
        detailCard.setOrientation(LinearLayout.VERTICAL);
        detailCard.setPadding(20, 16, 20, 16);
        GradientDrawable detailBg = new GradientDrawable();
        detailBg.setColor(0xFF212922);
        detailBg.setCornerRadius(12f);
        detailBg.setStroke(2, 0xFFC5A059);
        detailCard.setBackground(detailBg);
        detailScroll.addView(detailCard);
        splitBody.addView(detailScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.92f));

        final Runnable[] renderUI = new Runnable[1];
        renderUI[0] = () -> {
            List<PgDatabase.UnitDef> allAvail = getAvailableUnitsForPlayer(purchaseShowAllYears);
            tabStrip.removeAllViews();
            rosterContainer.removeAllViews();
            detailCard.removeAllViews();

            // Build Illustrated Category Tabs
            for (int i = 0; i < PURCHASE_CATEGORY_CLASSES.length; i++) {
                final int catClass = PURCHASE_CATEGORY_CLASSES[i];
                String catName = PURCHASE_CATEGORY_LABELS[i];
                int repUnitId = CATEGORY_REP_UNIT_IDS[i];
                int count = 0;
                for (PgDatabase.UnitDef u : allAvail) {
                    if (u.unitClass == catClass) count++;
                }

                boolean isSelected = (catClass == selectedPurchaseCategory);
                LinearLayout tabBox = new LinearLayout(this);
                tabBox.setOrientation(LinearLayout.HORIZONTAL);
                tabBox.setGravity(Gravity.CENTER_VERTICAL);
                tabBox.setPadding(14, 8, 16, 8);

                GradientDrawable tabBg = new GradientDrawable();
                tabBg.setColor(isSelected ? 0xFF3A4A36 : (count > 0 ? 0xFF232C24 : 0xFF1A1E1A));
                tabBg.setCornerRadius(10f);
                tabBg.setStroke(isSelected ? 3 : 1, isSelected ? 0xFFFFD54F : 0xFF566557);
                tabBox.setBackground(tabBg);

                ImageView tabIcon = new ImageView(this);
                if (repUnitId < db.equipment.size()) {
                    Bitmap catBmp = assets.getTrimmedSprite("TACICONS.SHP", db.equipment.get(repUnitId).iconId);
                    if (catBmp != null) {
                        tabIcon.setImageBitmap(catBmp);
                    }
                }
                LinearLayout.LayoutParams tiLp = new LinearLayout.LayoutParams(54, 38);
                tiLp.setMargins(0, 0, 10, 0);
                tabBox.addView(tabIcon, tiLp);

                TextView tvTab = new TextView(this);
                tvTab.setText(String.format(Locale.ROOT, "%s (%d)", catName, count));
                tvTab.setTextColor(isSelected ? 0xFFFFD54F : (count > 0 ? Color.WHITE : 0xFF78909C));
                tvTab.setTextSize(12.5f);
                tvTab.setTypeface(Typeface.DEFAULT_BOLD);
                tabBox.addView(tvTab);

                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tLp.setMargins(0, 0, 10, 0);
                tabBox.setLayoutParams(tLp);

                tabBox.setOnClickListener(v -> {
                    sounds.playClick();
                    selectedPurchaseCategory = catClass;
                    selectedPurchaseUnitId = -1;
                    selectedTransportId = 0;
                    renderUI[0].run();
                });
                tabStrip.addView(tabBox);
            }

            // Filter units for current category
            List<PgDatabase.UnitDef> catUnits = new ArrayList<>();
            for (PgDatabase.UnitDef u : allAvail) {
                if (u.unitClass == selectedPurchaseCategory) {
                    catUnits.add(u);
                }
            }

            if (catUnits.isEmpty()) {
                TextView emptyTv = new TextView(this);
                emptyTv.setText("No units in this category for " + engine.scenario.meta.year
                        + ".\nCheck 'All Years (1939–45)' above to unlock late-war equipment.");
                emptyTv.setTextColor(0xFFB0BEC5);
                emptyTv.setTextSize(14f);
                emptyTv.setPadding(24, 60, 24, 60);
                emptyTv.setGravity(Gravity.CENTER);
                rosterContainer.addView(emptyTv);
                return;
            }

            // Ensure a valid unit is selected in this category
            PgDatabase.UnitDef activeUnit = null;
            for (PgDatabase.UnitDef u : catUnits) {
                if (u.id == selectedPurchaseUnitId) {
                    activeUnit = u;
                    break;
                }
            }
            if (activeUnit == null) {
                activeUnit = catUnits.get(0);
                selectedPurchaseUnitId = activeUnit.id;
            }

            // Populate Left Pane: Illustrated Unit Cards
            for (PgDatabase.UnitDef u : catUnits) {
                boolean isChosen = (u.id == activeUnit.id);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(12, 10, 14, 10);

                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(isChosen ? 0xFF2F3E30 : 0xFF212922);
                cardBg.setCornerRadius(10f);
                cardBg.setStroke(isChosen ? 3 : 1, isChosen ? 0xFFFFD54F : 0xFF4A5D4C);
                card.setBackground(cardBg);

                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.setMargins(0, 0, 0, 10);
                card.setLayoutParams(cardLp);

                // Framed Unit Diorama Portrait
                ImageView ivPortrait = new ImageView(this);
                ivPortrait.setImageBitmap(assets.createUnitPortrait(u.iconId, u.unitClass, 156, 100));
                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(156, 100);
                pLp.setMargins(0, 0, 14, 0);
                card.addView(ivPortrait, pLp);

                // Middle Info Column
                LinearLayout infoCol = new LinearLayout(this);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                card.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView tvName = new TextView(this);
                tvName.setText(u.name);
                tvName.setTextColor(isChosen ? 0xFFFFD54F : Color.WHITE);
                tvName.setTextSize(15.5f);
                tvName.setTypeface(Typeface.DEFAULT_BOLD);
                infoCol.addView(tvName);

                String targetStr = (u.targetType == PgDatabase.TARGET_HARD) ? "Armored / Hard"
                        : (u.targetType == PgDatabase.TARGET_AIR ? "Aircraft" : "Soft Target");
                TextView tvSub = new TextView(this);
                tvSub.setText(String.format(Locale.ROOT, "Service: 19%02d–19%02d  •  %s",
                        u.startYear, u.endYear, targetStr));
                tvSub.setTextColor(0xFFA5B5A7);
                tvSub.setTextSize(11.5f);
                infoCol.addView(tvSub);

                TextView tvQuickStats = new TextView(this);
                tvQuickStats.setText(String.format(Locale.ROOT,
                        "SA: %d   HA: %d   AA: %d   |   GD: %d   AD: %d   |   Mov: %d   Rng: %d",
                        u.softAttack, u.hardAttack, u.airAttack, u.groundDefense, u.airDefense, u.movement, u.range));
                tvQuickStats.setTextColor(0xFFE0E8E1);
                tvQuickStats.setTextSize(12f);
                tvQuickStats.setPadding(0, 4, 0, 0);
                infoCol.addView(tvQuickStats);

                // Right Cost Badge
                LinearLayout priceCol = new LinearLayout(this);
                priceCol.setOrientation(LinearLayout.VERTICAL);
                priceCol.setGravity(Gravity.CENTER);
                priceCol.setPadding(14, 8, 14, 8);
                GradientDrawable priceBg = new GradientDrawable();
                boolean affordable = (engine.getPlayerPrestige() >= u.cost);
                priceBg.setColor(affordable ? 0xFF28472C : 0xFF3E2723);
                priceBg.setCornerRadius(8f);
                priceBg.setStroke(1, affordable ? 0xFF81C784 : 0xFFE57373);
                priceCol.setBackground(priceBg);

                TextView tvCost = new TextView(this);
                tvCost.setText("★ " + u.cost);
                tvCost.setTextColor(affordable ? 0xFFFFE082 : 0xFFFF8A80);
                tvCost.setTextSize(14f);
                tvCost.setTypeface(Typeface.DEFAULT_BOLD);
                priceCol.addView(tvCost);

                TextView tvCostSub = new TextView(this);
                tvCostSub.setText(isChosen ? "SELECTED" : "INSPECT");
                tvCostSub.setTextColor(0xFFCFD8DC);
                tvCostSub.setTextSize(9.5f);
                priceCol.addView(tvCostSub);

                card.addView(priceCol);

                card.setOnClickListener(v -> {
                    sounds.playClick();
                    selectedPurchaseUnitId = u.id;
                    renderUI[0].run();
                });

                rosterContainer.addView(card);
            }

            // Populate Right Pane: Detailed Unit Showcase & Transport Selector
            final PgDatabase.UnitDef sel = activeUnit;
            boolean supportsTransport = (sel.unitClass == PgDatabase.CLASS_INFANTRY
                    || sel.unitClass == PgDatabase.CLASS_ARTILLERY
                    || sel.unitClass == PgDatabase.CLASS_ANTI_TANK
                    || sel.unitClass == PgDatabase.CLASS_ANTI_AIR
                    || sel.unitClass == PgDatabase.CLASS_AIR_DEFENSE);
            if (!supportsTransport) {
                selectedTransportId = 0;
            }

            // Hero Header with Large Diorama Picture
            LinearLayout heroRow = new LinearLayout(this);
            heroRow.setOrientation(LinearLayout.HORIZONTAL);
            heroRow.setGravity(Gravity.CENTER_VERTICAL);
            heroRow.setPadding(0, 0, 0, 12);

            ImageView ivHero = new ImageView(this);
            ivHero.setImageBitmap(assets.createUnitPortrait(sel.iconId, sel.unitClass, 240, 148));
            LinearLayout.LayoutParams heroImgLp = new LinearLayout.LayoutParams(240, 148);
            heroImgLp.setMargins(0, 0, 16, 0);
            heroRow.addView(ivHero, heroImgLp);

            LinearLayout heroInfo = new LinearLayout(this);
            heroInfo.setOrientation(LinearLayout.VERTICAL);

            String clsName = (sel.unitClass >= 0 && sel.unitClass < PgDatabase.CLASS_NAMES.length)
                    ? PgDatabase.CLASS_NAMES[sel.unitClass] : "Unit";
            TextView tvHeroClass = new TextView(this);
            tvHeroClass.setText(clsName.toUpperCase(Locale.ROOT) + "  •  EQUIPMENT #" + sel.id);
            tvHeroClass.setTextColor(0xFF81C784);
            tvHeroClass.setTextSize(11f);
            tvHeroClass.setTypeface(Typeface.DEFAULT_BOLD);
            heroInfo.addView(tvHeroClass);

            TextView tvHeroName = new TextView(this);
            tvHeroName.setText(sel.name);
            tvHeroName.setTextColor(0xFFFFD54F);
            tvHeroName.setTextSize(20f);
            tvHeroName.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            heroInfo.addView(tvHeroName);

            TextView tvHeroMeta = new TextView(this);
            tvHeroMeta.setText(String.format(Locale.ROOT,
                    "Service Years: 19%02d – 19%02d\nBase Cost: ★ %d Prestige\nStrength: 10/10 (Exp: 100 / 1★)",
                    sel.startYear, sel.endYear, sel.cost));
            tvHeroMeta.setTextColor(0xFFD0D8D0);
            tvHeroMeta.setTextSize(12f);
            heroInfo.addView(tvHeroMeta);

            heroRow.addView(heroInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            detailCard.addView(heroRow);

            // 3x3 Tactical Stat Bars Grid
            TextView tvSpecsHdr = new TextView(this);
            tvSpecsHdr.setText("COMBAT & OPERATIONAL SPECIFICATIONS");
            tvSpecsHdr.setTextColor(0xFFC5A059);
            tvSpecsHdr.setTextSize(11.5f);
            tvSpecsHdr.setTypeface(Typeface.DEFAULT_BOLD);
            tvSpecsHdr.setPadding(0, 4, 0, 6);
            detailCard.addView(tvSpecsHdr);

            Object[][] statRows = {
                {
                    createStatBadge("Soft Attack", String.valueOf(sel.softAttack), sel.softAttack, 24, 0xFFE57373),
                    createStatBadge("Hard Attack", String.valueOf(sel.hardAttack), sel.hardAttack, 24, 0xFFFF8A65),
                    createStatBadge("Air Attack", String.valueOf(sel.airAttack), sel.airAttack, 20, 0xFF64B5F6)
                },
                {
                    createStatBadge("Ground Def", String.valueOf(sel.groundDefense), sel.groundDefense, 20, 0xFF81C784),
                    createStatBadge("Air Defense", String.valueOf(sel.airDefense), sel.airDefense, 20, 0xFF4DB6AC),
                    createStatBadge("Close Def", String.valueOf(sel.closeDefense), sel.closeDefense, 12, 0xFFAED581)
                },
                {
                    createStatBadge("Initiative", String.valueOf(sel.initiative), sel.initiative, 15, 0xFFFFD54F),
                    createStatBadge("Movement", sel.movement + " hex", sel.movement, 14, 0xFF90CAF9),
                    createStatBadge("Range / Spot", sel.range + " / " + sel.spotting, sel.range * 3 + sel.spotting, 16, 0xFFCE93D8)
                },
                {
                    createStatBadge("Max Fuel", engine.supplyEnabled ? String.valueOf(sel.maxFuel) : "∞ (OFF)", sel.maxFuel, 90, 0xFFFFB74D),
                    createStatBadge("Max Ammo", engine.supplyEnabled ? String.valueOf(sel.maxAmmo) : "∞ (OFF)", sel.maxAmmo, 16, 0xFFA1887F),
                    createStatBadge("Naval Atk", String.valueOf(sel.navalAttack), sel.navalAttack, 16, 0xFF4FC3F7)
                }
            };

            for (Object[] rowWidgets : statRows) {
                LinearLayout sRow = new LinearLayout(this);
                sRow.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < rowWidgets.length; c++) {
                    View w = (View) rowWidgets[c];
                    LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    wLp.setMargins(c == 0 ? 0 : 6, 0, c == rowWidgets.length - 1 ? 0 : 6, 8);
                    sRow.addView(w, wLp);
                }
                detailCard.addView(sRow);
            }

            // Organic Transport Picker with Pictures (if applicable)
            int transportAddonCost = 0;
            if (supportsTransport) {
                TextView tvTrpHdr = new TextView(this);
                tvTrpHdr.setText("ORGANIC LAND TRANSPORT (OPTIONAL)");
                tvTrpHdr.setTextColor(0xFFC5A059);
                tvTrpHdr.setTextSize(11.5f);
                tvTrpHdr.setTypeface(Typeface.DEFAULT_BOLD);
                tvTrpHdr.setPadding(0, 6, 0, 6);
                detailCard.addView(tvTrpHdr);

                LinearLayout trpStrip = new LinearLayout(this);
                trpStrip.setOrientation(LinearLayout.HORIZONTAL);
                int[] trpIds = getAvailableLandTransportIdsForPlayer();
                for (int i = 0; i < trpIds.length; i++) {
                    final int tid = trpIds[i];
                    boolean isTrpSel = (selectedTransportId == tid);
                    PgDatabase.UnitDef trpDef = (tid > 0 && tid < db.equipment.size()) ? db.equipment.get(tid) : null;
                    int addCost = (trpDef != null) ? (trpDef.cost / 2) : 0;
                    if (isTrpSel) transportAddonCost = addCost;

                    LinearLayout tCard = new LinearLayout(this);
                    tCard.setOrientation(LinearLayout.VERTICAL);
                    tCard.setGravity(Gravity.CENTER_HORIZONTAL);
                    tCard.setPadding(8, 8, 8, 8);
                    GradientDrawable tBg = new GradientDrawable();
                    tBg.setColor(isTrpSel ? 0xFF334434 : 0xFF19201A);
                    tBg.setCornerRadius(8f);
                    tBg.setStroke(isTrpSel ? 2 : 1, isTrpSel ? 0xFFFFD54F : 0xFF455A48);
                    tCard.setBackground(tBg);

                    ImageView ivTrp = new ImageView(this);
                    if (trpDef != null) {
                        ivTrp.setImageBitmap(assets.createUnitPortrait(trpDef.iconId, trpDef.unitClass, 110, 66));
                    } else {
                        ivTrp.setImageBitmap(assets.createUnitPortrait(sel.iconId, sel.unitClass, 110, 66));
                    }
                    tCard.addView(ivTrp, new LinearLayout.LayoutParams(110, 66));

                    TextView tvTrpName = new TextView(this);
                    tvTrpName.setText(trpDef != null ? trpDef.name : "Foot / Towed");
                    tvTrpName.setTextColor(isTrpSel ? 0xFFFFD54F : Color.WHITE);
                    tvTrpName.setTextSize(11f);
                    tvTrpName.setTypeface(Typeface.DEFAULT_BOLD);
                    tvTrpName.setSingleLine(true);
                    tvTrpName.setPadding(0, 4, 0, 0);
                    tCard.addView(tvTrpName);

                    TextView tvTrpMeta = new TextView(this);
                    tvTrpMeta.setText(trpDef != null
                            ? String.format(Locale.ROOT, "+%d★ • Mov:%d GD:%d", addCost, trpDef.movement, trpDef.groundDefense)
                            : String.format(Locale.ROOT, "+0★ • Mov:%d", sel.movement));
                    tvTrpMeta.setTextColor(0xFFB0BEC5);
                    tvTrpMeta.setTextSize(10f);
                    tCard.addView(tvTrpMeta);

                    tCard.setOnClickListener(v -> {
                        sounds.playClick();
                        selectedTransportId = tid;
                        renderUI[0].run();
                    });

                    LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    tcLp.setMargins(i == 0 ? 0 : 4, 0, i == trpIds.length - 1 ? 0 : 4, 8);
                    trpStrip.addView(tCard, tcLp);
                }
                detailCard.addView(trpStrip);
            }

            final int finalTotalCost = sel.cost + transportAddonCost;
            boolean canBuyTotal = (engine.getPlayerPrestige() >= finalTotalCost);
            String trpSuffix = (selectedTransportId > 0 && selectedTransportId < db.equipment.size())
                    ? (" + " + db.equipment.get(selectedTransportId).name) : "";

            Button btnConfirmBuy = createStyledButton(
                    canBuyTotal
                            ? String.format(Locale.ROOT, "REQUISITION & DEPLOY %s%s  —  ★ %d PRESTIGE",
                                    sel.name.toUpperCase(Locale.ROOT), trpSuffix, finalTotalCost)
                            : String.format(Locale.ROOT, "INSUFFICIENT PRESTIGE (NEED ★ %d, HAVE ★ %d)",
                                    finalTotalCost, engine.getPlayerPrestige()),
                    canBuyTotal ? 0xFF2E6B36 : 0xFF455A64,
                    canBuyTotal ? 0xFFFFF59D : 0xFFCFD8DC);
            LinearLayout.LayoutParams buyBtnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            buyBtnLp.setMargins(0, 8, 0, 4);
            btnConfirmBuy.setLayoutParams(buyBtnLp);
            btnConfirmBuy.setOnClickListener(v -> {
                if (engine.getPlayerPrestige() < finalTotalCost) {
                    Toast.makeText(this, "Insufficient Prestige (" + finalTotalCost + "★ required).", Toast.LENGTH_SHORT).show();
                    return;
                }
                sounds.playSelect();
                dialog.dismiss();
                startUnitPlacement(sel.id, selectedTransportId);
            });
            detailCard.addView(btnConfirmBuy);
        };

        cbAllYears.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sounds.playClick();
            purchaseShowAllYears = isChecked;
            renderUI[0].run();
        });

        renderUI[0].run();
        dialog.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.show();
    }

    private void startUnitPlacement(int unitDefId, int transportDefId) {
        List<int[]> spots = engine.getAvailableDeployHexes(unitDefId);
        PgDatabase.UnitDef def = (unitDefId > 0 && unitDefId < db.equipment.size()) ? db.equipment.get(unitDefId) : null;
        boolean isAir = def != null && def.isAir();

        if (spots.isEmpty()) {
            if (isAir) {
                Toast.makeText(this, "No secure airports available! Planes must be placed by friendly airports free from enemy contact.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "No secure cities or supply areas available! Units must be placed by friendly cities or supply areas free from enemy contact.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        mapView.pendingPurchaseUnitId = unitDefId;
        mapView.pendingPurchaseTransportId = transportDefId;
        mapView.purchasePlacementMode = true;

        // If user already has a hex selected near a valid deploy spot, center on the closest deploy spot to their selection
        int[] targetSpot = spots.get(0);
        if (mapView.selectedCol >= 0 && mapView.selectedRow >= 0) {
            int bestDist = Integer.MAX_VALUE;
            for (int[] s : spots) {
                int d = PgGameEngine.hexDistance(mapView.selectedCol, mapView.selectedRow, s[0], s[1]);
                if (d < bestDist) {
                    bestDist = d;
                    targetSpot = s;
                }
            }
        }
        mapView.centerOnHex(targetSpot[0], targetSpot[1]);
        mapView.invalidate();
        if (isAir) {
            Toast.makeText(this, "Tap a controlled airport or adjacent hexagon (free from enemy contact) to deploy.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Tap a controlled city or adjacent hexagon (free from enemy contact) to deploy.", Toast.LENGTH_LONG).show();
        }
    }

    private void showActiveUnitActionsModal(PgDatabase.ActiveUnit u) {
        if (u == null || engine.scenario == null) return;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        PgDatabase.HexCell cell = (u.col >= 0 && u.col < engine.scenario.cols && u.row >= 0 && u.row < engine.scenario.rows)
                ? engine.scenario.grid[u.col][u.row] : null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(32, 20, 32, 20);
        box.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iv = new ImageView(this);
        iv.setImageBitmap(assets.createUnitPortrait(engine.getEffectiveIconId(u), def.unitClass, 210, 134));
        box.addView(iv, new LinearLayout.LayoutParams(210, 134));

        TextView tv = new TextView(this);
        tv.setPadding(24, 0, 0, 0);
        tv.setTextSize(13.5f);
        PgDatabase.UnitDef effDef = engine.getEffectiveUnitDef(u);
        String cls = (effDef.unitClass >= 0 && effDef.unitClass < PgDatabase.CLASS_NAMES.length)
                ? PgDatabase.CLASS_NAMES[effDef.unitClass] : "Unit";
        String specialRules = "";
        if (u.mountedInTransport && u.transportDefId > 0) specialRules = "\n• Status: Mounted in Transport (" + effDef.name + "). Moving and attacking using transport stats.";
        else if (def.unitClass == PgDatabase.CLASS_TANK) specialRules = "\n• Special: Tank Overrun (destroying weak unit with 0 losses grants bonus move & attack)";
        else if (def.isBridgeEngineer()) specialRules = "\n• Special: Bridge Engineer (forms pontoon bridge on River hexes; ignores Rugged Defense)";
        else if (def.isPioneerOrEngineer()) specialRules = "\n• Special: Combat Pioneer (ignores enemy Rugged Defense; strips 2x entrenchment)";
        else if (def.unitClass == PgDatabase.CLASS_RECON) specialRules = "\n• Special: Recon Phased Movement & High Spotting";
        else if (def.unitClass == PgDatabase.CLASS_ARTILLERY) specialRules = "\n• Special: Defensive Artillery Support to adjacent friendly units & Ranged Suppression";
        else if (def.unitClass == PgDatabase.CLASS_FIGHTER) specialRules = "\n• Special: Air Interceptor (protects adjacent friendly units from enemy bombers)";
        else if (def.unitClass == PgDatabase.CLASS_LEVEL_BOMBER) specialRules = "\n• Special: Strategic Bombardment (destroys enemy fuel/ammo & strips entrenchment)";

        String locLine = (cell != null)
                ? String.format(Locale.ROOT, "Hex (%d,%d) %s [%s • Entrenchment: %d • Ini Cap: %s]",
                        u.col, u.row, cell.name, cell.getTerrainName(), u.entrenchment,
                        cell.getInitiativeCap() >= 99 ? "Uncapped" : String.valueOf(cell.getInitiativeCap()))
                : "Reserve Pool (Undeployed)";

        tv.setText(String.format(Locale.ROOT,
                "Unit: %s (%s)\n" +
                "Strength: %d / %d (Suppression: %d)  •  Experience: %d (%d★)\n" +
                "Location: %s\n" +
                "Movement: %d / %d  •  Ammo: %s  •  Fuel: %s\n" +
                "Combat: SA:%d  HA:%d  AA:%d  NA:%d  |  GD:%d  AD:%d  CD:%d  Ini:%d%s",
                u.customTitle != null ? u.customTitle : def.name, cls,
                u.strength, u.getMaxOverstrength(), u.suppression, u.experience, u.getStars(),
                locLine,
                engine.getRemainingMovementPoints(u), engine.getMaxMovementPoints(u),
                engine.supplyEnabled ? String.valueOf(u.ammo) : "∞",
                engine.supplyEnabled ? String.valueOf(u.fuel) : "∞",
                effDef.softAttack, effDef.hardAttack, effDef.airAttack, effDef.navalAttack,
                effDef.groundDefense, effDef.airDefense, effDef.closeDefense, effDef.initiative,
                specialRules));
        box.addView(tv);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        String dossierPrefix = engine.isPlayerUnit(u)
                ? (engine.playerIsAxis ? "Axis Unit Dossier — " : "Allied Unit Dossier — ")
                : (engine.playerIsAxis ? "Allied Intelligence Report — " : "Axis Intelligence Report — ");
        b.setTitle(dossierPrefix + def.name);
        b.setView(box);
        if (engine.canUpgradeUnit(u)) {
            b.setPositiveButton("Upgrade Equipment", (d, w) -> showUpgradeUnitDialog(u));
        }
        if (engine.canDisbandUnit(u)) {
            int refund = engine.getDisbandRefund(u);
            b.setNeutralButton("Disband (+" + refund + "★ Prestige)", (d, w) -> {
                int gained = engine.disbandUnit(u);
                sounds.playSelect();
                mapView.clearSelection();
                refreshHud();
                Toast.makeText(this, "Disbanded " + def.name + " (+" + gained + "★ Prestige refunded).", Toast.LENGTH_SHORT).show();
            });
        }
        b.setNegativeButton("Close", null);
        b.show();
    }

    private String formatStatDiff(int newVal, int oldVal) {
        int diff = newVal - oldVal;
        if (diff > 0) return newVal + " (+" + diff + ")";
        if (diff < 0) return newVal + " (" + diff + ")";
        return String.valueOf(newVal);
    }

    private boolean isValidUpgradeCategoryForUnit(PgDatabase.UnitDef currentDef, int targetClass) {
        if (currentDef == null) return false;
        if (targetClass == currentDef.unitClass) return true;
        // Towed Air Defense (CLASS_AIR_DEFENSE) and Self-Propelled Anti-Air (CLASS_ANTI_AIR) share the Air Defense upgrade branch
        return (currentDef.unitClass == PgDatabase.CLASS_AIR_DEFENSE && targetClass == PgDatabase.CLASS_ANTI_AIR)
                || (currentDef.unitClass == PgDatabase.CLASS_ANTI_AIR && targetClass == PgDatabase.CLASS_AIR_DEFENSE);
    }

    private void showUpgradeUnitDialog(PgDatabase.ActiveUnit u) {
        if (u == null || !engine.canUpgradeUnit(u)) {
            Toast.makeText(this, "Ground units can only upgrade on a controlled City (planes on a controlled Airfield) with no adjacent enemies.", Toast.LENGTH_LONG).show();
            return;
        }
        PgDatabase.UnitDef currentDef = db.equipment.get(u.unitDefId);

        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 20, 28, 20);

        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xFF161B16);
        rootBg.setCornerRadius(16f);
        rootBg.setStroke(3, 0xFFC5A059);
        root.setBackground(rootBg);

        // 1. Header Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, 10);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("UPGRADE UNIT & MOUNTS");
        tvTitle.setTextColor(0xFFFFD54F);
        tvTitle.setTextSize(19f);
        tvTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        headerRow.addView(tvTitle);

        String starsText = u.getStars() > 0 ? (" ★".repeat(u.getStars())) : "";
        TextView tvUnitSub = new TextView(this);
        tvUnitSub.setText(String.format(Locale.ROOT, "  •  %s%s (Str: %d/%d, Exp: %d)",
                currentDef.name, starsText, u.strength, u.getMaxOverstrength(), u.experience));
        tvUnitSub.setTextColor(0xFFB0BEC5);
        tvUnitSub.setTextSize(12.5f);
        headerRow.addView(tvUnitSub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvPrestigePill = new TextView(this);
        tvPrestigePill.setText("★ " + engine.getPlayerPrestige() + " PRESTIGE");
        tvPrestigePill.setTextColor(0xFF1A2218);
        tvPrestigePill.setTextSize(13.5f);
        tvPrestigePill.setTypeface(Typeface.DEFAULT_BOLD);
        tvPrestigePill.setPadding(20, 8, 20, 8);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xFFFFD54F);
        pillBg.setCornerRadius(8f);
        tvPrestigePill.setBackground(pillBg);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.setMargins(12, 0, 16, 0);
        headerRow.addView(tvPrestigePill, pillLp);

        CheckBox cbAllYears = new CheckBox(this);
        cbAllYears.setText("All Years (1939–45)");
        cbAllYears.setTextColor(0xFFE0E0E0);
        cbAllYears.setTextSize(12f);
        cbAllYears.setChecked(purchaseShowAllYears);
        headerRow.addView(cbAllYears);

        Button btnClose = createCompactButton("✕ CLOSE", 0xFF7F2424);
        btnClose.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
            if (engine.isDeploymentPhase && (u.col < 0 || u.row < 0)) {
                showCoreDeploymentDialog();
            }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 60);
        closeLp.setMargins(14, 0, 0, 0);
        btnClose.setLayoutParams(closeLp);
        headerRow.addView(btnClose);

        root.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Category Tab Strip
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setPadding(0, 10, 0, 10);
        tabScroll.addView(tabStrip);
        root.addView(tabScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 3. Main Master-Detail Split Area
        LinearLayout splitBody = new LinearLayout(this);
        splitBody.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(splitBody, bodyLp);

        // Left Pane: Illustrated Upgrade Options (52% width)
        ScrollView rosterScroll = new ScrollView(this);
        LinearLayout rosterContainer = new LinearLayout(this);
        rosterContainer.setOrientation(LinearLayout.VERTICAL);
        rosterContainer.setPadding(0, 0, 12, 0);
        rosterScroll.addView(rosterContainer);
        splitBody.addView(rosterScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.08f));

        // Right Pane: Detailed Showcase & Transport Selector (48% width)
        ScrollView detailScroll = new ScrollView(this);
        LinearLayout detailCard = new LinearLayout(this);
        detailCard.setOrientation(LinearLayout.VERTICAL);
        detailCard.setPadding(20, 16, 20, 16);
        GradientDrawable detailBg = new GradientDrawable();
        detailBg.setColor(0xFF212922);
        detailBg.setCornerRadius(12f);
        detailBg.setStroke(2, 0xFFC5A059);
        detailCard.setBackground(detailBg);
        detailScroll.addView(detailCard);
        splitBody.addView(detailScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.92f));

        final int[] curCategory = new int[]{currentDef.unitClass};
        final int[] curSelectedUnitDefId = new int[]{u.unitDefId};
        final int[] curSelectedTransportId = new int[]{u.transportDefId};

        final Runnable[] renderUI = new Runnable[1];
        renderUI[0] = () -> {
            List<PgDatabase.UnitDef> allAvail = getAvailableUnitsForPlayer(purchaseShowAllYears);
            tabStrip.removeAllViews();
            rosterContainer.removeAllViews();
            detailCard.removeAllViews();

            // Category tabs (only show categories eligible for this unit's class)
            for (int i = 0; i < PURCHASE_CATEGORY_CLASSES.length; i++) {
                final int catClass = PURCHASE_CATEGORY_CLASSES[i];
                if (!isValidUpgradeCategoryForUnit(currentDef, catClass)) continue;
                String catName = PURCHASE_CATEGORY_LABELS[i];
                int repUnitId = CATEGORY_REP_UNIT_IDS[i];
                int count = 0;
                for (PgDatabase.UnitDef cand : allAvail) {
                    if (cand.unitClass == catClass) count++;
                }
                if (catClass == currentDef.unitClass && count == 0) count = 1;

                boolean isSelected = (catClass == curCategory[0]);
                boolean isCurrentUnitClass = (catClass == currentDef.unitClass);

                LinearLayout tabBox = new LinearLayout(this);
                tabBox.setOrientation(LinearLayout.HORIZONTAL);
                tabBox.setGravity(Gravity.CENTER_VERTICAL);
                tabBox.setPadding(14, 8, 16, 8);

                GradientDrawable tabBg = new GradientDrawable();
                tabBg.setColor(isSelected ? 0xFF3A4A36 : (count > 0 ? 0xFF232C24 : 0xFF1A1E1A));
                tabBg.setCornerRadius(10f);
                tabBg.setStroke(isSelected ? 3 : (isCurrentUnitClass ? 2 : 1),
                        isSelected ? 0xFFFFD54F : (isCurrentUnitClass ? 0xFF81C784 : 0xFF566557));
                tabBox.setBackground(tabBg);

                ImageView tabIcon = new ImageView(this);
                if (repUnitId < db.equipment.size()) {
                    Bitmap catBmp = assets.getTrimmedSprite("TACICONS.SHP", db.equipment.get(repUnitId).iconId);
                    if (catBmp != null) {
                        tabIcon.setImageBitmap(catBmp);
                    }
                }
                LinearLayout.LayoutParams tiLp = new LinearLayout.LayoutParams(54, 38);
                tiLp.setMargins(0, 0, 10, 0);
                tabBox.addView(tabIcon, tiLp);

                TextView tvTab = new TextView(this);
                String tag = isCurrentUnitClass ? " ★" : "";
                tvTab.setText(String.format(Locale.ROOT, "%s (%d)%s", catName, count, tag));
                tvTab.setTextColor(isSelected ? 0xFFFFD54F : (isCurrentUnitClass ? 0xFF81C784 : (count > 0 ? Color.WHITE : 0xFF78909C)));
                tvTab.setTextSize(12.5f);
                tvTab.setTypeface(Typeface.DEFAULT_BOLD);
                tabBox.addView(tvTab);

                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tLp.setMargins(0, 0, 10, 0);
                tabBox.setLayoutParams(tLp);

                tabBox.setOnClickListener(v -> {
                    sounds.playClick();
                    curCategory[0] = catClass;
                    curSelectedUnitDefId[0] = -1;
                    renderUI[0].run();
                });
                tabStrip.addView(tabBox);
            }

            // Gather units for active category
            List<PgDatabase.UnitDef> catUnits = new ArrayList<>();
            boolean currentDefInList = false;
            for (PgDatabase.UnitDef cand : allAvail) {
                if (cand.unitClass == curCategory[0]) {
                    catUnits.add(cand);
                    if (cand.id == currentDef.id) currentDefInList = true;
                }
            }
            // Always ensure current equipment is present so user can keep it and just add/change mounts
            if (curCategory[0] == currentDef.unitClass && !currentDefInList) {
                catUnits.add(0, currentDef);
            }

            if (catUnits.isEmpty()) {
                TextView emptyTv = new TextView(this);
                emptyTv.setText("No upgrade candidates available in this category.\nCheck 'All Years (1939–45)' above to view all equipment.");
                emptyTv.setTextColor(0xFFB0BEC5);
                emptyTv.setTextSize(14f);
                emptyTv.setPadding(24, 60, 24, 60);
                emptyTv.setGravity(Gravity.CENTER);
                rosterContainer.addView(emptyTv);
                return;
            }

            // Pick active unit
            PgDatabase.UnitDef activeDef = null;
            for (PgDatabase.UnitDef cand : catUnits) {
                if (cand.id == curSelectedUnitDefId[0]) {
                    activeDef = cand;
                    break;
                }
            }
            if (activeDef == null) {
                activeDef = (curCategory[0] == currentDef.unitClass) ? currentDef : catUnits.get(0);
                curSelectedUnitDefId[0] = activeDef.id;
            }

            // Render Left Pane Unit Cards
            for (PgDatabase.UnitDef cand : catUnits) {
                boolean isChosen = (cand.id == activeDef.id);
                boolean isCurrentEqp = (cand.id == u.unitDefId);

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(12, 10, 14, 10);

                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(isChosen ? 0xFF2F3E30 : 0xFF212922);
                cardBg.setCornerRadius(10f);
                cardBg.setStroke(isChosen ? 3 : 1, isChosen ? 0xFFFFD54F : (isCurrentEqp ? 0xFF81C784 : 0xFF4A5D4C));
                card.setBackground(cardBg);

                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.setMargins(0, 0, 0, 10);
                card.setLayoutParams(cardLp);

                // Framed portrait
                ImageView ivPortrait = new ImageView(this);
                ivPortrait.setImageBitmap(assets.createUnitPortrait(cand.iconId, cand.unitClass, 156, 100));
                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(156, 100);
                pLp.setMargins(0, 0, 14, 0);
                card.addView(ivPortrait, pLp);

                // Middle Info
                LinearLayout infoCol = new LinearLayout(this);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                card.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout titleRow = new LinearLayout(this);
                titleRow.setOrientation(LinearLayout.HORIZONTAL);
                titleRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView tvName = new TextView(this);
                tvName.setText(cand.name);
                tvName.setTextColor(isChosen ? 0xFFFFD54F : Color.WHITE);
                tvName.setTextSize(15.5f);
                tvName.setTypeface(Typeface.DEFAULT_BOLD);
                titleRow.addView(tvName);

                if (isCurrentEqp) {
                    TextView tvCurrentBadge = new TextView(this);
                    tvCurrentBadge.setText(" CURRENT ");
                    tvCurrentBadge.setTextColor(0xFF1B5E20);
                    tvCurrentBadge.setTextSize(10f);
                    tvCurrentBadge.setTypeface(Typeface.DEFAULT_BOLD);
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setColor(0xFFA5D6A7);
                    badgeBg.setCornerRadius(4f);
                    tvCurrentBadge.setBackground(badgeBg);
                    LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    bLp.setMargins(10, 0, 0, 0);
                    titleRow.addView(tvCurrentBadge, bLp);
                }
                infoCol.addView(titleRow);

                String targetStr = (cand.targetType == PgDatabase.TARGET_HARD) ? "Armored / Hard"
                        : (cand.targetType == PgDatabase.TARGET_AIR ? "Aircraft" : "Soft Target");
                TextView tvSub = new TextView(this);
                tvSub.setText(String.format(Locale.ROOT, "Service: 19%02d–19%02d  •  %s",
                        cand.startYear, cand.endYear, targetStr));
                tvSub.setTextColor(0xFFA5B5A7);
                tvSub.setTextSize(11.5f);
                infoCol.addView(tvSub);

                TextView tvQuickStats = new TextView(this);
                tvQuickStats.setText(String.format(Locale.ROOT,
                        "SA: %d   HA: %d   AA: %d   |   GD: %d   AD: %d   |   Mov: %d   Rng: %d",
                        cand.softAttack, cand.hardAttack, cand.airAttack, cand.groundDefense, cand.airDefense, cand.movement, cand.range));
                tvQuickStats.setTextColor(0xFFE0E8E1);
                tvQuickStats.setTextSize(12f);
                tvQuickStats.setPadding(0, 4, 0, 0);
                infoCol.addView(tvQuickStats);

                // Right Cost Badge
                int chassisUpgCost = (cand.id == u.unitDefId) ? 0 : Math.max(20, cand.cost - (currentDef.cost / 2));
                boolean affordable = (engine.getPlayerPrestige() >= chassisUpgCost);

                LinearLayout priceCol = new LinearLayout(this);
                priceCol.setOrientation(LinearLayout.VERTICAL);
                priceCol.setGravity(Gravity.CENTER);
                priceCol.setPadding(14, 8, 14, 8);
                GradientDrawable priceBg = new GradientDrawable();
                priceBg.setColor(isCurrentEqp ? 0xFF1B3820 : (affordable ? 0xFF28472C : 0xFF3E2723));
                priceBg.setCornerRadius(8f);
                priceBg.setStroke(1, isCurrentEqp ? 0xFF81C784 : (affordable ? 0xFF81C784 : 0xFFE57373));
                priceCol.setBackground(priceBg);

                TextView tvCost = new TextView(this);
                tvCost.setText(isCurrentEqp ? "★ 0" : ("★ " + chassisUpgCost));
                tvCost.setTextColor(isCurrentEqp ? 0xFF81C784 : (affordable ? 0xFFFFE082 : 0xFFFF8A80));
                tvCost.setTextSize(14f);
                tvCost.setTypeface(Typeface.DEFAULT_BOLD);
                priceCol.addView(tvCost);

                TextView tvCostSub = new TextView(this);
                tvCostSub.setText(isCurrentEqp ? "KEEP CHASSIS" : "UPGRADE");
                tvCostSub.setTextColor(0xFFCFD8DC);
                tvCostSub.setTextSize(9.5f);
                priceCol.addView(tvCostSub);

                card.addView(priceCol);

                card.setOnClickListener(v -> {
                    sounds.playClick();
                    curSelectedUnitDefId[0] = cand.id;
                    renderUI[0].run();
                });

                rosterContainer.addView(card);
            }

            // Render Right Pane Details & Transport Picker
            final PgDatabase.UnitDef sel = activeDef;
            boolean supportsTransport = (sel.unitClass == PgDatabase.CLASS_INFANTRY
                    || sel.unitClass == PgDatabase.CLASS_ARTILLERY
                    || sel.unitClass == PgDatabase.CLASS_ANTI_TANK
                    || sel.unitClass == PgDatabase.CLASS_ANTI_AIR
                    || sel.unitClass == PgDatabase.CLASS_AIR_DEFENSE);
            if (!supportsTransport) {
                curSelectedTransportId[0] = 0;
            }

            // Hero Header
            LinearLayout heroRow = new LinearLayout(this);
            heroRow.setOrientation(LinearLayout.HORIZONTAL);
            heroRow.setGravity(Gravity.CENTER_VERTICAL);
            heroRow.setPadding(0, 0, 0, 12);

            ImageView ivHero = new ImageView(this);
            ivHero.setImageBitmap(assets.createUnitPortrait(sel.iconId, sel.unitClass, 240, 148));
            LinearLayout.LayoutParams heroImgLp = new LinearLayout.LayoutParams(240, 148);
            heroImgLp.setMargins(0, 0, 16, 0);
            heroRow.addView(ivHero, heroImgLp);

            LinearLayout heroInfo = new LinearLayout(this);
            heroInfo.setOrientation(LinearLayout.VERTICAL);

            String clsName = (sel.unitClass >= 0 && sel.unitClass < PgDatabase.CLASS_NAMES.length)
                    ? PgDatabase.CLASS_NAMES[sel.unitClass] : "Unit";
            TextView tvHeroClass = new TextView(this);
            tvHeroClass.setText(clsName.toUpperCase(Locale.ROOT) + "  •  EQUIPMENT #" + sel.id);
            tvHeroClass.setTextColor(0xFF81C784);
            tvHeroClass.setTextSize(11f);
            tvHeroClass.setTypeface(Typeface.DEFAULT_BOLD);
            heroInfo.addView(tvHeroClass);

            TextView tvHeroName = new TextView(this);
            tvHeroName.setText(sel.name);
            tvHeroName.setTextColor(0xFFFFD54F);
            tvHeroName.setTextSize(20f);
            tvHeroName.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            heroInfo.addView(tvHeroName);

            int chassisCost = (sel.id == u.unitDefId) ? 0 : Math.max(20, sel.cost - (currentDef.cost / 2));
            TextView tvHeroMeta = new TextView(this);
            tvHeroMeta.setText(String.format(Locale.ROOT,
                    "Service: 19%02d–19%02d  •  Chassis Upgrade Cost: ★ %d\nUnit Strength: %d/%d  •  Experience: %d (%s)",
                    sel.startYear, sel.endYear, chassisCost,
                    u.strength, u.getMaxOverstrength(), u.experience,
                    u.getStars() > 0 ? (u.getStars() + "★ Veteran") : "Regular"));
            tvHeroMeta.setTextColor(0xFFD0D8D0);
            tvHeroMeta.setTextSize(12f);
            heroInfo.addView(tvHeroMeta);

            heroRow.addView(heroInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            detailCard.addView(heroRow);

            // 4x3 Tactical Specs with Diff Comparison
            TextView tvSpecsHdr = new TextView(this);
            tvSpecsHdr.setText("COMBAT & OPERATIONAL SPECIFICATIONS (COMPARISON)");
            tvSpecsHdr.setTextColor(0xFFC5A059);
            tvSpecsHdr.setTextSize(11.5f);
            tvSpecsHdr.setTypeface(Typeface.DEFAULT_BOLD);
            tvSpecsHdr.setPadding(0, 4, 0, 6);
            detailCard.addView(tvSpecsHdr);

            Object[][] statRows = {
                {
                    createStatBadge("Soft Attack", formatStatDiff(sel.softAttack, currentDef.softAttack), sel.softAttack, 24, 0xFFE57373),
                    createStatBadge("Hard Attack", formatStatDiff(sel.hardAttack, currentDef.hardAttack), sel.hardAttack, 24, 0xFFFF8A65),
                    createStatBadge("Air Attack", formatStatDiff(sel.airAttack, currentDef.airAttack), sel.airAttack, 20, 0xFF64B5F6)
                },
                {
                    createStatBadge("Ground Def", formatStatDiff(sel.groundDefense, currentDef.groundDefense), sel.groundDefense, 20, 0xFF81C784),
                    createStatBadge("Air Defense", formatStatDiff(sel.airDefense, currentDef.airDefense), sel.airDefense, 20, 0xFF4DB6AC),
                    createStatBadge("Close Def", formatStatDiff(sel.closeDefense, currentDef.closeDefense), sel.closeDefense, 12, 0xFFAED581)
                },
                {
                    createStatBadge("Initiative", formatStatDiff(sel.initiative, currentDef.initiative), sel.initiative, 15, 0xFFFFD54F),
                    createStatBadge("Movement", formatStatDiff(sel.movement, currentDef.movement) + " hex", sel.movement, 14, 0xFF90CAF9),
                    createStatBadge("Range / Spot", sel.range + " / " + sel.spotting, sel.range * 3 + sel.spotting, 16, 0xFFCE93D8)
                },
                {
                    createStatBadge("Max Fuel", engine.supplyEnabled ? formatStatDiff(sel.maxFuel, currentDef.maxFuel) : "∞ (OFF)", sel.maxFuel, 90, 0xFFFFB74D),
                    createStatBadge("Max Ammo", engine.supplyEnabled ? formatStatDiff(sel.maxAmmo, currentDef.maxAmmo) : "∞ (OFF)", sel.maxAmmo, 16, 0xFFA1887F),
                    createStatBadge("Naval Atk", formatStatDiff(sel.navalAttack, currentDef.navalAttack), sel.navalAttack, 16, 0xFF4FC3F7)
                }
            };

            for (Object[] rowWidgets : statRows) {
                LinearLayout sRow = new LinearLayout(this);
                sRow.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < rowWidgets.length; c++) {
                    View w = (View) rowWidgets[c];
                    LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    wLp.setMargins(c == 0 ? 0 : 6, 0, c == rowWidgets.length - 1 ? 0 : 6, 8);
                    sRow.addView(w, wLp);
                }
                detailCard.addView(sRow);
            }

            // Organic Transport / Mount Picker
            if (supportsTransport) {
                TextView tvTrpHdr = new TextView(this);
                tvTrpHdr.setText("ORGANIC LAND TRANSPORT / MOUNTS (OPTIONAL)");
                tvTrpHdr.setTextColor(0xFFC5A059);
                tvTrpHdr.setTextSize(11.5f);
                tvTrpHdr.setTypeface(Typeface.DEFAULT_BOLD);
                tvTrpHdr.setPadding(0, 6, 0, 6);
                detailCard.addView(tvTrpHdr);

                LinearLayout trpStrip = new LinearLayout(this);
                trpStrip.setOrientation(LinearLayout.HORIZONTAL);
                int[] trpIds = getAvailableLandTransportIdsForPlayer();
                for (int i = 0; i < trpIds.length; i++) {
                    final int tid = trpIds[i];
                    boolean isTrpSel = (curSelectedTransportId[0] == tid);
                    boolean isEquipped = (u.transportDefId == tid);
                    PgDatabase.UnitDef trpDef = (tid > 0 && tid < db.equipment.size()) ? db.equipment.get(tid) : null;
                    int addCost = (tid == u.transportDefId) ? 0 : ((trpDef != null) ? Math.max(10, trpDef.cost / 2) : 0);

                    LinearLayout tCard = new LinearLayout(this);
                    tCard.setOrientation(LinearLayout.VERTICAL);
                    tCard.setGravity(Gravity.CENTER_HORIZONTAL);
                    tCard.setPadding(8, 8, 8, 8);
                    GradientDrawable tBg = new GradientDrawable();
                    tBg.setColor(isTrpSel ? 0xFF334434 : 0xFF19201A);
                    tBg.setCornerRadius(8f);
                    tBg.setStroke(isTrpSel ? 2 : 1, isTrpSel ? 0xFFFFD54F : (isEquipped ? 0xFF81C784 : 0xFF455A48));
                    tCard.setBackground(tBg);

                    ImageView ivTrp = new ImageView(this);
                    if (trpDef != null) {
                        ivTrp.setImageBitmap(assets.createUnitPortrait(trpDef.iconId, trpDef.unitClass, 110, 66));
                    } else {
                        ivTrp.setImageBitmap(assets.createUnitPortrait(sel.iconId, sel.unitClass, 110, 66));
                    }
                    tCard.addView(ivTrp, new LinearLayout.LayoutParams(110, 66));

                    TextView tvTrpName = new TextView(this);
                    tvTrpName.setText(trpDef != null ? trpDef.name : "Foot / Towed");
                    tvTrpName.setTextColor(isTrpSel ? 0xFFFFD54F : Color.WHITE);
                    tvTrpName.setTextSize(11f);
                    tvTrpName.setTypeface(Typeface.DEFAULT_BOLD);
                    tvTrpName.setSingleLine(true);
                    tvTrpName.setPadding(0, 4, 0, 0);
                    tCard.addView(tvTrpName);

                    TextView tvTrpMeta = new TextView(this);
                    String badgeStr = isEquipped ? "EQUIPPED" : (addCost > 0 ? ("+" + addCost + "★") : "0★");
                    tvTrpMeta.setText(trpDef != null
                            ? String.format(Locale.ROOT, "%s • Mov:%d GD:%d", badgeStr, trpDef.movement, trpDef.groundDefense)
                            : String.format(Locale.ROOT, "%s • Mov:%d", badgeStr, sel.movement));
                    tvTrpMeta.setTextColor(isEquipped ? 0xFF81C784 : 0xFFB0BEC5);
                    tvTrpMeta.setTextSize(10f);
                    tCard.addView(tvTrpMeta);

                    tCard.setOnClickListener(v -> {
                        sounds.playClick();
                        curSelectedTransportId[0] = tid;
                        renderUI[0].run();
                    });

                    LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    tcLp.setMargins(i == 0 ? 0 : 4, 0, i == trpIds.length - 1 ? 0 : 4, 8);
                    trpStrip.addView(tCard, tcLp);
                }
                detailCard.addView(trpStrip);
            }

            final int finalTotalCost = engine.getUpgradeCost(u, sel.id, curSelectedTransportId[0]);
            boolean canAfford = (engine.getPlayerPrestige() >= finalTotalCost);
            boolean noChange = (sel.id == u.unitDefId && curSelectedTransportId[0] == u.transportDefId);

            String trpSuffix = (curSelectedTransportId[0] > 0 && curSelectedTransportId[0] < db.equipment.size())
                    ? (" + " + db.equipment.get(curSelectedTransportId[0]).name) : "";

            String btnLabel;
            int btnBgColor;
            int btnTextColor;

            if (noChange) {
                btnLabel = "EQUIPMENT UNCHANGED (SELECT AN UPGRADE OR MOUNT)";
                btnBgColor = 0xFF37474F;
                btnTextColor = 0xFFB0BEC5;
            } else if (canAfford) {
                btnLabel = String.format(Locale.ROOT, "UPGRADE %s TO %s%s  —  ★ %d PRESTIGE",
                        currentDef.name.toUpperCase(Locale.ROOT), sel.name.toUpperCase(Locale.ROOT), trpSuffix, finalTotalCost);
                btnBgColor = 0xFF2E6B36;
                btnTextColor = 0xFFFFF59D;
            } else {
                btnLabel = String.format(Locale.ROOT, "INSUFFICIENT PRESTIGE (NEED ★ %d, HAVE ★ %d)",
                        finalTotalCost, engine.getPlayerPrestige());
                btnBgColor = 0xFF455A64;
                btnTextColor = 0xFFCFD8DC;
            }

            Button btnConfirmUpg = createStyledButton(btnLabel, btnBgColor, btnTextColor);
            LinearLayout.LayoutParams upgBtnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            upgBtnLp.setMargins(0, 10, 0, 4);
            btnConfirmUpg.setLayoutParams(upgBtnLp);
            btnConfirmUpg.setEnabled(!noChange && canAfford);

            btnConfirmUpg.setOnClickListener(v -> {
                if (noChange) return;
                if (!canAfford) {
                    Toast.makeText(this, "Insufficient Prestige (" + finalTotalCost + "★ required).", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (engine.upgradeUnit(u, sel.id, curSelectedTransportId[0])) {
                    sounds.playSelect();
                    dialog.dismiss();
                    if (engine.isDeploymentPhase && (u.col < 0 || u.row < 0)) {
                        showCoreDeploymentDialog();
                    } else {
                        mapView.selectUnit(u);
                        refreshHud();
                        mapView.invalidate();
                    }
                    Toast.makeText(this, "Successfully upgraded to " + sel.name + trpSuffix + "!", Toast.LENGTH_SHORT).show();
                }
            });
            detailCard.addView(btnConfirmUpg);
        };

        cbAllYears.setOnCheckedChangeListener((btn, isChecked) -> {
            purchaseShowAllYears = isChecked;
            renderUI[0].run();
        });

        renderUI[0].run();
        dialog.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.show();
    }

    public void showFieldManualDialog() {
        showFieldManualDialog(0);
    }

    public void showFieldManualDialog(final int initialTab) {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF131A15);
        root.setPadding(20, 14, 20, 14);

        // Header Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(16, 10, 16, 10);
        GradientDrawable hdrBg = new GradientDrawable();
        hdrBg.setColor(0xFF1F2721);
        hdrBg.setCornerRadius(10f);
        hdrBg.setStroke(2, 0xFFC5A059);
        headerRow.setBackground(hdrBg);

        ImageView ivFlag = new ImageView(this);
        Bitmap flagBmp = assets != null ? assets.getTrimmedSprite("FLAGS.SHP", engine != null ? engine.getPlayerFlagSpriteIndex() : 0) : null;
        if (flagBmp != null) {
            ivFlag.setImageBitmap(Bitmap.createScaledBitmap(flagBmp, 48, 36, false));
        }
        LinearLayout.LayoutParams flagLp = new LinearLayout.LayoutParams(52, 40);
        flagLp.setMargins(0, 0, 14, 0);
        headerRow.addView(ivFlag, flagLp);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText("PANZER GENERAL — MILITARY REFERENCE LIBRARY");
        tvHeaderTitle.setTextColor(0xFFFFD54F);
        tvHeaderTitle.setTextSize(15f);
        tvHeaderTitle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titleStack.addView(tvHeaderTitle);

        TextView tvSubHeader = new TextView(this);
        tvSubHeader.setText("Official SSI 1994 Instruction Manual, Tactical Strategy Guide & Campaign Compendium");
        tvSubHeader.setTextColor(0xFFB0BEC5);
        tvSubHeader.setTextSize(11.5f);
        titleStack.addView(tvSubHeader);
        headerRow.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClose = createCompactButton("✕ CLOSE", 0xFF7F2424);
        btnClose.setOnClickListener(v -> {
            sounds.playClick();
            dialog.dismiss();
        });
        headerRow.addView(btnClose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 60));
        root.addView(headerRow);

        // Search Bar Row
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(12, 6, 12, 6);
        GradientDrawable sbBg = new GradientDrawable();
        sbBg.setColor(0xFF1A221C);
        sbBg.setCornerRadius(8f);
        sbBg.setStroke(1, 0xFF37474F);
        searchRow.setBackground(sbBg);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.setMargins(0, 10, 0, 8);

        final EditText etSearch = new EditText(this);
        etSearch.setHint("🔍 Search manual, rules, tactics & scenario walkthroughs...");
        etSearch.setHintTextColor(0xFF78909C);
        etSearch.setTextColor(0xFFECEFF1);
        etSearch.setTextSize(13f);
        etSearch.setBackgroundColor(Color.TRANSPARENT);
        etSearch.setSingleLine(true);
        etSearch.setPadding(10, 8, 10, 8);
        searchRow.addView(etSearch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClearSearch = createCompactButton("CLEAR", 0xFF37474F);
        searchRow.addView(btnClearSearch);
        root.addView(searchRow, srp);

        // Tab Strip
        LinearLayout tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tslp.setMargins(0, 0, 0, 8);

        final Button btnTabManual = createCompactButton("📖 INSTRUCTION MANUAL", 0xFF2E7D32);
        final Button btnTabStrategy = createCompactButton("🎖️ STRATEGY GUIDE", 0xFF263238);
        final Button btnTabCampaign = createCompactButton("🗺️ CAMPAIGN & SCENARIOS", 0xFF263238);

        LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(0, 68, 1f);
        tblp.setMargins(0, 0, 8, 0);
        tabStrip.addView(btnTabManual, tblp);

        LinearLayout.LayoutParams tblp2 = new LinearLayout.LayoutParams(0, 68, 1f);
        tblp2.setMargins(0, 0, 8, 0);
        tabStrip.addView(btnTabStrategy, tblp2);

        LinearLayout.LayoutParams tblp3 = new LinearLayout.LayoutParams(0, 68, 1f);
        tabStrip.addView(btnTabCampaign, tblp3);
        root.addView(tabStrip, tslp);

        // Quick Jump Chips Bar
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout chipContainer = new LinearLayout(this);
        chipContainer.setOrientation(LinearLayout.HORIZONTAL);
        chipContainer.setPadding(0, 2, 0, 2);
        chipScroll.addView(chipContainer);
        LinearLayout.LayoutParams cslp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cslp.setMargins(0, 0, 0, 8);
        root.addView(chipScroll, cslp);

        // Content Area Container
        FrameLayout contentArea = new FrameLayout(this);
        LinearLayout.LayoutParams calp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);

        // 1. Text Viewer ScrollView
        final ScrollView contentScroll = new ScrollView(this);
        final TextView tvContent = new TextView(this);
        tvContent.setPadding(22, 18, 22, 26);
        tvContent.setTextSize(13f);
        tvContent.setTextColor(0xFFECEFF1);
        tvContent.setTypeface(Typeface.MONOSPACE);
        tvContent.setLineSpacing(0, 1.18f);
        tvContent.setTextIsSelectable(true);
        GradientDrawable tvBg = new GradientDrawable();
        tvBg.setColor(0xFF18201A);
        tvBg.setCornerRadius(8f);
        tvBg.setStroke(1, 0xFF2E3D31);
        tvContent.setBackground(tvBg);
        contentScroll.addView(tvContent);
        contentArea.addView(contentScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Search Results ScrollView
        final ScrollView searchScroll = new ScrollView(this);
        final LinearLayout searchList = new LinearLayout(this);
        searchList.setOrientation(LinearLayout.VERTICAL);
        searchList.setPadding(6, 6, 6, 12);
        searchScroll.addView(searchList);
        searchScroll.setVisibility(View.GONE);
        contentArea.addView(searchScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(contentArea, calp);

        final int[] currentTab = {Math.max(0, Math.min(2, initialTab))};

        final Runnable[] scrollToTarget = new Runnable[1];
        final String[] jumpTarget = new String[1];
        scrollToTarget[0] = () -> {
            if (jumpTarget[0] == null) return;
            String text = tvContent.getText().toString();
            int idx = text.indexOf(jumpTarget[0]);
            if (idx >= 0 && tvContent.getLayout() != null) {
                int line = tvContent.getLayout().getLineForOffset(idx);
                int y = tvContent.getLayout().getLineTop(line);
                contentScroll.smoothScrollTo(0, Math.max(0, y - 10));
            }
            jumpTarget[0] = null;
        };

        final Runnable populateChips = () -> {
            chipContainer.removeAllViews();
            String[][] chips;
            if (currentTab[0] == 0) {
                chips = new String[][]{
                    {"Ch.1 Command", "CHAPTER 1: PHILOSOPHY"},
                    {"Ch.2 Map & Grid", "CHAPTER 2: THE BATTLEFIELD"},
                    {"Ch.3 Movement & ZOC", "CHAPTER 3: MOVEMENT"},
                    {"Ch.4 12 Classes", "CHAPTER 4: THE 12 UNIT CLASSES"},
                    {"Ch.5 Combat CRT", "CHAPTER 5: COMBAT MECHANICS"},
                    {"Ch.6 Rugged Def & Overrun", "CHAPTER 6: RUGGED DEFENSE"},
                    {"Ch.7 Supply & Overstrength", "CHAPTER 7: SUPPLY"},
                    {"Ch.8 Upgrading", "CHAPTER 8: UPGRADING EQUIPMENT"},
                    {"Ch.9 Weather", "CHAPTER 9: THE WEATHER SYSTEM"},
                    {"Ch.10 Campaign Tree", "CHAPTER 10: PRESTIGE ECONOMY"}
                };
            } else if (currentTab[0] == 1) {
                chips = new String[][]{
                    {"(1) Key Terms", "(1). Glossary Of Key Terms"},
                    {"Entrenchment", "Entrenchment\nEntrenchment"},
                    {"Experience", "Experience\nIn addition"},
                    {"Prestige Costs", "Prestige Costs\nThe prestige"},
                    {"Replacements", "Replacements\nDon't let"},
                    {"Rugged Defense", "Rugged Defense\nThe concept"},
                    {"Terrain", "Terrain\nCareful"},
                    {"Weather", "Weather\nBad weather"},
                    {"(2) Tactical Overview", "(2). Tactical Overview"},
                    {"Offensive Tactics", "Offensive Tactics"},
                    {"Defensive Tactics", "Defensive Tactics"},
                    {"The Air War", "The Air War"},
                    {"The Naval War", "The Naval War"},
                    {"(3) Combined Arms", "(3). Combined Arms"},
                    {"City Assault Walkthrough", "An Example Of A Combined Arms Attack"}
                };
            } else {
                chips = new String[][]{
                    {"(1) The Campaigns", "(1). The Campaigns."},
                    {"1939 Campaign", "1939 Campaign"},
                    {"1941 Western", "1941 Western Campaign"},
                    {"1943 Western", "1943 Western Campaign"},
                    {"Eastern Front", "1941 & 1943 Eastern Campaign"},
                    {"(2) Campaign Notes", "(2). Campaign Notes"},
                    {"Force Balance", "Campaign Force Balance"},
                    {"Core vs Aux", "Core Versus Auxiliary Units"},
                    {"Upgrading", "Upgrading And Elite Replacements"},
                    {"(3) Scenario Notes", "(3) Scenario Notes"},
                    {"Poland / Warsaw", "1. POLAND"},
                    {"Norway", "3. NORWAY"},
                    {"Low Countries", "4. LOW COUNTRIES"},
                    {"France", "5. FRANCE"},
                    {"Sealion", "6, 38 & 11.. SEALION"},
                    {"North Africa", "7. NORTH AFRICA"},
                    {"Middle East", "8. MIDDLE EAST"},
                    {"El Alamein", "9. EL ALAMEIN"},
                    {"Caucasus", "10. CAUCASUS"},
                    {"Torch / Husky", "12. TORCH"},
                    {"Anzio", "14. ANZIO"},
                    {"D-Day", "15. D-DAY"},
                    {"Ardennes (Bulge)", "17. ARDENNES"},
                    {"Market-Garden", "19. MARKET-GARDEN"},
                    {"Barbarossa", "23. BARBAROSSA"},
                    {"Kiev", "24. KIEV"},
                    {"Moscow", "25, 27, 31, & 35. MOSCOW"},
                    {"Sevastopol", "26. SEVASTOPOL"},
                    {"Stalingrad", "28. STALINGRAD"},
                    {"Kursk", "30. KURSK"},
                    {"Berlin", "35. BERLIN"},
                    {"Washington", "36. WASHINGTON"}
                };
            }

            for (String[] pair : chips) {
                final String label = pair[0];
                final String target = pair[1];
                Button chip = createCompactButton(label, 0xFF2A362D);
                chip.setTextSize(11f);
                chip.setOnClickListener(v -> {
                    sounds.playClick();
                    jumpTarget[0] = target;
                    if (tvContent.getLayout() != null) {
                        scrollToTarget[0].run();
                    } else {
                        tvContent.post(scrollToTarget[0]);
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 54);
                clp.setMargins(0, 0, 6, 0);
                chipContainer.addView(chip, clp);
            }
        };

        final java.lang.Runnable updateTabDisplay = () -> {
            updateCompactButtonColor(btnTabManual, currentTab[0] == 0 ? 0xFF2E7D32 : 0xFF263238);
            btnTabManual.setTextColor(currentTab[0] == 0 ? 0xFFFFD54F : 0xFFCFD8DC);

            updateCompactButtonColor(btnTabStrategy, currentTab[0] == 1 ? 0xFF2E7D32 : 0xFF263238);
            btnTabStrategy.setTextColor(currentTab[0] == 1 ? 0xFFFFD54F : 0xFFCFD8DC);

            updateCompactButtonColor(btnTabCampaign, currentTab[0] == 2 ? 0xFF2E7D32 : 0xFF263238);
            btnTabCampaign.setTextColor(currentTab[0] == 2 ? 0xFFFFD54F : 0xFFCFD8DC);

            if (currentTab[0] == 0) {
                tvContent.setText(PgManualManager.getInstructionManualText());
            } else if (currentTab[0] == 1) {
                tvContent.setText(PgManualManager.getStrategyGuideText(MainActivity.this));
            } else {
                tvContent.setText(PgManualManager.getCampaignGuideText(MainActivity.this));
            }

            populateChips.run();
            contentScroll.scrollTo(0, 0);
        };

        btnTabManual.setOnClickListener(v -> {
            sounds.playClick();
            currentTab[0] = 0;
            etSearch.setText("");
            updateTabDisplay.run();
        });

        btnTabStrategy.setOnClickListener(v -> {
            sounds.playClick();
            currentTab[0] = 1;
            etSearch.setText("");
            updateTabDisplay.run();
        });

        btnTabCampaign.setOnClickListener(v -> {
            sounds.playClick();
            currentTab[0] = 2;
            etSearch.setText("");
            updateTabDisplay.run();
        });

        btnClearSearch.setOnClickListener(v -> {
            sounds.playClick();
            etSearch.setText("");
        });

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String q = (s != null) ? s.toString().trim() : "";
                if (q.length() < 2) {
                    searchScroll.setVisibility(View.GONE);
                    contentScroll.setVisibility(View.VISIBLE);
                    return;
                }
                searchScroll.setVisibility(View.VISIBLE);
                contentScroll.setVisibility(View.GONE);
                searchList.removeAllViews();

                List<PgManualManager.SearchResult> res = PgManualManager.searchDocumentation(MainActivity.this, q);
                if (res.isEmpty()) {
                    TextView tvNone = new TextView(MainActivity.this);
                    tvNone.setText("No occurrences found for \"" + q + "\".\nTry searching for terms like: Overrun, Rugged, Pioniere, Bridge, Air, Supply, Replacements, Weather, Sealion, Moscow...");
                    tvNone.setTextColor(0xFF90A4AE);
                    tvNone.setTextSize(13.5f);
                    tvNone.setPadding(20, 30, 20, 30);
                    tvNone.setGravity(Gravity.CENTER);
                    searchList.addView(tvNone);
                    return;
                }

                TextView tvHeader = new TextView(MainActivity.this);
                tvHeader.setText(String.format(Locale.ROOT, "Found %d matching sections for \"%s\":", res.size(), q));
                tvHeader.setTextColor(0xFFFFD54F);
                tvHeader.setTextSize(13f);
                tvHeader.setTypeface(Typeface.DEFAULT_BOLD);
                tvHeader.setPadding(10, 8, 10, 8);
                searchList.addView(tvHeader);

                for (PgManualManager.SearchResult r : res) {
                    LinearLayout card = new LinearLayout(MainActivity.this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(16, 12, 16, 12);
                    GradientDrawable cbg = new GradientDrawable();
                    cbg.setColor(0xFF1E2620);
                    cbg.setCornerRadius(8f);
                    cbg.setStroke(1, 0xFF3E4F42);
                    card.setBackground(cbg);

                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    clp.setMargins(0, 4, 0, 4);

                    LinearLayout tagRow = new LinearLayout(MainActivity.this);
                    tagRow.setOrientation(LinearLayout.HORIZONTAL);
                    tagRow.setGravity(Gravity.CENTER_VERTICAL);

                    TextView badge = new TextView(MainActivity.this);
                    badge.setText(r.tabName.toUpperCase(Locale.ROOT));
                    badge.setTextSize(10f);
                    badge.setTypeface(Typeface.DEFAULT_BOLD);
                    badge.setPadding(8, 3, 8, 3);
                    GradientDrawable bgb = new GradientDrawable();
                    int badgeColor = (r.tabIndex == 0) ? 0xFF2E7D32 : (r.tabIndex == 1 ? 0xFFD84315 : 0xFF00838F);
                    bgb.setColor(badgeColor);
                    bgb.setCornerRadius(4f);
                    badge.setBackground(bgb);
                    badge.setTextColor(0xFFFFFFFF);
                    tagRow.addView(badge);

                    TextView tvSec = new TextView(MainActivity.this);
                    tvSec.setText("  " + r.sectionHeader);
                    tvSec.setTextColor(0xFFFFD54F);
                    tvSec.setTextSize(12.5f);
                    tvSec.setTypeface(Typeface.DEFAULT_BOLD);
                    tagRow.addView(tvSec);
                    card.addView(tagRow);

                    TextView tvSnip = new TextView(MainActivity.this);
                    tvSnip.setText(r.snippet);
                    tvSnip.setTextColor(0xFFCFD8DC);
                    tvSnip.setTextSize(12f);
                    tvSnip.setPadding(0, 6, 0, 0);
                    card.addView(tvSnip);

                    card.setOnClickListener(v -> {
                        sounds.playClick();
                        etSearch.setText("");
                        currentTab[0] = r.tabIndex;
                        updateTabDisplay.run();
                        jumpTarget[0] = r.sectionHeader;
                        tvContent.post(scrollToTarget[0]);
                    });

                    searchList.addView(card, clp);
                }
            }
        });

        updateTabDisplay.run();
        dialog.setContentView(root);
        dialog.show();
    }

    private void showInGameMenuDialog() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (!engine.isDeploymentPhase) {
            labels.add("Scenario Unit Roster (Move & Attack Status)");
            actions.add(this::showUnitRosterDialog);
        }

        if (lastTurnLossSummary != null) {
            labels.add("📊 Turn Combat & Unit Loss Summary");
            actions.add(() -> showTurnLossSummaryDialog(lastTurnLossSummary));
        }

        if (mapView.selectedUnit != null) {
            final PgDatabase.ActiveUnit selUnit = mapView.selectedUnit;
            PgDatabase.UnitDef selDef = db.equipment.get(selUnit.unitDefId);
            String uName = selDef != null ? selDef.name : "Selected Unit";
            if (selUnit.isAxis) {
                boolean canUpg = engine.canUpgradeUnit(selUnit);
                boolean canDisb = engine.canDisbandUnit(selUnit);
                String actionTag = (canUpg && canDisb) ? " (Upgrade / Disband)"
                        : (canUpg ? " (Upgrade)" : (canDisb ? " (Disband)" : ""));
                labels.add("Unit Dossier — " + uName + actionTag);
            } else {
                labels.add("Allied Intelligence Report — " + uName);
            }
            actions.add(() -> showActiveUnitActionsModal(selUnit));
        }

        labels.add("📖 Official Instruction Manual & Tactical Rules");
        actions.add(() -> showFieldManualDialog(0));

        labels.add("🎖️ Official SSI Strategy Guide (Tactics & Combined Arms)");
        actions.add(() -> showFieldManualDialog(1));

        labels.add("🗺️ Campaign Operations Guide & Scenario Notes");
        actions.add(() -> showFieldManualDialog(2));

        labels.add("Game Options & Preferences (Supply, Weather, Fog of War...)");
        actions.add(this::showPreferencesDialog);

        labels.add("Save Game (Local Slot / Google Drive)");
        actions.add(() -> showSaveLoadDialog(true));

        labels.add("Load Saved Game (Local Slot / Google Drive)");
        actions.add(() -> showSaveLoadDialog(false));

        labels.add("Supply (Fuel & Ammo): " + (engine.supplyEnabled ? "ON (Standard Rules)" : "OFF (Unlimited Fuel & Ammo)"));
        actions.add(() -> {
            engine.supplyEnabled = !engine.supplyEnabled;
            savePreferences();
            if (mapView.selectedUnit != null) mapView.selectUnit(mapView.selectedUnit);
            refreshHud();
            Toast.makeText(this, "Supply (Fuel & Ammo): " + (engine.supplyEnabled ? "ON" : "OFF (Unlimited)"), Toast.LENGTH_SHORT).show();
        });

        labels.add("Weather Effects: " + (engine.weatherEnabled ? "ON (Dynamic Weather)" : "OFF (Always Clear/Dry)"));
        actions.add(() -> {
            engine.weatherEnabled = !engine.weatherEnabled;
            if (!engine.weatherEnabled) {
                engine.weather = 0;
                engine.groundCondition = 0;
            }
            savePreferences();
            refreshHud();
            mapView.invalidate();
            Toast.makeText(this, "Weather Effects: " + (engine.weatherEnabled ? "ON" : "OFF (Always Clear/Dry)"), Toast.LENGTH_SHORT).show();
        });

        labels.add("Fog of War: " + (engine.fogOfWarEnabled ? "ON" : "OFF"));
        actions.add(() -> {
            engine.fogOfWarEnabled = !engine.fogOfWarEnabled;
            engine.recalculateFogOfWar();
            savePreferences();
            mapView.invalidate();
        });

        labels.add("Equipment Encyclopedia (425 Units)");
        actions.add(this::showEncyclopediaDialog);

        labels.add("Return to Title Screen");
        actions.add(this::showTitleScreen);

        new AlertDialog.Builder(this)
                .setTitle("Command Menu")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton("Resume Battle", null)
                .show();
    }

    private void showPreferencesDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(42, 24, 42, 24);

        CheckBox cbSupply = new CheckBox(this);
        cbSupply.setText("Supply Rules ON (Units consume Fuel & Ammo; turn OFF for Unlimited Fuel & Ammo)");
        cbSupply.setChecked(engine.supplyEnabled);
        cbSupply.setTextSize(14f);
        container.addView(cbSupply);

        CheckBox cbWeather = new CheckBox(this);
        cbWeather.setText("Weather Rules ON (Dynamic Overcast/Storm/Mud; turn OFF for Always Clear & Dry)");
        cbWeather.setChecked(engine.weatherEnabled);
        cbWeather.setTextSize(14f);
        container.addView(cbWeather);

        CheckBox cbFow = new CheckBox(this);
        cbFow.setText("Fog of War ON (Enemy units hidden outside spotting range)");
        cbFow.setChecked(engine.fogOfWarEnabled);
        cbFow.setTextSize(14f);
        container.addView(cbFow);

        CheckBox cbGrid = new CheckBox(this);
        cbGrid.setText("Show Hex Grid Overlay on Tactical Map");
        cbGrid.setChecked(engine.showHexGrid);
        cbGrid.setTextSize(14f);
        container.addView(cbGrid);

        CheckBox cbStrength = new CheckBox(this);
        cbStrength.setText("Show Unit Strength Badges & Experience Stars");
        cbStrength.setChecked(engine.showUnitStrength);
        cbStrength.setTextSize(14f);
        container.addView(cbStrength);

        CheckBox cbSound = new CheckBox(this);
        cbSound.setText("Sound Effects & Voice Briefings ON");
        cbSound.setChecked(sounds.isSoundEnabled());
        cbSound.setTextSize(14f);
        container.addView(cbSound);

        CheckBox cbMusic = new CheckBox(this);
        cbMusic.setText("Background Music ON");
        cbMusic.setChecked(sounds.isMusicEnabled());
        cbMusic.setTextSize(14f);
        container.addView(cbMusic);

        TextView tvHandicap = new TextView(this);
        tvHandicap.setText("\nPrestige Handicap / Difficulty Balance:");
        tvHandicap.setTypeface(Typeface.DEFAULT_BOLD);
        tvHandicap.setTextSize(14f);
        container.addView(tvHandicap);

        final int[] handicapValues = {50, 75, 100, 125, 150, 200};
        String[] handicapLabels = {
            "50% Prestige (Hardcore Axis Handicap)",
            "75% Prestige (Challenging)",
            "100% Prestige (Historical Standard)",
            "125% Prestige (Favorable)",
            "150% Prestige (Generous)",
            "200% Prestige (Double Prestige)"
        };
        Spinner spHandicap = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, handicapLabels);
        spHandicap.setAdapter(adapter);
        for (int i = 0; i < handicapValues.length; i++) {
            if (handicapValues[i] == engine.prestigeModifier) {
                spHandicap.setSelection(i);
                break;
            }
        }
        container.addView(spHandicap);
        scroll.addView(container);

        new AlertDialog.Builder(this)
                .setTitle("Panzer General — Game Options & Preferences")
                .setView(scroll)
                .setPositiveButton("Apply & Save", (d, w) -> {
                    engine.supplyEnabled = cbSupply.isChecked();
                    engine.weatherEnabled = cbWeather.isChecked();
                    if (!engine.weatherEnabled) {
                        engine.weather = 0;
                        engine.groundCondition = 0;
                    }
                    engine.fogOfWarEnabled = cbFow.isChecked();
                    engine.showHexGrid = cbGrid.isChecked();
                    engine.showUnitStrength = cbStrength.isChecked();
                    engine.prestigeModifier = handicapValues[spHandicap.getSelectedItemPosition()];
                    sounds.setSoundEnabled(cbSound.isChecked());
                    sounds.setMusicEnabled(cbMusic.isChecked());
                    savePreferences();

                    if (engine.scenario != null) {
                        engine.recalculateFogOfWar();
                        if (mapView.selectedUnit != null) {
                            mapView.selectUnit(mapView.selectedUnit);
                        }
                        refreshHud();
                        mapView.invalidate();
                    }
                    Toast.makeText(this, "Preferences saved (Supply: " + (engine.supplyEnabled ? "ON" : "OFF")
                            + ", Weather: " + (engine.weatherEnabled ? "ON" : "OFF")
                            + ", FOW: " + (engine.fogOfWarEnabled ? "ON" : "OFF") + ")", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSaveLoadDialog(boolean isSave) {
        String[] options = new String[7];
        for (int i = 0; i < 5; i++) {
            options[i] = engine.getSaveSummary(this, i + 1);
        }
        if (isSave) {
            options[5] = "\u2601 Save Active Battle to Google Drive...";
            options[6] = "\u2601 Export a Local Save Slot to Google Drive...";
        } else {
            options[5] = "\u2601 Load Game from Google Drive / Cloud File...";
            options[6] = "\u2601 Import Google Drive File into a Local Slot...";
        }

        new AlertDialog.Builder(this)
                .setTitle(isSave ? "Save Tactical Game" : "Load Tactical Game")
                .setItems(options, (d, which) -> {
                    if (which < 5) {
                        int slot = which + 1;
                        if (isSave) {
                            try {
                                engine.saveGame(this, slot);
                                sounds.playSelect();
                                Toast.makeText(this, "Saved to Slot " + slot, Toast.LENGTH_SHORT).show();
                            } catch (IOException e) {
                                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        } else {
                            if (engine.loadGame(this, slot)) {
                                try {
                                    engine.saveGameCache(this);
                                } catch (Exception ignored) {}
                                if (titleOverlay != null) titleOverlay.setVisibility(View.GONE);
                                mapView.purchasePlacementMode = false;
                                mapView.strategicMapOverlay = false;
                                centerCameraOnActiveUnits();
                                refreshHud();
                                mapView.invalidate();
                                sounds.playSelect();
                                Toast.makeText(this, "Loaded Slot " + slot, Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "Slot " + slot + " is empty.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    } else if (which == 5) {
                        if (isSave) {
                            if (engine.scenario == null) {
                                Toast.makeText(this, "No active scenario in progress to save.", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            launchGoogleDriveSavePicker(0);
                        } else {
                            launchGoogleDriveLoadPicker(0);
                        }
                    } else if (which == 6) {
                        if (isSave) {
                            showExportSlotDialog();
                        } else {
                            showImportSlotDialog();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchGoogleDriveSavePicker(int slotToExport) {
        pendingExportSlot = slotToExport;
        String sName = (engine.scenario != null && engine.scenario.meta != null)
                ? engine.scenario.meta.shortTitle.replaceAll("[^a-zA-Z0-9_-]", "")
                : "Battle";
        String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        String defaultFileName;
        if (slotToExport > 0) {
            defaultFileName = String.format(Locale.US, "PG_Slot%d_Export_%s.pgsave", slotToExport, ts);
        } else {
            defaultFileName = String.format(Locale.US, "PG_%s_Turn%d_%s.pgsave", sName, engine.turn, ts);
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, defaultFileName);
        try {
            startActivityForResult(intent, REQUEST_SAVE_TO_DRIVE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Google Drive / Document Manager not available on this device.", Toast.LENGTH_LONG).show();
        }
    }

    private void showExportSlotDialog() {
        String[] slots = new String[5];
        for (int i = 0; i < 5; i++) {
            slots[i] = engine.getSaveSummary(this, i + 1);
        }
        new AlertDialog.Builder(this)
                .setTitle("Select Local Slot to Export to Google Drive")
                .setItems(slots, (d, which) -> {
                    int slot = which + 1;
                    File f = new File(getFilesDir(), "pg_save_" + slot + ".dat");
                    if (!f.exists()) {
                        Toast.makeText(this, "Slot " + slot + " has no saved game.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    launchGoogleDriveSavePicker(slot);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchGoogleDriveLoadPicker(int targetSlotToImport) {
        pendingImportSlot = targetSlotToImport;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/x-binary", "*/*"});
        try {
            startActivityForResult(intent, targetSlotToImport > 0 ? REQUEST_IMPORT_TO_SLOT : REQUEST_LOAD_FROM_DRIVE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Google Drive / Document Manager not available on this device.", Toast.LENGTH_LONG).show();
        }
    }

    private void showImportSlotDialog() {
        String[] slots = new String[5];
        for (int i = 0; i < 5; i++) {
            File f = new File(getFilesDir(), "pg_save_" + (i + 1) + ".dat");
            slots[i] = "Slot " + (i + 1) + ": " + (f.exists() ? "(Will Overwrite Existing Save)" : "(Empty)");
        }
        new AlertDialog.Builder(this)
                .setTitle("Select Target Slot for Google Drive File")
                .setItems(slots, (d, which) -> {
                    int slot = which + 1;
                    launchGoogleDriveLoadPicker(slot);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExportSlot = 0;
            pendingImportSlot = 0;
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_SAVE_TO_DRIVE) {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("Unable to open output stream.");
                if (pendingExportSlot > 0) {
                    if (engine.exportSlotToStream(this, pendingExportSlot, os)) {
                        sounds.playSelect();
                        Toast.makeText(this, "\u2605 Slot " + pendingExportSlot + " successfully saved to Google Drive! \u2605", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Failed to export Slot " + pendingExportSlot, Toast.LENGTH_LONG).show();
                    }
                } else {
                    engine.saveGameToStream(os);
                    sounds.playSelect();
                    Toast.makeText(this, "\u2605 Battle successfully saved to Google Drive! \u2605", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Failed saving to Google Drive: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                pendingExportSlot = 0;
            }
        } else if (requestCode == REQUEST_LOAD_FROM_DRIVE) {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IOException("Unable to open input stream.");
                if (engine.loadGameFromStream(is)) {
                    try {
                        engine.saveGameCache(this);
                    } catch (Exception ignored) {}
                    if (titleOverlay != null) titleOverlay.setVisibility(View.GONE);
                    mapView.purchasePlacementMode = false;
                    mapView.strategicMapOverlay = false;
                    centerCameraOnActiveUnits();
                    refreshHud();
                    mapView.invalidate();
                    sounds.playSelect();
                    String name = (engine.scenario != null && engine.scenario.meta != null) ? engine.scenario.meta.fullTitle : "";
                    Toast.makeText(this, "\u2605 Loaded from Google Drive: " + name + " (Turn " + engine.turn + "/" + engine.maxTurns + ") \u2605", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Selected file is not a valid Panzer General save game.", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Failed loading from Google Drive: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_IMPORT_TO_SLOT) {
            final int slot = pendingImportSlot;
            pendingImportSlot = 0;
            if (slot >= 1 && slot <= 5) {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) throw new IOException("Unable to open input stream.");
                    if (engine.importStreamToSlot(this, is, slot)) {
                        sounds.playSelect();
                        String summary = engine.getSaveSummary(this, slot);
                        new AlertDialog.Builder(this)
                                .setTitle("Google Drive Import Complete")
                                .setMessage("Successfully imported save file into Slot " + slot + "!\n\n" + summary + "\n\nWould you like to load and play this game now?")
                                .setPositiveButton("Load & Play Now", (d, w) -> {
                                    if (engine.loadGame(this, slot)) {
                                        try {
                                            engine.saveGameCache(this);
                                        } catch (Exception ignored) {}
                                        if (titleOverlay != null) titleOverlay.setVisibility(View.GONE);
                                        mapView.purchasePlacementMode = false;
                                        mapView.strategicMapOverlay = false;
                                        centerCameraOnActiveUnits();
                                        refreshHud();
                                        mapView.invalidate();
                                        sounds.playSelect();
                                        Toast.makeText(this, "Loaded Slot " + slot, Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("Keep in Slot", null)
                                .show();
                    } else {
                        Toast.makeText(this, "Failed to import save file into Slot " + slot, Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Failed importing file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void showEncyclopediaDialog() {
        List<PgDatabase.UnitDef> valid = new ArrayList<>();
        for (PgDatabase.UnitDef u : db.equipment) {
            if (u.id > 0 && !u.name.isEmpty() && !u.name.equalsIgnoreCase("RESERVED")) {
                valid.add(u);
            }
        }
        String[] names = new String[valid.size()];
        for (int i = 0; i < valid.size(); i++) {
            PgDatabase.UnitDef u = valid.get(i);
            String cls = (u.unitClass >= 0 && u.unitClass < PgDatabase.CLASS_NAMES.length)
                    ? PgDatabase.CLASS_NAMES[u.unitClass] : "Unit";
            names[i] = String.format(Locale.ROOT, "#%03d %s (%s • 19%02d–19%02d)",
                    u.id, u.name, cls, u.startYear, u.endYear);
        }
        new AlertDialog.Builder(this)
                .setTitle("Panzer General Equipment Database (" + valid.size() + " Units)")
                .setItems(names, (d, which) -> showUnitDetailModal(valid.get(which)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showUnitDetailModal(PgDatabase.UnitDef u) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(36, 24, 36, 24);
        box.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iv = new ImageView(this);
        Bitmap spr = assets.getSprite("TACICONS.SHP", u.iconId);
        if (spr != null) {
            iv.setImageBitmap(Bitmap.createScaledBitmap(spr, spr.getWidth() * 3, spr.getHeight() * 3, false));
        }
        box.addView(iv, new LinearLayout.LayoutParams(200, 160));

        TextView tv = new TextView(this);
        tv.setPadding(24, 0, 0, 0);
        tv.setTextSize(14f);
        String cls = (u.unitClass >= 0 && u.unitClass < PgDatabase.CLASS_NAMES.length)
                ? PgDatabase.CLASS_NAMES[u.unitClass] : "Unit";
        tv.setText(String.format(Locale.ROOT,
                "Class: %s\nService Years: 19%02d – 19%02d\nPrestige Cost: %d\n\n" +
                "Soft Attack: %d   |   Hard Attack: %d\n" +
                "Air Attack: %d    |   Naval Attack: %d\n" +
                "Ground Def: %d    |   Air Defense: %d\n" +
                "Close Def: %d     |   Initiative: %d\n" +
                "Range: %d hexes   |   Spotting: %d\n" +
                "Movement: %d      |   Max Ammo: %d   |   Max Fuel: %d",
                cls, u.startYear, u.endYear, u.cost,
                u.softAttack, u.hardAttack, u.airAttack, u.navalAttack,
                u.groundDefense, u.airDefense, u.closeDefense, u.initiative,
                u.range, u.spotting, u.movement, u.maxAmmo, u.maxFuel));
        box.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle(u.name + " (Equipment #" + u.id + ")")
                .setView(box)
                .setPositiveButton("Back to List", (d, w) -> showEncyclopediaDialog())
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mapView != null && !mapView.strategicMapOverlay) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_PLUS:
                case KeyEvent.KEYCODE_EQUALS:
                case KeyEvent.KEYCODE_NUMPAD_ADD:
                case KeyEvent.KEYCODE_PAGE_UP:
                    sounds.playClick();
                    mapView.zoomIn();
                    return true;
                case KeyEvent.KEYCODE_MINUS:
                case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    sounds.playClick();
                    mapView.zoomOut();
                    return true;
                case KeyEvent.KEYCODE_0:
                case KeyEvent.KEYCODE_NUMPAD_0:
                    sounds.playClick();
                    mapView.resetZoom();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (titleOverlay != null && titleOverlay.getVisibility() != View.VISIBLE) {
            new AlertDialog.Builder(this)
                    .setTitle("Return to Main Menu?")
                    .setMessage("Your tactical battle progress is automatically cached. You can resume anytime from the Main Menu.")
                    .setPositiveButton("Main Menu", (d, w) -> {
                        sounds.playClick();
                        showTitleScreen();
                    })
                    .setNegativeButton("Continue Playing", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sounds.stopMusic();
        sounds.stopVoice();
        if (engine.scenario != null && engine.scenarioOutcome == 0) {
            try {
                if (mapView != null) {
                    engine.lastSelectedCol = mapView.selectedCol;
                    engine.lastSelectedRow = mapView.selectedRow;
                }
                engine.saveGameCache(this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (engine.scenario != null && engine.scenarioOutcome == 0) {
            try {
                if (mapView != null) {
                    engine.lastSelectedCol = mapView.selectedCol;
                    engine.lastSelectedRow = mapView.selectedRow;
                }
                engine.saveGameCache(this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (engine.scenario != null && engine.scenarioOutcome == 0) {
            try {
                if (mapView != null) {
                    engine.lastSelectedCol = mapView.selectedCol;
                    engine.lastSelectedRow = mapView.selectedRow;
                }
                engine.saveGameCache(this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateResumeButton();
        if (sounds.isMusicEnabled() && titleOverlay != null && titleOverlay.getVisibility() != View.VISIBLE && engine.scenario != null) {
            sounds.startMusic(engine.currentScenarioNumber % 6);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sounds.release();
    }
}
