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
import java.io.IOException;
import java.util.*;

public class MainActivity extends Activity implements PgMapView.MapInteractionListener {
    private PgAssetManager assets;
    private PgDatabase db;
    private PgGameEngine engine;
    private PgSoundManager sounds;

    private FrameLayout rootContainer;
    private View titleOverlay;
    private LinearLayout hudContainer;
    private PgMapView mapView;

    // Top bar labels
    private TextView tvScenarioTitle;
    private TextView tvTurnWeather;
    private TextView tvPrestige;
    private TextView tvObjectivesCount;

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

        Button btnLoad = createStyledButton("LOAD SAVED GAME", 0xFF373B3E, 0xFFFFFFFF);
        btnLoad.setOnClickListener(v -> {
            sounds.playClick();
            showSaveLoadDialog(false);
        });
        btnGrid.addView(btnLoad, createMenuBtnParams());

        Button btnEncyclopedia = createStyledButton("INSTRUCTION MANUAL & EQUIPMENT ENCYCLOPEDIA", 0xFF3E352B, 0xFFFFE082);
        btnEncyclopedia.setOnClickListener(v -> {
            sounds.playClick();
            new AlertDialog.Builder(this)
                    .setTitle("Reference Library")
                    .setItems(new String[]{
                        "Panzer General Instruction Manual & Tactical Rules",
                        "Equipment Encyclopedia (All 425 Units)"
                    }, (d, which) -> {
                        if (which == 0) showFieldManualDialog();
                        else showEncyclopediaDialog();
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
    }

    private LinearLayout.LayoutParams createMenuBtnParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(720, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 5, 0, 5);
        return lp;
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

        // Tactical Action Buttons
        btnUndo = createCompactButton("UNDO", 0xFF546E7A);
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
        btnSupply.setOnClickListener(v -> {
            if (engine.isDeploymentPhase && mapView.selectedUnit != null && mapView.selectedUnit.isAxis && mapView.selectedUnit.isCore) {
                PgDatabase.ActiveUnit u = mapView.selectedUnit;
                if (engine.undeployCoreUnit(u)) {
                    sounds.playSelect();
                    mapView.selectedUnit = null;
                    refreshHud();
                    mapView.invalidate();
                    Toast.makeText(this, "Returned " + db.equipment.get(u.unitDefId).name + " to Reserve Pool.", Toast.LENGTH_SHORT).show();
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
        btnReinforce.setOnClickListener(v -> {
            if (mapView.selectedUnit != null && engine.reinforceUnit(mapView.selectedUnit, false)) {
                sounds.playSelect();
                mapView.selectUnit(mapView.selectedUnit);
                refreshHud();
            }
        });
        bottomBar.addView(btnReinforce);

        btnElite = createCompactButton("ELITE+", 0xFF5D4037);
        btnElite.setOnClickListener(v -> {
            if (mapView.selectedUnit != null && engine.reinforceUnit(mapView.selectedUnit, true)) {
                sounds.playSelect();
                mapView.selectUnit(mapView.selectedUnit);
                refreshHud();
            }
        });
        bottomBar.addView(btnElite);

        btnUpgrade = createCompactButton("UPGRADE", 0xFF4E342E);
        btnUpgrade.setOnClickListener(v -> {
            if (mapView.selectedUnit != null && engine.canUpgradeUnit(mapView.selectedUnit)) {
                sounds.playClick();
                showUpgradeUnitDialog(mapView.selectedUnit);
            }
        });
        bottomBar.addView(btnUpgrade);

        btnMount = createCompactButton("MOUNT", 0xFF455A64);
        btnMount.setOnClickListener(v -> {
            PgDatabase.ActiveUnit u = mapView.selectedUnit;
            if (u != null && engine.canUseTransportAction(u)) {
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
            mapView.airModePreferred = !mapView.airModePreferred;
            btnAirToggle.setText(mapView.airModePreferred ? "AIR TOP" : "GND TOP");
            sounds.playClick();
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
                confirmEndTurn();
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
                engine.startCampaign(which);
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
            try {
                engine.startSingleScenario(which + 1);
                launchTacticalScreen();
            } catch (IOException e) {
                Toast.makeText(this, "Error loading scenario: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void launchTacticalScreen() {
        if (titleOverlay != null) {
            titleOverlay.setVisibility(View.GONE);
        }
        mapView.purchasePlacementMode = false;
        mapView.strategicMapOverlay = false;
        mapView.clearSelection();

        // Center camera on first Axis unit
        boolean centered = false;
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u.isAxis && u.col >= 0 && u.row >= 0) {
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
                m.number, m.fullTitle, engine.isCampaignMode ? "Campaign" : "Scenario"));

        if (engine.isDeploymentPhase) {
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

        tvPrestige.setText(String.format(Locale.ROOT, "★ %d Prestige", engine.axisPrestige));

        int heldObj = 0;
        for (int[] obj : engine.scenario.objectives) {
            if (engine.db.isAxisNation(engine.scenario.grid[obj[0]][obj[1]].ownerNation, engine.scenario)) {
                heldObj++;
            }
        }
        tvObjectivesCount.setText(String.format(Locale.ROOT, "Objectives: %d/%d",
                heldObj, engine.scenario.objectives.size()));

        updateUnitInspector(mapView.selectedCol, mapView.selectedRow, mapView.selectedUnit);
    }

    private void updateUnitInspector(int col, int row, PgDatabase.ActiveUnit u) {
        btnUndo.setEnabled(engine.canUndoLastMove());
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
                if (u.isAxis && u.isCore) {
                    btnSupply.setText("UNDEPLOY");
                    btnSupply.setEnabled(true);
                    btnSupply.setBackgroundColor(0xFFD32F2F);
                } else {
                    btnSupply.setText("SUPPLY");
                    btnSupply.setEnabled(false);
                    btnSupply.setBackgroundColor(0xFF2E5A36);
                }
                btnReinforce.setEnabled(engine.canReinforceUnit(u, false));
                btnElite.setEnabled(engine.canReinforceUnit(u, true));
                btnElite.setText(u.strength >= 10 && u.getStars() > 0 ? "OVRST+1" : "ELITE+");
                btnUpgrade.setEnabled(engine.canUpgradeUnit(u));
                btnMount.setEnabled(false);
            } else {
                btnSupply.setText("SUPPLY");
                btnSupply.setBackgroundColor(0xFF2E5A36);
                boolean canAct = u.isAxis && engine.isPlayerTurn && !u.moved && u.movePointsUsed == 0 && !u.attacked;
                btnSupply.setEnabled(canAct && engine.supplyEnabled);
                btnReinforce.setEnabled(engine.canReinforceUnit(u, false));
                btnElite.setEnabled(engine.canReinforceUnit(u, true));
                btnElite.setText(u.strength >= 10 && u.getStars() > 0 ? "OVRST+1" : "ELITE+");
                btnUpgrade.setEnabled(engine.canUpgradeUnit(u));
                btnMount.setText(engine.getTransportActionLabel(u));
                btnMount.setEnabled(engine.canUseTransportAction(u));
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
            btnSupply.setText("SUPPLY");
            btnSupply.setBackgroundColor(0xFF2E5A36);
            btnSupply.setEnabled(false);
            btnReinforce.setEnabled(false);
            btnElite.setEnabled(false);
            btnUpgrade.setEnabled(false);
            btnMount.setEnabled(false);
        } else {
            ivSelectedUnitIcon.setImageBitmap(null);
            tvSelectedTitle.setText("No Hex Selected");
            tvSelectedStats.setText("Tap any unit to view combat stats, move, or attack.");
            btnSupply.setText("SUPPLY");
            btnSupply.setBackgroundColor(0xFF2E5A36);
            btnSupply.setEnabled(false);
            btnReinforce.setEnabled(false);
            btnElite.setEnabled(false);
            btnUpgrade.setEnabled(false);
            btnMount.setEnabled(false);
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
                Toast.makeText(this, "Planes can only be placed on or next to controlled airports with no enemy adjacent.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Ground units can only be placed on or next to controlled cities with no enemy adjacent.", Toast.LENGTH_SHORT).show();
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
            mapView.pendingDeployCoreUnit = null;
            mapView.selectUnit(unit);
            refreshHud();
            mapView.invalidate();
            boolean inSea = unit.inSeaTransport;
            boolean isAir = db.equipment.get(unit.unitDefId).isAir();
            boolean isWater = engine.scenario != null && engine.scenario.grid[col][row].isWater();
            String tag = inSea ? " [Naval Transport]" : (isAir && isWater ? " [Airborne Over Sea]" : "");
            String msg = "Deployed " + db.equipment.get(unit.unitDefId).name + " at (" + col + "," + row + ")" + tag;
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Cannot deploy core unit on that hex.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDeployHexTapped(int col, int row) {
        if (!engine.isDeploymentPhase) return;
        showCoreDeploymentDialogForHex(col, row);
    }

    @Override
    public void onUnitLongPressed(PgDatabase.ActiveUnit unit) {
        if (unit != null) {
            sounds.playSelect();
            showUnitDossierDialog(unit);
        }
    }

    private void confirmEndTurn() {
        int unmovedCount = 0;
        for (PgDatabase.ActiveUnit u : engine.units) {
            if (engine.hasAvailableAction(u)) {
                unmovedCount++;
            }
        }

        if (unmovedCount > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Unmoved Units Warning")
                    .setMessage(String.format(Locale.ROOT,
                            "You have %d unit%s with available actions this turn.\n\nDo you want to end your turn now or review remaining units?",
                            unmovedCount, unmovedCount == 1 ? "" : "s"))
                    .setPositiveButton("End Turn", (d, w) -> performEndTurn())
                    .setNeutralButton("Next Unit", (d, w) -> selectNextUnmovedUnit())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            performEndTurn();
        }
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
        if (engine.scenario == null) return;
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141915);
        root.setPadding(20, 14, 20, 14);

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
        Bitmap flagBmp = assets.getTrimmedSprite("FLAGS.SHP", 7);
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
        tvSubHeader.setText(String.format(Locale.ROOT,
                "Reserve Core Units: %d remaining   •   Deploy onto marked deployment hexes",
                engine.unplacedCoreUnits.size()));
        tvSubHeader.setTextColor(0xFFB0BEC5);
        tvSubHeader.setTextSize(11.5f);
        titleStack.addView(tvSubHeader);
        headerRow.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Prestige Pill
        TextView tvPrestigePill = new TextView(this);
        tvPrestigePill.setText(String.format(Locale.ROOT, "★ %d PRESTIGE", engine.axisPrestige));
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
        });
        headerRow.addView(btnClose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 60));
        root.addView(headerRow);

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
            ScrollView scroll = new ScrollView(this);
            LinearLayout listLayout = new LinearLayout(this);
            listLayout.setOrientation(LinearLayout.VERTICAL);
            listLayout.setPadding(0, 10, 0, 20);

            for (PgDatabase.ActiveUnit u : engine.unplacedCoreUnits) {
                PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
                if (def == null) continue;

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(14, 10, 14, 10);
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(0xFF1E2520);
                cardBg.setCornerRadius(8f);
                cardBg.setStroke(1, 0xFF3E4F42);
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
                        showCoreDeploymentDialog();
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
                        showCoreDeploymentDialog();
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

                Button btnDeploy = createCompactButton("DEPLOY ON MAP", 0xFF00695C);
                btnDeploy.setOnClickListener(v -> {
                    sounds.playClick();
                    dialog.dismiss();
                    mapView.pendingDeployCoreUnit = u;
                    mapView.invalidate();
                    Toast.makeText(MainActivity.this,
                            "Tap any highlighted deployment hex (cyan) to place " + def.name,
                            Toast.LENGTH_LONG).show();
                });
                LinearLayout.LayoutParams depLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 56);
                depLp.setMargins(6, 0, 0, 0);
                btnStack.addView(btnDeploy, depLp);

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

    public void showCoreDeploymentDialogForHex(int targetCol, int targetRow) {
        if (engine.scenario == null || !engine.isDeploymentPhase) return;
        if (targetCol < 0 || targetCol >= engine.scenario.cols || targetRow < 0 || targetRow >= engine.scenario.rows) return;
        PgDatabase.HexCell cell = engine.scenario.grid[targetCol][targetRow];

        List<PgDatabase.ActiveUnit> eligible = new ArrayList<>();
        for (PgDatabase.ActiveUnit u : engine.unplacedCoreUnits) {
            List<int[]> validHexes = engine.getValidCoreDeploymentHexes(u);
            for (int[] h : validHexes) {
                if (h[0] == targetCol && h[1] == targetRow) {
                    eligible.add(u);
                    break;
                }
            }
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(String.format(Locale.ROOT, "Deploy Core Unit at (%d, %d) - %s [%s]",
                targetCol, targetRow, cell.name, cell.getTerrainName()));

        if (eligible.isEmpty()) {
            if (engine.unplacedCoreUnits.isEmpty()) {
                b.setMessage("No reserve core units remaining. All core units have been placed on the battlefield.");
            } else if (cell.terrainType == 6) {
                b.setMessage("No reserve air units available to deploy onto this airfield.");
            } else {
                b.setMessage("No reserve ground units can deploy onto this position.");
            }
            b.setNegativeButton("Close", null);
            b.setPositiveButton("View Reserves", (d, w) -> showCoreDeploymentDialog());
            b.show();
            return;
        }

        String[] items = new String[eligible.size()];
        for (int i = 0; i < eligible.size(); i++) {
            PgDatabase.ActiveUnit u = eligible.get(i);
            PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
            String className = (def.unitClass >= 0 && def.unitClass < PgDatabase.CLASS_NAMES.length)
                    ? PgDatabase.CLASS_NAMES[def.unitClass] : "Unit";
            String stars = u.getStars() > 0 ? (" ★".repeat(u.getStars())) : "";
            items[i] = String.format(Locale.ROOT, "%s [%s%s] • Str: %d/%d (Exp: %d) • Mov: %d",
                    def.name, className, stars, u.strength, u.getMaxOverstrength(), u.experience, def.movement);
        }

        b.setItems(items, (d, which) -> {
            PgDatabase.ActiveUnit selected = eligible.get(which);
            onCoreUnitDeployRequested(selected, targetCol, targetRow);
        });
        b.setNegativeButton("Cancel", null);
        b.setNeutralButton("View All Reserves", (d, w) -> showCoreDeploymentDialog());
        b.show();
    }

    private void selectNextUnmovedUnit() {
        if (engine.units.isEmpty() || !engine.isPlayerTurn) return;
        int startIdx = 0;
        if (mapView.selectedUnit != null) {
            int idx = engine.units.indexOf(mapView.selectedUnit);
            if (idx >= 0) startIdx = (idx + 1) % engine.units.size();
        }
        for (int i = 0; i < engine.units.size(); i++) {
            PgDatabase.ActiveUnit u = engine.units.get((startIdx + i) % engine.units.size());
            if (engine.hasAvailableAction(u)) {
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

    private void performEndTurn() {
        mapView.purchasePlacementMode = false;
        List<PgGameEngine.CombatReport> aiReports = engine.endPlayerTurnAndRunAi();
        if (!aiReports.isEmpty()) {
            PgGameEngine.CombatReport last = aiReports.get(aiReports.size() - 1);
            sounds.playCombatSound(null);
            mapView.triggerExplosion(last.targetCol, last.targetRow, last);
        }
        mapView.clearSelection();
        refreshHud();
        checkScenarioCompletion();
        if (engine.scenarioOutcome == 0 && engine.isPlayerTurn && engine.scenario != null) {
            sounds.playSelect();
            mapView.showYourTurnBanner(engine.turn, engine.maxTurns, engine.getWeatherLabel());
            Toast.makeText(this, "★ YOUR TURN! Turn " + engine.turn + " of " + engine.maxTurns + " ★", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkScenarioCompletion() {
        if (engine.scenarioOutcome == 0) return;
        int outcome = engine.scenarioOutcome;
        engine.scenarioOutcome = 0; // prevent duplicate dialogs

        PgDatabase.ScenarioMeta meta = engine.scenario.meta;
        String title;
        String nextScenKey;
        int bonusPrestige;
        if (outcome == 1) {
            title = "DECISIVE VICTORY!";
            nextScenKey = meta.nextDecisive;
            bonusPrestige = Math.max(500, meta.prestigeDecisive);
        } else if (outcome == 2) {
            title = "MARGINAL VICTORY!";
            nextScenKey = meta.nextMarginal;
            bonusPrestige = Math.max(300, meta.prestigeMarginal);
        } else {
            title = "DEFEAT";
            nextScenKey = meta.nextLoss;
            bonusPrestige = meta.prestigeLoss;
        }

        int nextScenNum = db.findScenarioNumberByName(nextScenKey);
        StringBuilder msg = new StringBuilder();
        msg.append("Scenario: ").append(meta.fullTitle).append("\n");
        msg.append("Completed on Turn ").append(engine.turn).append(" of ").append(engine.maxTurns).append("\n");
        msg.append("Prestige Awarded: +").append(bonusPrestige).append("\n\n");
        if (engine.isCampaignMode && nextScenNum > 0) {
            PgDatabase.ScenarioMeta nextMeta = db.scenarios.get(nextScenNum - 1);
            msg.append("Next Campaign Assignment: #").append(nextScenNum).append(" ").append(nextMeta.fullTitle);
        } else if (outcome <= 2) {
            msg.append("Congratulations, Herr General! You have completed this campaign path.");
        } else {
            msg.append("High Command has relieved you of command.");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(title);
        b.setMessage(msg.toString());
        b.setCancelable(false);
        if (engine.isCampaignMode && nextScenNum > 0 && outcome <= 2) {
            b.setPositiveButton("Advance to " + nextScenKey, (d, w) -> {
                try {
                    engine.axisPrestige += bonusPrestige;
                    engine.loadScenario(nextScenNum, true);
                    launchTacticalScreen();
                } catch (IOException e) {
                    Toast.makeText(this, "Error loading next scenario", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.setNegativeButton("Main Menu", (d, w) -> {
            titleOverlay.setVisibility(View.VISIBLE);
        });
        b.show();
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
        sb.append("• Turns Allowed: ").append(engine.maxTurns)
          .append(" (Decisive Victory within ").append(engine.maxTurns - m.decisiveTurnsRemaining).append(" turns)\n");
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

    private List<PgDatabase.UnitDef> getAvailableAxisUnitsForPurchase(boolean allYears) {
        int curYear = (engine.scenario != null) ? (engine.scenario.meta.year % 100) : 39;
        List<PgDatabase.UnitDef> available = new ArrayList<>();
        for (PgDatabase.UnitDef u : db.equipment) {
            if (u.id == 0 || u.name.isEmpty() || u.name.equalsIgnoreCase("RESERVED")) continue;
            if (u.unitClass == PgDatabase.CLASS_LAND_TRANSPORT
                    || u.unitClass == PgDatabase.CLASS_AIR_TRANSPORT
                    || u.unitClass == PgDatabase.CLASS_SEA_TRANSPORT
                    || u.unitClass == PgDatabase.CLASS_FORT) continue;
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
                    available.add(u);
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

        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141915);
        root.setPadding(20, 14, 20, 14);

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
        Bitmap flagBmp = assets.getTrimmedSprite("FLAGS.SHP", 7);
        if (flagBmp != null) {
            ivFlag.setImageBitmap(Bitmap.createScaledBitmap(flagBmp, 48, 36, false));
        }
        LinearLayout.LayoutParams flagLp = new LinearLayout.LayoutParams(52, 40);
        flagLp.setMargins(0, 0, 14, 0);
        headerRow.addView(ivFlag, flagLp);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText("OBERKOMMANDO DER WEHRMACHT — REQUISITION & DEPLOYMENT");
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
        tvPrestigePill.setText(String.format(Locale.ROOT, "★ %d PRESTIGE", engine.axisPrestige));
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
            List<PgDatabase.UnitDef> allAvail = getAvailableAxisUnitsForPurchase(purchaseShowAllYears);
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
                boolean affordable = (engine.axisPrestige >= u.cost);
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
                int[] trpIds = {0, 86, 87, 88};
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
            boolean canBuyTotal = (engine.axisPrestige >= finalTotalCost);
            String trpSuffix = (selectedTransportId > 0 && selectedTransportId < db.equipment.size())
                    ? (" + " + db.equipment.get(selectedTransportId).name) : "";

            Button btnConfirmBuy = createStyledButton(
                    canBuyTotal
                            ? String.format(Locale.ROOT, "REQUISITION & DEPLOY %s%s  —  ★ %d PRESTIGE",
                                    sel.name.toUpperCase(Locale.ROOT), trpSuffix, finalTotalCost)
                            : String.format(Locale.ROOT, "INSUFFICIENT PRESTIGE (NEED ★ %d, HAVE ★ %d)",
                                    finalTotalCost, engine.axisPrestige),
                    canBuyTotal ? 0xFF2E6B36 : 0xFF455A64,
                    canBuyTotal ? 0xFFFFF59D : 0xFFCFD8DC);
            LinearLayout.LayoutParams buyBtnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            buyBtnLp.setMargins(0, 8, 0, 4);
            btnConfirmBuy.setLayoutParams(buyBtnLp);
            btnConfirmBuy.setOnClickListener(v -> {
                if (engine.axisPrestige < finalTotalCost) {
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
                Toast.makeText(this, "No secure cities available! Units must be placed by friendly cities free from enemy contact.", Toast.LENGTH_LONG).show();
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
        showUnitDossierDialog(u);
    }

    private void showUnitDossierDialog(PgDatabase.ActiveUnit u) {
        if (u == null) return;
        PgDatabase.UnitDef def = db.equipment.get(u.unitDefId);
        PgDatabase.HexCell cell = (engine.scenario != null && u.col >= 0 && u.col < engine.scenario.cols && u.row >= 0 && u.row < engine.scenario.rows)
                ? engine.scenario.grid[u.col][u.row] : null;

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 24, 32, 24);

        // Header: Unit Portrait + Name + Branch
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iv = new ImageView(this);
        iv.setImageBitmap(assets.createUnitPortrait(engine.getEffectiveIconId(u), def.unitClass, 210, 134));
        header.addView(iv, new LinearLayout.LayoutParams(210, 134));

        LinearLayout headText = new LinearLayout(this);
        headText.setOrientation(LinearLayout.VERTICAL);
        headText.setPadding(24, 0, 0, 0);

        TextView tvName = new TextView(this);
        tvName.setTextColor(0xFFFFD54F);
        tvName.setTextSize(16.5f);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        String sideStr = u.isAxis ? (u.isCore ? "★ AXIS CORE DIVISION" : "AXIS AUXILIARY CORPS") : "ALLIED DIVISION";
        tvName.setText((u.customTitle != null ? u.customTitle : def.name) + "\n" + sideStr);
        headText.addView(tvName);

        TextView tvExp = new TextView(this);
        tvExp.setTextColor(Color.WHITE);
        tvExp.setTextSize(12f);
        tvExp.setText(String.format(Locale.ROOT, "Strength: %d / %d (Sup:%d)  •  Experience: %d (%d★)",
                u.strength, u.getMaxOverstrength(), u.suppression, u.experience, u.getStars()));
        headText.addView(tvExp);

        header.addView(headText);
        box.addView(header);

        // Tactical Specifications Section
        TextView tvStatsTitle = new TextView(this);
        tvStatsTitle.setPadding(0, 18, 0, 4);
        tvStatsTitle.setTextColor(0xFF90CAF9);
        tvStatsTitle.setTextSize(13.5f);
        tvStatsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvStatsTitle.setText("COMBAT SPECIFICATIONS & RECON:");
        box.addView(tvStatsTitle);

        PgDatabase.UnitDef effDef = engine.getEffectiveUnitDef(u);
        String cls = (effDef.unitClass >= 0 && effDef.unitClass < PgDatabase.CLASS_NAMES.length)
                ? PgDatabase.CLASS_NAMES[effDef.unitClass] : "Unit";

        String locStr = cell != null ? String.format(Locale.ROOT, "Hex (%d,%d) %s [%s • Entrenchment: %d]",
                u.col, u.row, cell.name, cell.getTerrainName(), u.entrenchment) : "Reserve Deployment Pool";

        TextView tvStats = new TextView(this);
        tvStats.setTextColor(0xFFECEFF1);
        tvStats.setTextSize(12f);
        tvStats.setText(String.format(Locale.ROOT,
                "Class: %s  •  Location: %s\n" +
                "Attacks:  Soft: %d   Hard: %d   Air: %d   Naval: %d\n" +
                "Defense:  Ground: %d   Air: %d   Close: %d\n" +
                "Tactics:  Initiative: %d   Range: %d   Movement: %d/%d   Ammo: %s   Fuel: %s",
                cls, locStr,
                effDef.softAttack, effDef.hardAttack, effDef.airAttack, effDef.navalAttack,
                effDef.groundDefense, effDef.airDefense, effDef.closeDefense,
                effDef.initiative, effDef.range,
                engine.getRemainingMovementPoints(u), engine.getMaxMovementPoints(u),
                engine.supplyEnabled ? String.valueOf(u.ammo) : "∞",
                engine.supplyEnabled ? String.valueOf(u.fuel) : "∞"));
        box.addView(tvStats);

        // Service Record & Confirmed Kills Section
        TextView tvHonorsTitle = new TextView(this);
        tvHonorsTitle.setPadding(0, 18, 0, 4);
        tvHonorsTitle.setTextColor(0xFFFFD54F);
        tvHonorsTitle.setTextSize(13.5f);
        tvHonorsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvHonorsTitle.setText("SERVICE RECORD & BATTLE HONORS:");
        box.addView(tvHonorsTitle);

        TextView tvKills = new TextView(this);
        tvKills.setTextColor(0xFFECEFF1);
        tvKills.setTextSize(12f);
        tvKills.setText(String.format(Locale.ROOT,
                "Battles Fought: %d   |   Total Confirmed Kills: %d\n" +
                "• Tanks & Armor: %d\n" +
                "• Infantry: %d\n" +
                "• Artillery & Anti-Tank Guns: %d\n" +
                "• Aircraft: %d\n" +
                "• Naval Ships: %d",
                u.battlesFought, u.kills,
                u.tankKills, u.infantryKills, u.gunKills, u.airKills, u.navalKills));
        box.addView(tvKills);

        // Military Citations & Awards Section
        TextView tvAwardsTitle = new TextView(this);
        tvAwardsTitle.setPadding(0, 16, 0, 4);
        tvAwardsTitle.setTextColor(0xFFFFCA28);
        tvAwardsTitle.setTextSize(13.5f);
        tvAwardsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvAwardsTitle.setText("MILITARY DECORATIONS & CITATIONS:");
        box.addView(tvAwardsTitle);

        List<String> awards = u.getAwards();
        TextView tvAwards = new TextView(this);
        tvAwards.setTextColor(0xFFE0E0E0);
        tvAwards.setTextSize(12f);
        if (awards.isEmpty()) {
            tvAwards.setText("No combat citations yet. Requires 5+ confirmed kills to earn the Iron Cross.");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String aw : awards) {
                sb.append("★ ").append(aw).append("\n");
            }
            tvAwards.setText(sb.toString().trim());
        }
        box.addView(tvAwards);

        scroll.addView(box);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle((u.isAxis ? "Service Dossier — " : "Intelligence Report — ") + def.name);
        b.setView(scroll);
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

    private void showUpgradeUnitDialog(PgDatabase.ActiveUnit u) {
        if (u == null || !engine.canUpgradeUnit(u)) {
            Toast.makeText(this, "Unit must be in deployment reserve or unmoved on a friendly City/Port/Airfield with no adjacent enemies to upgrade.", Toast.LENGTH_LONG).show();
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
        tvPrestigePill.setText("★ " + engine.axisPrestige + " PRESTIGE");
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
            List<PgDatabase.UnitDef> allAvail = getAvailableAxisUnitsForPurchase(purchaseShowAllYears);
            tabStrip.removeAllViews();
            rosterContainer.removeAllViews();
            detailCard.removeAllViews();

            // Category tabs
            for (int i = 0; i < PURCHASE_CATEGORY_CLASSES.length; i++) {
                final int catClass = PURCHASE_CATEGORY_CLASSES[i];
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
                boolean affordable = (engine.axisPrestige >= chassisUpgCost);

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
                int[] trpIds = {0, 86, 87, 88};
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
            boolean canAfford = (engine.axisPrestige >= finalTotalCost);
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
                        finalTotalCost, engine.axisPrestige);
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

    private void showFieldManualDialog() {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setPadding(42, 28, 42, 28);
        tv.setTextSize(13.5f);
        tv.setTextColor(0xFFECEFF1);
        tv.setBackgroundColor(0xFF1A211C);
        tv.setText(
                "PANZER GENERAL — OFFICIAL FIELD MANUAL & TACTICAL RULES\n\n" +
                "1. FLEXIBLE TURN ORDER (MOVE & ATTACK IN EITHER ORDER)\n" +
                "• Every unit has 1 Movement Allowance and 1 Attack per turn in either order.\n" +
                "• You may Attack first and then Move afterwards, or Move and then Attack.\n" +
                "• If a unit uses only part of its movement allowance before attacking, it may continue moving with its remaining movement points after the attack.\n" +
                "• UNDO: If a move does not uncover any hidden enemy units, tap UNDO to return the unit to its previous hex.\n\n" +
                "2. INITIATIVE, FIRST STRIKE & TERRAIN INITIATIVE CAPS\n" +
                "• In combat, Effective Initiative = Unit Initiative + Experience Stars (1★–5★).\n" +
                "• Close Terrain caps non-infantry Initiative: Cities & Fortifications cap Initiative at 1; Forests, Mountains, Swamps & Bocage cap at 2; Rough caps at 3. Infantry gains +2 Initiative in close terrain.\n" +
                "• Each point of Initiative advantage grants 20% First Strike fire before the enemy can shoot back.\n\n" +
                "3. SPECIAL UNIT ABILITIES\n" +
                "• TANK OVERRUN: When a Tank destroys a weakened enemy ground unit in close combat with 0 losses to the Tank, it triggers an OVERRUN — advancing into the hex and retaining its attack to strike again!\n" +
                "• RUGGED DEFENSE & PIONIERE: Entrenched defenders can trigger Rugged Defense, inflicting heavy casualties. Pioneer / Engineer units ignore enemy Rugged Defense and strip 2x entrenchment.\n" +
                "• BRIDGE ENGINEERS: A Bridge Engineer standing on a River hex forms a Pontoon Bridge — friendly units cross for 1 MP and suffer no river combat penalties.\n" +
                "• DEFENSIVE ARTILLERY & FIGHTER INTERCEPTORS: Friendly Artillery (range ≥ 2) automatically fires defensive support when an adjacent friendly ground unit is assaulted. Friendly Fighters automatically intercept enemy air attacks on adjacent hexes.\n" +
                "• LEVEL BOMBERS: Strategic bombing suppresses defenders, strips 2 entrenchment, and destroys 25% of target Fuel & Ammo.\n" +
                "• FORCED RETREAT & SURRENDER: Heavy losses force a defender to retreat. If all retreat hexes are blocked by units, impassable terrain, or enemy Zones of Control (ZOC), the unit SURRENDERS!\n\n" +
                "4. SUPPLY, REPLACEMENTS, OVERSTRENGTH, UPGRADE & DISBAND\n" +
                "• SUPPLY: Resupply restores 100% Fuel/Ammo in friendly Cities/Ports/Airfields, 75% in open terrain, or 25% when adjacent to an enemy. Air units refuel automatically over friendly Airfields or adjacent to Carriers.\n" +
                "• REPLACEMENTS & OVERSTRENGTH (11–15): Regular Replacements (REPL) restore strength to 10 (diluting experience). Elite Replacements (ELITE+ / OVRST+) preserve experience and allow veteran units (1★–5★) to Overstrength up to 10 + Stars (max 15)!\n" +
                "• UPGRADE & DISBAND: Units on friendly Supply Points with no adjacent enemies can UPGRADE to newer equipment. Tapping the bottom unit dossier also allows DISBANDING a unit for a 50% Prestige refund.\n" +
                "• AIRLIFT & SEA TRANSPORT: Infantry on a friendly Airfield can embark into Ju 52 Air Transport (AIRLIFT) and PARADROP. Ground units on a friendly Port can embark into Naval Transport (SEA EMBARK) and DISEMBARK on coasts."
        );
        scroll.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("Panzer General — Instruction Manual & Tactical Rules")
                .setView(scroll)
                .setPositiveButton("Understood", null)
                .show();
    }

    private void showInGameMenuDialog() {
        String[] options = {
            "Panzer General Instruction Manual & Tactical Rules",
            "Unit Dossier / Upgrade / Disband Selected Unit",
            "Game Options & Preferences (Supply, Weather, Fog of War...)",
            "Save Game",
            "Load Saved Game",
            "Supply (Fuel & Ammo): " + (engine.supplyEnabled ? "ON (Standard Rules)" : "OFF (Unlimited Fuel & Ammo)"),
            "Weather Effects: " + (engine.weatherEnabled ? "ON (Dynamic Weather)" : "OFF (Always Clear/Dry)"),
            "Fog of War: " + (engine.fogOfWarEnabled ? "ON" : "OFF"),
            "Equipment Encyclopedia (425 Units)",
            "Return to Title Screen"
        };
        new AlertDialog.Builder(this)
                .setTitle("Command Menu")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            showFieldManualDialog();
                            break;
                        case 1:
                            if (mapView.selectedUnit != null) {
                                showActiveUnitActionsModal(mapView.selectedUnit);
                            } else {
                                Toast.makeText(this, "Select a unit on the map first.", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case 2:
                            showPreferencesDialog();
                            break;
                        case 3:
                            showSaveLoadDialog(true);
                            break;
                        case 4:
                            showSaveLoadDialog(false);
                            break;
                        case 5:
                            engine.supplyEnabled = !engine.supplyEnabled;
                            savePreferences();
                            if (mapView.selectedUnit != null) mapView.selectUnit(mapView.selectedUnit);
                            refreshHud();
                            Toast.makeText(this, "Supply (Fuel & Ammo): " + (engine.supplyEnabled ? "ON" : "OFF (Unlimited)"), Toast.LENGTH_SHORT).show();
                            break;
                        case 6:
                            engine.weatherEnabled = !engine.weatherEnabled;
                            if (!engine.weatherEnabled) {
                                engine.weather = 0;
                                engine.groundCondition = 0;
                            }
                            savePreferences();
                            refreshHud();
                            mapView.invalidate();
                            Toast.makeText(this, "Weather Effects: " + (engine.weatherEnabled ? "ON" : "OFF (Always Clear/Dry)"), Toast.LENGTH_SHORT).show();
                            break;
                        case 7:
                            engine.fogOfWarEnabled = !engine.fogOfWarEnabled;
                            engine.recalculateFogOfWar();
                            savePreferences();
                            mapView.invalidate();
                            break;
                        case 8:
                            showEncyclopediaDialog();
                            break;
                        case 9:
                            titleOverlay.setVisibility(View.VISIBLE);
                            break;
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
        String[] slots = new String[5];
        for (int i = 0; i < 5; i++) {
            slots[i] = engine.getSaveSummary(this, i + 1);
        }
        new AlertDialog.Builder(this)
                .setTitle(isSave ? "Save Tactical Game" : "Load Tactical Game")
                .setItems(slots, (d, which) -> {
                    int slot = which + 1;
                    if (isSave) {
                        try {
                            engine.saveGame(this, slot);
                            Toast.makeText(this, "Saved to Slot " + slot, Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    } else {
                        if (engine.loadGame(this, slot)) {
                            if (titleOverlay != null) titleOverlay.setVisibility(View.GONE);
                            mapView.clearSelection();
                            refreshHud();
                            mapView.invalidate();
                            Toast.makeText(this, "Loaded Slot " + slot, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Slot " + slot + " is empty.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
    protected void onPause() {
        super.onPause();
        sounds.stopMusic();
        sounds.stopVoice();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sounds.isMusicEnabled()) {
            sounds.startMusic(engine.currentScenarioNumber % 6);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sounds.release();
    }
}
