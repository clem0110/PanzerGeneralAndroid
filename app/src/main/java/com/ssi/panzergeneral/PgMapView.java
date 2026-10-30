package com.ssi.panzergeneral;

import android.content.Context;
import android.graphics.*;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.*;

public class PgMapView extends View {
    public interface MapInteractionListener {
        void onHexSelected(int col, int row, PgDatabase.HexCell cell, PgDatabase.ActiveUnit unit);
        void onUnitMoved(PgDatabase.ActiveUnit unit, int col, int row);
        void onCombatInitiated(PgDatabase.ActiveUnit attacker, PgDatabase.ActiveUnit defender, PgGameEngine.CombatReport report);
        void onPurchasePlacementRequested(int col, int row);
        void onCoreUnitDeployRequested(PgDatabase.ActiveUnit unit, int col, int row);
        void onDeployHexTapped(int col, int row);
    }

    // Original Panzer General TACMAP.SHP sprite dimensions: 60x50
    // Column step = 45px, Row step = 50px, odd columns shifted down by 25px
    private static final float TILE_W = 60f;
    private static final float TILE_H = 50f;
    private static final float COL_STEP = 45f;
    private static final float ROW_STEP = 50f;
    private static final float ODD_OFFSET_Y = 25f;

    private final PgAssetManager assets;
    private final PgGameEngine engine;
    private final PgSoundManager sounds;
    private MapInteractionListener listener;

    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 4.5f;
    public static final float DEFAULT_SCALE = 1.6f;

    private float scale = DEFAULT_SCALE;
    private float panX = 40f;
    private float panY = 80f;

    private final RectF btnZoomInRect = new RectF();
    private final RectF btnZoomResetRect = new RectF();
    private final RectF btnZoomOutRect = new RectF();

    public boolean airModePreferred = false;
    public boolean strategicMapOverlay = false;
    public boolean purchasePlacementMode = false;
    public int pendingPurchaseUnitId = -1;
    public int pendingPurchaseTransportId = 0;
    public PgDatabase.ActiveUnit pendingDeployCoreUnit = null;

    public int selectedCol = -1;
    public int selectedRow = -1;
    public PgDatabase.ActiveUnit selectedUnit = null;
    public Map<Integer, Integer> reachableHexes = new HashMap<>();
    public List<PgDatabase.ActiveUnit> validTargets = new ArrayList<>();

    // Explosion animation state
    private int animCol = -1, animRow = -1;
    private int animFrame = -1;
    private long animLastTick = 0;
    private PgGameEngine.CombatReport lastCombatBanner = null;
    private long bannerExpireTime = 0;

    // Turn banner state
    private String turnBannerTitle = null;
    private String turnBannerSubtitle = null;
    private long turnBannerExpireTime = 0;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint hexStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hexPath = new Path();

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public PgMapView(Context context, PgAssetManager assets, PgGameEngine engine, PgSoundManager sounds) {
        super(context);
        this.assets = assets;
        this.engine = engine;
        this.sounds = sounds;

        hexStrokePaint.setStyle(Paint.Style.STROKE);
        hexStrokePaint.setStrokeWidth(1.5f);

        highlightFillPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(11f);
        textPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        textPaint.setShadowLayer(2.5f, 1f, 1f, Color.BLACK);
        textPaint.setTextAlign(Paint.Align.CENTER);

        badgePaint.setStyle(Paint.Style.FILL);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (scaleDetector.isInProgress()) {
                    return false;
                }
                panX -= distanceX;
                panY -= distanceY;
                clampPan();
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale < 2.4f) {
                    zoomAt(3.0f, e.getX(), e.getY());
                } else {
                    zoomAt(DEFAULT_SCALE, e.getX(), e.getY());
                }
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (handleZoomControlTap(e.getX(), e.getY())) {
                    return true;
                }
                handleTap(e.getX(), e.getY());
                return true;
            }
        });
    }

    public void zoomBy(float factor, float focusX, float focusY) {
        float prevScale = scale;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        panX = focusX - (focusX - panX) * (scale / prevScale);
        panY = focusY - (focusY - panY) * (scale / prevScale);
        clampPan();
        invalidate();
    }

    public void zoomAt(float targetScale, float focusX, float focusY) {
        float prevScale = scale;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, targetScale));
        panX = focusX - (focusX - panX) * (scale / prevScale);
        panY = focusY - (focusY - panY) * (scale / prevScale);
        clampPan();
        invalidate();
    }

    public void zoomIn() {
        float cx = getWidth() > 0 ? getWidth() * 0.5f : 640f;
        float cy = getHeight() > 0 ? getHeight() * 0.5f : 360f;
        zoomBy(1.25f, cx, cy);
    }

    public void zoomOut() {
        float cx = getWidth() > 0 ? getWidth() * 0.5f : 640f;
        float cy = getHeight() > 0 ? getHeight() * 0.5f : 360f;
        zoomBy(0.8f, cx, cy);
    }

    public void resetZoom() {
        float cx = getWidth() > 0 ? getWidth() * 0.5f : 640f;
        float cy = getHeight() > 0 ? getHeight() * 0.5f : 360f;
        zoomAt(DEFAULT_SCALE, cx, cy);
    }

    public float getScale() {
        return scale;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & android.view.InputDevice.SOURCE_CLASS_POINTER) != 0) {
            if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (vScroll != 0f) {
                    float factor = vScroll > 0 ? 1.2f : 0.83f;
                    zoomBy(factor, event.getX(), event.getY());
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    public void showYourTurnBanner(int currentTurn, int maxTurns, String weatherLabel) {
        this.turnBannerTitle = String.format(Locale.ROOT, "★ YOUR TURN — TURN %d / %d ★", currentTurn, maxTurns);
        this.turnBannerSubtitle = "Axis Forces Ready For Orders • " + (weatherLabel != null ? weatherLabel : "Clear / Dry");
        this.turnBannerExpireTime = System.currentTimeMillis() + 3800;
        invalidate();
    }

    private boolean handleZoomControlTap(float x, float y) {
        if (strategicMapOverlay) return false;
        if (btnZoomInRect.contains(x, y)) {
            sounds.playClick();
            zoomIn();
            return true;
        }
        if (btnZoomResetRect.contains(x, y)) {
            sounds.playClick();
            resetZoom();
            return true;
        }
        if (btnZoomOutRect.contains(x, y)) {
            sounds.playClick();
            zoomOut();
            return true;
        }
        return false;
    }

    private void drawZoomControls(Canvas canvas) {
        float btnW = 48f;
        float btnH = 42f;
        float rx = getWidth() - btnW - 14f;
        float y = 14f;

        btnZoomInRect.set(rx, y, rx + btnW, y + btnH);
        y += btnH + 8f;
        btnZoomResetRect.set(rx, y, rx + btnW, y + btnH);
        y += btnH + 8f;
        btnZoomOutRect.set(rx, y, rx + btnW, y + btnH);

        drawSingleZoomButton(canvas, btnZoomInRect, "+", 0xFFFFD54F, 24f);
        String scaleText = String.format(Locale.ROOT, "%.1fx", scale);
        drawSingleZoomButton(canvas, btnZoomResetRect, scaleText, 0xFFECEFF1, 13f);
        drawSingleZoomButton(canvas, btnZoomOutRect, "-", 0xFFFFD54F, 24f);
    }

    private void drawSingleZoomButton(Canvas canvas, RectF r, String label, int textColor, float textSize) {
        badgePaint.setColor(0xD91B241D);
        canvas.drawRoundRect(r, 8f, 8f, badgePaint);
        hexStrokePaint.setColor(0xFFC5A059);
        hexStrokePaint.setStrokeWidth(1.8f);
        canvas.drawRoundRect(r, 8f, 8f, hexStrokePaint);

        textPaint.setColor(textColor);
        textPaint.setTextSize(textSize);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = r.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(label, r.centerX(), textY, textPaint);
    }

    public void setListener(MapInteractionListener listener) {
        this.listener = listener;
    }

    public void centerOnHex(int col, int row) {
        float wx = col * COL_STEP + TILE_W * 0.5f;
        float wy = row * ROW_STEP + ((col & 1) != 0 ? ODD_OFFSET_Y : 0f) + TILE_H * 0.5f;
        int vw = getWidth() > 0 ? getWidth() : 1280;
        int vh = getHeight() > 0 ? getHeight() : 720;
        panX = vw * 0.5f - wx * scale;
        panY = vh * 0.5f - wy * scale;
        clampPan();
        invalidate();
    }

    public void clearSelection() {
        selectedCol = -1;
        selectedRow = -1;
        selectedUnit = null;
        reachableHexes.clear();
        validTargets.clear();
        if (listener != null && engine != null && engine.scenario != null) {
            listener.onHexSelected(-1, -1, null, null);
        }
        invalidate();
    }

    public void selectUnit(PgDatabase.ActiveUnit u) {
        if (u == null) {
            clearSelection();
            return;
        }
        selectedCol = u.col;
        selectedRow = u.row;
        selectedUnit = u;
        if (u.isAxis && engine.isPlayerTurn && !engine.isDeploymentPhase) {
            reachableHexes = engine.getReachableHexes(u);
            validTargets = engine.getValidTargets(u);
        } else {
            reachableHexes.clear();
            validTargets.clear();
        }
        if (listener != null && engine.scenario != null) {
            PgDatabase.HexCell cell = (u.col >= 0 && u.col < engine.scenario.cols && u.row >= 0 && u.row < engine.scenario.rows)
                    ? engine.scenario.grid[u.col][u.row] : null;
            listener.onHexSelected(u.col, u.row, cell, u);
        }
        invalidate();
    }

    public void triggerExplosion(int col, int row, PgGameEngine.CombatReport rep) {
        this.animCol = col;
        this.animRow = row;
        this.animFrame = 0;
        this.animLastTick = System.currentTimeMillis();
        this.lastCombatBanner = rep;
        this.bannerExpireTime = System.currentTimeMillis() + 3600;
        invalidate();
    }

    private void clampPan() {
        if (engine.scenario == null) return;
        float mapW = (engine.scenario.cols * COL_STEP + 30f) * scale;
        float mapH = (engine.scenario.rows * ROW_STEP + 40f) * scale;
        int vw = getWidth() > 0 ? getWidth() : 1280;
        int vh = getHeight() > 0 ? getHeight() : 720;
        float minX = Math.min(80f, vw - mapW - 80f);
        float minY = Math.min(80f, vh - mapH - 80f);
        panX = Math.max(minX, Math.min(200f, panX));
        panY = Math.max(minY, Math.min(200f, panY));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    private void handleTap(float screenX, float screenY) {
        if (engine.scenario == null) return;
        if (!engine.isPlayerTurn) return;

        if (turnBannerTitle != null && System.currentTimeMillis() < turnBannerExpireTime) {
            turnBannerExpireTime = 0;
            invalidate();
        }

        if (strategicMapOverlay) {
            // Tap on strategic map jumps camera to tapped hex
            float pad = 36f;
            float availW = getWidth() - pad * 2;
            float availH = getHeight() - pad * 2 - 80f;
            float cellW = Math.min(availW / engine.scenario.cols, availH / engine.scenario.rows);
            float startX = (getWidth() - cellW * engine.scenario.cols) * 0.5f;
            float startY = (getHeight() - cellW * engine.scenario.rows) * 0.5f;
            int c = (int) ((screenX - startX) / cellW);
            int r = (int) ((screenY - startY) / cellW);
            strategicMapOverlay = false;
            if (c >= 0 && c < engine.scenario.cols && r >= 0 && r < engine.scenario.rows) {
                centerOnHex(c, r);
            }
            invalidate();
            return;
        }

        float worldX = (screenX - panX) / scale;
        float worldY = (screenY - panY) / scale;
        int[] hex = pixelToHex(worldX, worldY);
        if (hex == null) return;
        int col = hex[0], row = hex[1];
        PgDatabase.HexCell cell = (engine.scenario != null && col >= 0 && col < engine.scenario.cols && row >= 0 && row < engine.scenario.rows)
                ? engine.scenario.grid[col][row] : null;

        if (purchasePlacementMode) {
            if (listener != null) {
                listener.onPurchasePlacementRequested(col, row);
            }
            return;
        }

        if (pendingDeployCoreUnit != null) {
            if (listener != null) {
                listener.onCoreUnitDeployRequested(pendingDeployCoreUnit, col, row);
            }
            return;
        }

        if (engine.isDeploymentPhase) {
            PgDatabase.ActiveUnit airOcc = engine.getUnitAt(col, row, true);
            PgDatabase.ActiveUnit gndOcc = engine.getUnitAt(col, row, false);
            boolean canDeployHere = false;
            if ((airOcc == null || gndOcc == null) && !engine.unplacedCoreUnits.isEmpty()) {
                if (cell != null && cell.isDeployHex) {
                    canDeployHere = true;
                } else {
                    for (PgDatabase.ActiveUnit u : engine.unplacedCoreUnits) {
                        for (int[] h : engine.getValidCoreDeploymentHexes(u)) {
                            if (h[0] == col && h[1] == row) {
                                canDeployHere = true;
                                break;
                            }
                        }
                        if (canDeployHere) break;
                    }
                }
            }

            if (canDeployHere) {
                // If completely unoccupied hex, tap always opens deployment dialog
                if (airOcc == null && gndOcc == null) {
                    if (listener != null) {
                        listener.onDeployHexTapped(col, row);
                    }
                    return;
                }
                // If air mode is preferred and air layer is open, tap opens deployment dialog for planes!
                if (airModePreferred && airOcc == null) {
                    if (listener != null) {
                        listener.onDeployHexTapped(col, row);
                    }
                    return;
                }
            }
        }

        // 1. Check if tapping a valid attack target for currently selected friendly unit
        if (selectedUnit != null && selectedUnit.isAxis && engine.isPlayerTurn) {
            for (PgDatabase.ActiveUnit target : validTargets) {
                if (target.col == col && target.row == row) {
                    PgDatabase.UnitDef aDef = engine.getEffectiveUnitDef(selectedUnit);
                    sounds.playCombatSound(aDef);
                    PgGameEngine.CombatReport rep = engine.executeCombat(selectedUnit, target);
                    triggerExplosion(col, row, rep);
                    if (selectedUnit.strength > 0) {
                        selectUnit(selectedUnit);
                    } else {
                        clearSelection();
                    }
                    if (listener != null) {
                        listener.onCombatInitiated(selectedUnit, target, rep);
                    }
                    return;
                }
            }

            // 2. Check if tapping a reachable movement hex
            int key = row * engine.scenario.cols + col;
            if (reachableHexes.containsKey(key) && (col != selectedUnit.col || row != selectedUnit.row)) {
                PgDatabase.UnitDef def = engine.db.equipment.get(selectedUnit.unitDefId);
                if (engine.moveUnit(selectedUnit, col, row)) {
                    if (engine.lastSurpriseReport != null) {
                        sounds.playCombatSound(def);
                        triggerExplosion(col, row, engine.lastSurpriseReport);
                    } else {
                        sounds.playMoveSound(def);
                    }
                    if (selectedUnit.strength > 0) {
                        selectUnit(selectedUnit);
                    } else {
                        clearSelection();
                    }
                    if (listener != null) {
                        listener.onUnitMoved(selectedUnit, col, row);
                    }
                    return;
                }
            }
        }

        // 3. If clicking a unit or hex that is already selected, handle deploy dialog or switch layer
        if ((selectedUnit != null && selectedUnit.col == col && selectedUnit.row == row)
                || (selectedUnit == null && selectedCol == col && selectedRow == row)) {
            if (engine.isDeploymentPhase) {
                PgDatabase.ActiveUnit airOcc = engine.getUnitAt(col, row, true);
                PgDatabase.ActiveUnit gndOcc = engine.getUnitAt(col, row, false);
                boolean canDeployHere = false;
                if ((airOcc == null || gndOcc == null) && !engine.unplacedCoreUnits.isEmpty()) {
                    if (cell != null && cell.isDeployHex) {
                        canDeployHere = true;
                    } else {
                        for (PgDatabase.ActiveUnit u : engine.unplacedCoreUnits) {
                            for (int[] h : engine.getValidCoreDeploymentHexes(u)) {
                                if (h[0] == col && h[1] == row) {
                                    canDeployHere = true;
                                    break;
                                }
                            }
                            if (canDeployHere) break;
                        }
                    }
                }
                if (canDeployHere) {
                    if (listener != null) {
                        listener.onDeployHexTapped(col, row);
                    }
                    return;
                }
            }

            // If hex has another friendly unit on opposite layer, toggle selection between them
            if (selectedUnit != null) {
                PgDatabase.ActiveUnit otherLayer = engine.getUnitAt(col, row, !engine.isUnitOnAirLayer(selectedUnit));
                if (otherLayer != null && otherLayer.isAxis) {
                    sounds.playSelect();
                    selectUnit(otherLayer);
                    return;
                }
            }

            sounds.playClick();
            clearSelection();
            return;
        }

        // 4. Otherwise select unit or inspect hex at (col, row)
        PgDatabase.ActiveUnit tappedUnit = engine.getTopUnitAt(col, row, airModePreferred);
        if (tappedUnit != null) {
            PgDatabase.ActiveUnit alt = engine.getUnitAt(col, row, !engine.isUnitOnAirLayer(tappedUnit));
            if (alt != null) {
                // If one unit belongs to the player (Axis) and the other is enemy, prioritize selecting player's unit
                if (!tappedUnit.isAxis && alt.isAxis) {
                    tappedUnit = alt;
                }
            }
        }
        if (tappedUnit != null && engine.isUnitVisibleToPlayer(tappedUnit)) {
            sounds.playSelect();
            selectUnit(tappedUnit);
        } else {
            sounds.playClick();
            selectedCol = col;
            selectedRow = row;
            selectedUnit = null;
            reachableHexes.clear();
            validTargets.clear();
            if (listener != null) {
                listener.onHexSelected(col, row, engine.scenario.grid[col][row], null);
            }
            invalidate();
        }
    }

    private int[] pixelToHex(float wx, float wy) {
        if (engine.scenario == null) return null;
        int approxCol = (int) (wx / COL_STEP);
        int approxRow = (int) (wy / ROW_STEP);
        int bestCol = -1, bestRow = -1;
        float bestDistSq = Float.MAX_VALUE;

        for (int c = Math.max(0, approxCol - 2); c <= Math.min(engine.scenario.cols - 1, approxCol + 2); c++) {
            for (int r = Math.max(0, approxRow - 2); r <= Math.min(engine.scenario.rows - 1, approxRow + 2); r++) {
                float cx = c * COL_STEP + TILE_W * 0.5f;
                float cy = r * ROW_STEP + ((c & 1) != 0 ? ODD_OFFSET_Y : 0f) + TILE_H * 0.5f;
                float dx = wx - cx;
                float dy = wy - cy;
                float d2 = dx * dx + dy * dy;
                if (d2 < bestDistSq && d2 <= 950f) {
                    bestDistSq = d2;
                    bestCol = c;
                    bestRow = r;
                }
            }
        }
        return (bestCol >= 0) ? new int[]{bestCol, bestRow} : null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF141814);
        if (engine.scenario == null) return;

        if (strategicMapOverlay) {
            drawStrategicMap(canvas);
            return;
        }

        canvas.save();
        canvas.translate(panX, panY);
        canvas.scale(scale, scale);

        int cols = engine.scenario.cols;
        int rows = engine.scenario.rows;

        // Visible world bounds for culling
        float viewLeft = -panX / scale - TILE_W;
        float viewTop = -panY / scale - TILE_H;
        float viewRight = (getWidth() - panX) / scale + TILE_W;
        float viewBottom = (getHeight() - panY) / scale + TILE_H;

        int minCol = Math.max(0, (int) (viewLeft / COL_STEP));
        int maxCol = Math.min(cols - 1, (int) (viewRight / COL_STEP) + 1);
        int minRow = Math.max(0, (int) (viewTop / ROW_STEP) - 1);
        int maxRow = Math.min(rows - 1, (int) (viewBottom / ROW_STEP) + 1);

        List<int[]> deploySpots = purchasePlacementMode
                ? engine.getAvailableDeployHexes(pendingPurchaseUnitId)
                : Collections.emptyList();

        List<int[]> coreDeploySpots = (engine.isDeploymentPhase && pendingDeployCoreUnit != null)
                ? engine.getValidCoreDeploymentHexes(pendingDeployCoreUnit)
                : Collections.emptyList();

        // Pass 1: Draw TACMAP.SHP terrain tiles, fog of war, hex grid, and highlights
        for (int c = minCol; c <= maxCol; c++) {
            float x = c * COL_STEP;
            float yOff = ((c & 1) != 0) ? ODD_OFFSET_Y : 0f;
            for (int r = minRow; r <= maxRow; r++) {
                float y = r * ROW_STEP + yOff;
                PgDatabase.HexCell cell = engine.scenario.grid[c][r];

                Bitmap tile = assets.getSprite("TACMAP.SHP", cell.tileSpriteId);
                if (tile != null) {
                    canvas.drawBitmap(tile, x, y, bitmapPaint);
                } else {
                    buildHexPath(x, y);
                    highlightFillPaint.setColor(0xFF4A5D32);
                    canvas.drawPath(hexPath, highlightFillPaint);
                }

                boolean visible = !engine.fogOfWarEnabled || engine.visibleHexes[c][r];
                if (!visible) {
                    buildHexPath(x, y);
                    highlightFillPaint.setColor(0x66000000);
                    canvas.drawPath(hexPath, highlightFillPaint);
                }

                // Draw subtle hex outline if enabled in Preferences
                buildHexPath(x, y);
                if (engine.showHexGrid) {
                    hexStrokePaint.setColor(0x38000000);
                    hexStrokePaint.setStrokeWidth(1.0f);
                    canvas.drawPath(hexPath, hexStrokePaint);
                }

                // Objective golden ring
                if (cell.isObjective) {
                    hexStrokePaint.setColor(0xFFFFD700);
                    hexStrokePaint.setStrokeWidth(2.4f);
                    canvas.drawPath(hexPath, hexStrokePaint);
                }

                // Movement highlight
                int key = r * cols + c;
                if (reachableHexes.containsKey(key) && (c != selectedCol || r != selectedRow)) {
                    highlightFillPaint.setColor(0x4032FF64);
                    canvas.drawPath(hexPath, highlightFillPaint);
                    hexStrokePaint.setColor(0xAA32FF64);
                    hexStrokePaint.setStrokeWidth(1.6f);
                    canvas.drawPath(hexPath, hexStrokePaint);
                }

                // Purchase deployment highlight
                if (purchasePlacementMode) {
                    for (int[] ds : deploySpots) {
                        if (ds[0] == c && ds[1] == r) {
                            highlightFillPaint.setColor(0x5500E5FF);
                            canvas.drawPath(hexPath, highlightFillPaint);
                            hexStrokePaint.setColor(0xFF00E5FF);
                            hexStrokePaint.setStrokeWidth(2.2f);
                            canvas.drawPath(hexPath, hexStrokePaint);
                            break;
                        }
                    }
                }

                // Campaign core deployment highlight
                if (engine.isDeploymentPhase) {
                    if (pendingDeployCoreUnit != null) {
                        for (int[] ds : coreDeploySpots) {
                            if (ds[0] == c && ds[1] == r) {
                                highlightFillPaint.setColor(0x5500E5FF);
                                canvas.drawPath(hexPath, highlightFillPaint);
                                hexStrokePaint.setColor(0xFF00E5FF);
                                hexStrokePaint.setStrokeWidth(2.4f);
                                canvas.drawPath(hexPath, hexStrokePaint);
                                break;
                            }
                        }
                    } else if (cell.isDeployHex) {
                        highlightFillPaint.setColor(0x30FFD54F);
                        canvas.drawPath(hexPath, highlightFillPaint);
                        hexStrokePaint.setColor(0xCCFFD54F);
                        hexStrokePaint.setStrokeWidth(1.8f);
                        canvas.drawPath(hexPath, hexStrokePaint);
                    }
                }

                // Selected hex highlight
                if (c == selectedCol && r == selectedRow) {
                    hexStrokePaint.setColor(0xFFFFFFFF);
                    hexStrokePaint.setStrokeWidth(2.5f);
                    canvas.drawPath(hexPath, hexStrokePaint);
                }
            }
        }

        // Pass 2: Draw City/Objective Labels & Ownership Flags (FLAGS.SHP)
        for (int c = minCol; c <= maxCol; c++) {
            float x = c * COL_STEP;
            float yOff = ((c & 1) != 0) ? ODD_OFFSET_Y : 0f;
            for (int r = minRow; r <= maxRow; r++) {
                float y = r * ROW_STEP + yOff;
                PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                if (cell.ownerNation > 0 && (cell.isObjective || cell.terrainType == 8 || cell.terrainType == 3 || cell.terrainType == 6)) {
                    Bitmap flag = assets.getSprite("FLAGS.SHP", Math.max(0, cell.ownerNation - 1));
                    if (flag != null) {
                        canvas.drawBitmap(flag, x, y + 6f, bitmapPaint);
                    }
                }
                if ((cell.isObjective || cell.terrainType == 8) && cell.nameIndex > 13 && scale >= 1.0f) {
                    textPaint.setColor(cell.isObjective ? 0xFFFFE57F : 0xFFEAEAEA);
                    textPaint.setTextSize(9.5f);
                    canvas.drawText(cell.name, x + TILE_W * 0.5f, y + TILE_H - 3f, textPaint);
                }
            }
        }

        // Pass 3: Draw Units (TACICONS.SHP + STRENGTH.SHP) and Combat Prediction Reticule Badges
        for (int c = minCol; c <= maxCol; c++) {
            float x = c * COL_STEP;
            float yOff = ((c & 1) != 0) ? ODD_OFFSET_Y : 0f;
            for (int r = minRow; r <= maxRow; r++) {
                float y = r * ROW_STEP + yOff;
                boolean visible = !engine.fogOfWarEnabled || engine.visibleHexes[c][r];
                if (!visible) continue;

                PgDatabase.ActiveUnit groundUnit = engine.getUnitAt(c, r, false);
                PgDatabase.ActiveUnit airUnit = engine.getUnitAt(c, r, true);
                if (groundUnit != null && !engine.isUnitVisibleToPlayer(groundUnit)) groundUnit = null;
                if (airUnit != null && !engine.isUnitVisibleToPlayer(airUnit)) airUnit = null;

                if (groundUnit != null && airUnit != null) {
                    boolean groundSelected = (selectedUnit == groundUnit);
                    boolean airSelected = (selectedUnit == airUnit);
                    if (airSelected || (airModePreferred && !groundSelected)) {
                        drawSingleUnit(canvas, groundUnit, x - 3f, y + 3f, true);
                        drawSingleUnit(canvas, airUnit, x, y, false);
                    } else {
                        drawSingleUnit(canvas, airUnit, x + 3f, y - 3f, true);
                        drawSingleUnit(canvas, groundUnit, x, y, false);
                    }
                } else if (groundUnit != null) {
                    drawSingleUnit(canvas, groundUnit, x, y, false);
                } else if (airUnit != null) {
                    drawSingleUnit(canvas, airUnit, x, y, false);
                }

                // Draw valid target crosshair overlay + Panzer General Combat Prediction Odds badge
                for (PgDatabase.ActiveUnit target : validTargets) {
                    if (target.col == c && target.row == r) {
                        buildHexPath(x, y);
                        highlightFillPaint.setColor(0x45FF1744);
                        canvas.drawPath(hexPath, highlightFillPaint);
                        hexStrokePaint.setColor(0xFFFF1744);
                        hexStrokePaint.setStrokeWidth(2.4f);
                        canvas.drawPath(hexPath, hexStrokePaint);

                        if (selectedUnit != null) {
                            PgGameEngine.CombatPrediction pred = engine.predictCombat(selectedUnit, target);
                            badgePaint.setColor(0xDD1A1010);
                            RectF oddsRect = new RectF(x + 9f, y + 2f, x + 51f, y + 14.5f);
                            canvas.drawRoundRect(oddsRect, 3f, 3f, badgePaint);
                            hexStrokePaint.setColor(0xFFFF5252);
                            hexStrokePaint.setStrokeWidth(1f);
                            canvas.drawRoundRect(oddsRect, 3f, 3f, hexStrokePaint);
                            textPaint.setColor(0xFFFFE57F);
                            textPaint.setTextSize(8.5f);
                            String oddsText = "-" + pred.expectedAttackerLosses + " : -" + pred.expectedDefenderLosses
                                    + (pred.ruggedDefenseChance ? "!" : "");
                            canvas.drawText(oddsText, x + 30f, y + 11.2f, textPaint);
                        }
                        break;
                    }
                }
            }
        }

        // Pass 4: Draw Explosion Animation (EXPLODE.SHP) if active
        if (animFrame >= 0 && animCol >= 0 && animRow >= 0) {
            float ex = animCol * COL_STEP;
            float ey = animRow * ROW_STEP + ((animCol & 1) != 0 ? ODD_OFFSET_Y : 0f);
            Bitmap expBmp = assets.getSprite("EXPLODE.SHP", animFrame);
            if (expBmp != null) {
                canvas.drawBitmap(expBmp, ex, ey, bitmapPaint);
            }
            long now = System.currentTimeMillis();
            if (now - animLastTick > 85) {
                animFrame++;
                animLastTick = now;
                if (animFrame >= 5) {
                    animFrame = -1;
                }
            }
            postInvalidateDelayed(40);
        }

        canvas.restore();

        if (!strategicMapOverlay) {
            drawZoomControls(canvas);
        }

        // Draw floating combat report banner if recent
        if (lastCombatBanner != null && System.currentTimeMillis() < bannerExpireTime) {
            drawCombatBanner(canvas, lastCombatBanner);
            postInvalidateDelayed(250);
        } else if (!engine.isPlayerTurn && !strategicMapOverlay) {
            drawEnemyTurnBanner(canvas);
            postInvalidateDelayed(250);
        }

        // Draw Your Turn banner if active
        if (turnBannerTitle != null && System.currentTimeMillis() < turnBannerExpireTime) {
            drawYourTurnBanner(canvas);
            postInvalidateDelayed(150);
        }
    }

    private void drawSingleUnit(Canvas canvas, PgDatabase.ActiveUnit u, float x, float y, boolean dimmed) {
        int iconIdx = engine.getEffectiveIconId(u);

        Bitmap unitBmp = assets.getSprite("TACICONS.SHP", iconIdx, u.facingLeft);
        if (unitBmp != null) {
            bitmapPaint.setAlpha(dimmed ? 160 : (u.moved && u.attacked && u.isAxis ? 195 : 255));
            canvas.drawBitmap(unitBmp, x, y, bitmapPaint);
            bitmapPaint.setAlpha(255);
        }

        if (engine.showUnitStrength) {
            // Draw strength badge from STRENGTH.SHP or crisp badge (supports Overstrength 11..15!)
            int strIdx = Math.max(0, Math.min(14, u.strength - 1)) + (u.isAxis ? 0 : 15);
            Bitmap strBmp = (u.strength <= 15) ? assets.getSprite("STRENGTH.SHP", strIdx) : null;
            if (strBmp != null && u.strength <= 10) {
                canvas.drawBitmap(strBmp, x, y, bitmapPaint);
            } else {
                badgePaint.setColor(u.isAxis ? (u.strength > 10 ? 0xFF1B5E20 : 0xFF37474F) : 0xFF5D4037);
                canvas.drawRect(x + 21f, y + 38f, x + 39f, y + 49f, badgePaint);
                textPaint.setColor(u.isCore ? 0xFFFFD700 : Color.WHITE);
                textPaint.setTextSize(9f);
                canvas.drawText(String.valueOf(u.strength), x + 30f, y + 47f, textPaint);
            }

            // Draw experience star indicator
            int stars = u.getStars();
            if (stars > 0) {
                Bitmap starBmp = assets.getSprite("EXPSTAR.SHP", Math.min(5, stars - 1));
                if (starBmp != null) {
                    canvas.drawBitmap(starBmp, x + 42f, y + 38f, bitmapPaint);
                }
            }
        }

        // Action readiness dot for Axis player units
        if (u.isAxis && engine.isPlayerTurn && (!u.moved || !u.attacked)) {
            badgePaint.setColor(!u.moved ? 0xFF00E676 : 0xFFFFC400);
            canvas.drawCircle(x + 15f, y + 42f, 3.2f, badgePaint);
        }
    }

    private void drawCombatBanner(Canvas canvas, PgGameEngine.CombatReport rep) {
        float bw = Math.min(620f, getWidth() * 0.78f);
        float bh = 78f;
        float bx = (getWidth() - bw) * 0.5f;
        float by = 84f;

        badgePaint.setColor(0xE61C221E);
        RectF r = new RectF(bx, by, bx + bw, by + bh);
        canvas.drawRoundRect(r, 12f, 12f, badgePaint);
        hexStrokePaint.setColor(rep.overrun ? 0xFF00E676 : (rep.ruggedDefense || rep.surpriseContact ? 0xFFFF5252 : 0xFFC5A059));
        hexStrokePaint.setStrokeWidth(2.2f);
        canvas.drawRoundRect(r, 12f, 12f, hexStrokePaint);

        textPaint.setColor(0xFFFFD54F);
        textPaint.setTextSize(21f);
        String prefix = rep.overrun ? "TANK OVERRUN! "
                : (rep.surpriseContact ? "SURPRISE CONTACT! "
                : (rep.ruggedDefense ? "RUGGED DEFENSE! " : "COMBAT RESULT: "));
        String headline = prefix + rep.attackerName + " vs " + rep.defenderName;
        canvas.drawText(headline, bx + bw * 0.5f, by + 28f, textPaint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(17.5f);
        String extra = "";
        if (rep.submarineDived) extra += " [SUB DIVED & EVADED!]";
        else if (rep.defenderSurrendered) extra += " [SURRENDERED!]";
        else if (rep.defenderRetreated) extra += " [RETREATED]";
        if (rep.interceptorUnit != null) extra += " [INTERCEPTED]";
        else if (rep.supportUnit != null) extra += " [ARTY SUPPORT]";
        String detail = String.format(Locale.ROOT, "Attacker: -%d%s   |   Defender: -%d%s%s",
                rep.attackerLosses, rep.attackerDestroyed ? " (DESTROYED)" : "",
                rep.defenderLosses, rep.defenderDestroyed && !rep.defenderSurrendered ? " (DESTROYED)" : "",
                extra);
        canvas.drawText(detail, bx + bw * 0.5f, by + 57f, textPaint);
    }

    private void drawYourTurnBanner(Canvas canvas) {
        float bw = Math.min(680f, getWidth() * 0.85f);
        float bh = 94f;
        float bx = (getWidth() - bw) * 0.5f;
        float by = Math.max(90f, (getHeight() - bh) * 0.36f);

        RectF r = new RectF(bx, by, bx + bw, by + bh);

        // Dark translucent tactical panel
        badgePaint.setColor(0xF2162018);
        canvas.drawRoundRect(r, 14f, 14f, badgePaint);

        // Gold border with inner accent
        hexStrokePaint.setColor(0xFFFFD54F);
        hexStrokePaint.setStrokeWidth(2.6f);
        canvas.drawRoundRect(r, 14f, 14f, hexStrokePaint);

        RectF inner = new RectF(bx + 4f, by + 4f, bx + bw - 4f, by + bh - 4f);
        hexStrokePaint.setColor(0x80C5A059);
        hexStrokePaint.setStrokeWidth(1.2f);
        canvas.drawRoundRect(inner, 10f, 10f, hexStrokePaint);

        // Title: Gleaming Gold with shadow
        textPaint.setColor(0xFFFFD54F);
        textPaint.setTextSize(26f);
        textPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        textPaint.setShadowLayer(3.5f, 1.5f, 1.5f, Color.BLACK);
        canvas.drawText(turnBannerTitle, bx + bw * 0.5f, by + 42f, textPaint);

        // Subtitle: Off-white tactical status
        textPaint.setColor(0xFFECEFF1);
        textPaint.setTextSize(16f);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        canvas.drawText(turnBannerSubtitle, bx + bw * 0.5f, by + 74f, textPaint);
    }

    private void drawEnemyTurnBanner(Canvas canvas) {
        float bw = Math.min(540f, getWidth() * 0.85f);
        float bh = 46f;
        float bx = (getWidth() - bw) * 0.5f;
        float by = 60f;

        RectF r = new RectF(bx, by, bx + bw, by + bh);
        badgePaint.setColor(0xEE2A0A0A);
        canvas.drawRoundRect(r, 10f, 10f, badgePaint);

        hexStrokePaint.setColor(0xFFE53935);
        hexStrokePaint.setStrokeWidth(2.2f);
        canvas.drawRoundRect(r, 10f, 10f, hexStrokePaint);

        textPaint.setColor(0xFFFFCDD2);
        textPaint.setTextSize(20f);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        canvas.drawText("\u2605 ALLIED TURN IN PROGRESS \u2605", bx + bw * 0.5f, by + 31f, textPaint);
    }

    private void drawStrategicMap(Canvas canvas) {
        int cols = engine.scenario.cols;
        int rows = engine.scenario.rows;
        float pad = 36f;
        float availW = getWidth() - pad * 2;
        float availH = getHeight() - pad * 2 - 80f;
        float cellW = Math.min(availW / cols, availH / rows);
        float startX = (getWidth() - cellW * cols) * 0.5f;
        float startY = (getHeight() - cellW * rows) * 0.5f;

        badgePaint.setColor(0xFF1F2621);
        RectF board = new RectF(startX - 12f, startY - 42f, startX + cols * cellW + 12f, startY + rows * cellW + 16f);
        canvas.drawRoundRect(board, 12f, 12f, badgePaint);
        hexStrokePaint.setColor(0xFFC5A059);
        hexStrokePaint.setStrokeWidth(2f);
        canvas.drawRoundRect(board, 12f, 12f, hexStrokePaint);

        textPaint.setColor(0xFFFFD54F);
        textPaint.setTextSize(24f);
        canvas.drawText("STRATEGIC MAP - " + engine.scenario.meta.fullTitle + " (Tap any location to jump)",
                getWidth() * 0.5f, startY - 14f, textPaint);

        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                PgDatabase.HexCell cell = engine.scenario.grid[c][r];
                float cx = startX + c * cellW;
                float cy = startY + r * cellW;

                int color;
                switch (cell.terrainType) {
                    case 2: color = 0xFF1E4D7B; break; // Ocean
                    case 4: color = 0xFF3A7CA5; break; // River
                    case 8: case 12: color = 0xFF9E9E9E; break; // City/Fort
                    case 5: color = 0xFF6D4C41; break; // Mountain
                    case 10: case 13: color = 0xFF2E5A27; break; // Forest
                    case 11: color = 0xFFD4A359; break; // Desert
                    default: color = 0xFF5A7D3A; break; // Clear
                }
                badgePaint.setColor(color);
                canvas.drawRect(cx, cy, cx + cellW - 0.5f, cy + cellW - 0.5f, badgePaint);

                if (cell.isObjective) {
                    boolean axisOwned = engine.db.isAxisNation(cell.ownerNation, engine.scenario);
                    badgePaint.setColor(axisOwned ? 0xFFFFD700 : 0xFFFF3D00);
                    canvas.drawCircle(cx + cellW * 0.5f, cy + cellW * 0.5f, Math.max(4f, cellW * 0.45f), badgePaint);
                }
            }
        }

        for (PgDatabase.ActiveUnit u : engine.units) {
            if (u.strength <= 0) continue;
            if (!engine.isUnitVisibleToPlayer(u)) continue;
            float cx = startX + (u.col + 0.5f) * cellW;
            float cy = startY + (u.row + 0.5f) * cellW;
            badgePaint.setColor(u.isAxis ? 0xFFECEFF1 : 0xFFFF5252);
            canvas.drawCircle(cx, cy, Math.max(3f, cellW * 0.35f), badgePaint);
        }
    }

    private void buildHexPath(float x, float y) {
        hexPath.reset();
        hexPath.moveTo(x + 15f, y);
        hexPath.lineTo(x + 45f, y);
        hexPath.lineTo(x + 60f, y + 25f);
        hexPath.lineTo(x + 45f, y + 50f);
        hexPath.lineTo(x + 15f, y + 50f);
        hexPath.lineTo(x, y + 25f);
        hexPath.close();
    }
}
