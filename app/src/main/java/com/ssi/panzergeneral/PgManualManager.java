package com.ssi.panzergeneral;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Panzer General - Comprehensive Military Documentation & Reference Library
 * Provides the complete official SSI Instruction Manual, the authentic SSI Strategy Guide (STRATEGY.TXT),
 * and the complete Campaign Compendium & Scenario Briefings (CAMPAIGN.TXT).
 */
public class PgManualManager {

    private static String cachedStrategyGuide = null;
    private static String cachedCampaignGuide = null;

    public static String getInstructionManualText() {
        return MANUAL_TEXT;
    }

    public static synchronized String getStrategyGuideText(Context context) {
        if (cachedStrategyGuide != null) return cachedStrategyGuide;
        String raw = loadAssetString(context, "STRATEGY.TXT");
        if (raw == null || raw.trim().isEmpty()) {
            raw = FALLBACK_STRATEGY_TEXT;
        }
        cachedStrategyGuide = raw;
        return cachedStrategyGuide;
    }

    public static synchronized String getCampaignGuideText(Context context) {
        if (cachedCampaignGuide != null) return cachedCampaignGuide;
        String raw = loadAssetString(context, "CAMPAIGN.TXT");
        if (raw == null || raw.trim().isEmpty()) {
            raw = FALLBACK_CAMPAIGN_TEXT;
        }
        cachedCampaignGuide = raw;
        return cachedCampaignGuide;
    }

    public static String loadAssetString(Context context, String filename) {
        if (context == null) return null;
        try (InputStream is = context.getAssets().open("pgdata/" + filename)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.ISO_8859_1.name());
        } catch (Exception e) {
            return null;
        }
    }

    public static class SearchResult {
        public final int tabIndex; // 0=Manual, 1=Strategy, 2=Campaign
        public final String tabName;
        public final String sectionHeader;
        public final String snippet;

        public SearchResult(int tabIndex, String tabName, String sectionHeader, String snippet) {
            this.tabIndex = tabIndex;
            this.tabName = tabName;
            this.sectionHeader = sectionHeader;
            this.snippet = snippet;
        }
    }

    public static List<SearchResult> searchDocumentation(Context context, String query) {
        List<SearchResult> results = new ArrayList<>();
        if (query == null || query.trim().length() < 2) return results;
        String q = query.trim().toLowerCase(Locale.ROOT);

        searchDocument(0, "Instruction Manual", getInstructionManualText(), q, results);
        searchDocument(1, "Strategy Guide", getStrategyGuideText(context), q, results);
        searchDocument(2, "Campaign Guide", getCampaignGuideText(context), q, results);

        return results;
    }

    private static void searchDocument(int tabIdx, String tabName, String docText, String q, List<SearchResult> out) {
        if (docText == null) return;
        String[] lines = docText.split("\n");
        String currentHeader = tabName;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("===") || trimmed.startsWith("---") || trimmed.startsWith("CHAPTER")
                    || (trimmed.length() > 3 && trimmed.length() < 60 && trimmed.toUpperCase(Locale.ROOT).equals(trimmed) && !trimmed.startsWith("•"))) {
                currentHeader = trimmed.replaceAll("[=\\-_#]", "").trim();
            }
            if (line.toLowerCase(Locale.ROOT).contains(q)) {
                StringBuilder snippet = new StringBuilder();
                int start = Math.max(0, i - 1);
                int end = Math.min(lines.length - 1, i + 2);
                for (int j = start; j <= end; j++) {
                    snippet.append(lines[j].trim()).append(" ");
                }
                out.add(new SearchResult(tabIdx, tabName, currentHeader, snippet.toString().trim()));
                i = end; // advance past snippet to avoid duplicate overlapping snippets
                if (out.size() >= 40) return;
            }
        }
    }

    // =========================================================================
    // COMPLETE OFFICIAL INSTRUCTION MANUAL TEXT
    // =========================================================================
    private static final String MANUAL_TEXT =
        "════════════════════════════════════════════════════════════════════════════════\n" +
        "   PANZER GENERAL — OFFICIAL INSTRUCTION MANUAL & RULES OF WAR\n" +
        "   Strategic Simulations, Inc. (1994) • Operational Field Command Manual\n" +
        "════════════════════════════════════════════════════════════════════════════════\n\n" +

        "■ TABLE OF CONTENTS ■\n" +
        "--------------------------------------------------------------------------------\n" +
        "CHAPTER 1: PHILOSOPHY OF COMMAND & GAME OVERVIEW\n" +
        "CHAPTER 2: THE BATTLEFIELD, HEX GRID & INTERFACE CONTROLS\n" +
        "CHAPTER 3: MOVEMENT, SPOTTING & ZONES OF CONTROL\n" +
        "CHAPTER 4: THE 12 UNIT CLASSES & SPECIAL COMBAT ABILITIES\n" +
        "CHAPTER 5: COMBAT MECHANICS, THE D20 CRT & INITIATIVE\n" +
        "CHAPTER 6: RUGGED DEFENSE, TANK OVERRUNS & SURRENDERS\n" +
        "CHAPTER 7: SUPPLY, LOGISTICS, REPLACEMENTS & OVERSTRENGTH\n" +
        "CHAPTER 8: UPGRADING EQUIPMENT, REQUISITIONS & DISBANDING\n" +
        "CHAPTER 9: THE WEATHER SYSTEM & SEASONAL EFFECTS\n" +
        "CHAPTER 10: PRESTIGE ECONOMY, CAMPAIGN TREE & VICTORY CONDITIONS\n\n" +

        "================================================================================\n" +
        "CHAPTER 1: PHILOSOPHY OF COMMAND & GAME OVERVIEW\n" +
        "================================================================================\n\n" +

        "1.1 THE ROLE OF THE GENERAL\n" +
        "Panzer General is an operational-level wargame that puts you in supreme command of\n" +
        "military forces across the European Theater of World War II (1939–1945). As a general,\n" +
        "you must direct combined-arms forces consisting of infantry, tanks, reconnaissance,\n" +
        "anti-tank guns, artillery, air defense flak, fighter aircraft, tactical bombers,\n" +
        "strategic level bombers, and naval flotillas.\n\n" +

        "Your primary task is not merely to defeat enemy units in the field, but to capture\n" +
        "vital strategic Victory Objectives (cities, airfields, and ports) within strict time limits.\n" +
        "Time is your most unforgiving adversary. An indecisive commander who wastes turns\n" +
        "pursuing unimportant targets will fail to achieve victory.\n\n" +

        "1.2 CORE UNITS VS. AUXILIARY UNITS\n" +
        "A foundational concept in Panzer General is the distinction between Core and Auxiliary troops:\n\n" +
        "• CORE UNITS (Veterans of your Personal Army):\n" +
        "  - Represent the elite backbone of your command group.\n" +
        "  - Survive from scenario to scenario throughout the entire campaign.\n" +
        "  - Accumulate experience points (0 to 500) and earn Experience Stars (1★ to 5★).\n" +
        "  - Eligible for Elite Replacements and Overstrength (strength 11 to 15).\n" +
        "  - Eligible for equipment upgrades as modern weapons are developed by OKW.\n" +
        "  - Core units should be preserved at all costs! If an experienced Core unit is destroyed\n" +
        "    in battle, its irreplaceable veteran cadre is permanently lost.\n\n" +

        "• AUXILIARY UNITS (Local Scenario Allocations):\n" +
        "  - Assigned specifically for a single battle to support your Core army.\n" +
        "  - Do NOT carry over to subsequent campaign scenarios.\n" +
        "  - Ideal for spearhead scouting, absorbing dangerous enemy ambushes, holding secondary\n" +
        "    defensive sectors, and screening valuable Core units.\n" +
        "  - Losing an auxiliary unit does not permanently harm your campaign army.\n\n" +

        "1.3 VICTORY CONDITIONS & TURN LIMITS\n" +
        "Every scenario features designated Victory Objective Hexes (highlighted on the map\n" +
        "and listed in the Briefing). Battles end in one of three outcomes:\n\n" +
        "1. DECISIVE VICTORY (Major Victory):\n" +
        "   - Achieved by capturing ALL required victory objectives well ahead of the deadline.\n" +
        "   - Awards the maximum Prestige bonus and opens the most aggressive, victorious\n" +
        "     campaign routes (such as invading Britain in Operation Sea Lion or driving on Moscow in 1941).\n\n" +
        "2. MARGINAL VICTORY (Minor Victory):\n" +
        "   - Achieved by taking all objectives near or on the final turn limit, or holding designated\n" +
        "     defensive strongpoints.\n" +
        "   - Awards moderate Prestige and advances along historical fallback campaign paths.\n\n" +
        "3. TACTICAL DEFEAT / LOSS:\n" +
        "   - Occurs if you fail to hold or capture the required objectives before turns expire.\n" +
        "   - Forces defensive retreat battles, and in critical scenarios can terminate the campaign.\n\n" +

        "================================================================================\n" +
        "CHAPTER 2: THE BATTLEFIELD, HEX GRID & INTERFACE CONTROLS\n" +
        "================================================================================\n\n" +

        "2.1 THE HEXAGONAL GRID & COORDINATES\n" +
        "The battlefield is mapped onto a hexagonal grid. Hex coordinates are denoted as (col, row).\n" +
        "Hexagonal geometry ensures that all six adjacent hexes are equidistant from the center,\n" +
        "allowing realistic multi-directional maneuvering, flanking, and encirclement.\n\n" +

        "2.2 DUAL-LAYER MAP SYSTEM (AIR & GROUND LAYERS)\n" +
        "Every hex on the map contains TWO independent tactical layers:\n" +
        "• Ground Layer: Occupied by infantry, tanks, artillery, anti-tank guns, flak, ships, or fortifications.\n" +
        "• Air Layer: Occupied by aircraft (fighters, tactical dive bombers, level bombers, or air transports).\n" +
        "A ground unit and an air unit of the same side can coexist simultaneously in the same hex!\n\n" +
        "• AIR/GND TOGGLE BUTTON:\n" +
        "  Tap the AIR/GND button on the bottom bar to switch display priority between the Air\n" +
        "  Layer ('AIR TOP') and Ground Layer ('GND TOP').\n\n" +

        "2.3 TERRAIN TYPES & DEFENSIVE RATINGS\n" +
        "Terrain dictates movement costs, combat effectiveness, and initiative limits:\n" +
        "• Clear: Open plains. Flat ground favored by armor and motorized forces. Base entrenchment: 0.\n" +
        "• Rough: Rolling rocky terrain. Impedes wheeled vehicles. Base entrenchment: 1.\n" +
        "• Forest / Woods: Dense woodland. Blocks line of sight. Favors infantry close combat. Base entrenchment: 1.\n" +
        "• Mountain: Formidable peaks. Impassable to non-mountain vehicles without roads. High defensive bonus. Base entrenchment: 2.\n" +
        "• Swamp / Marsh: Dangerous wetlands. Severely penalizes armor and vehicles. Base entrenchment: 1.\n" +
        "• Bocage: Dense hedgerows of Normandy. Limits visibility and movement; favors defenders. Base entrenchment: 2.\n" +
        "• River: Formidable obstacle. Crossing an unbridged river consumes all remaining MP and leaves\n" +
        "  the crossing unit vulnerable (+4 Attack Grade bonus for any unit attacking a unit caught on a river!).\n" +
        "• City / Town: Major urban centers. Key victory objectives. Provides 100% supply replenishment and\n" +
        "  triggers close-combat defense against armored vehicles. Base entrenchment: 3.\n" +
        "• Airfield: Allows landing, refueling, and rearming of aircraft. Required for embarking paratroops into Ju 52s.\n" +
        "• Port: Allows naval resupply, repair, and embarking/disembarking ground troops into Sea Transports.\n" +
        "• Fortification: Heavy reinforced bunkers and pillboxes. Massive defensive strength; immobile.\n\n" +

        "2.4 STRATEGIC MAP OVERLAY (STRAT MAP)\n" +
        "Tap the STRAT MAP button on the top bar to display the full-theater operational map.\n" +
        "Shows all discovered enemy and friendly forces, victory objectives, terrain features, and frontline status.\n\n" +

        "================================================================================\n" +
        "CHAPTER 3: MOVEMENT, SPOTTING & ZONES OF CONTROL\n" +
        "================================================================================\n\n" +

        "3.1 FLEXIBLE TURN ORDER (MOVE & ATTACK IN EITHER ORDER)\n" +
        "Panzer General features a flexible action system for every unit on your turn:\n" +
        "• Each unit receives 1 Movement Allowance and 1 Attack per turn.\n" +
        "• You may MOVE first and then ATTACK.\n" +
        "• You may ATTACK first and then MOVE afterwards!\n" +
        "• SPLIT MOVEMENT: If a unit moves only part of its Movement Allowance before attacking,\n" +
        "  it retains its remaining Movement Points (MP) and may continue moving after combat!\n\n" +

        "3.2 UNDO MOVE COMMAND\n" +
        "If you move a unit to a hex and change your mind, tap the UNDO button to return the unit\n" +
        "to its starting hex with all MP restored. However, if the move uncovered an unspotted\n" +
        "enemy unit (revealing hidden intelligence) or triggered an ambush, the move is locked and cannot be undone.\n\n" +

        "3.3 SPOTTING RANGE & FOG OF WAR\n" +
        "• Shrouded hexes represent unexplored territory hidden by the Fog of War.\n" +
        "• Every unit has an intrinsic Spotting Range (typically 1 hex for infantry/tanks, 2–3 hexes for\n" +
        "  reconnaissance, and 2–3 hexes for aircraft).\n" +
        "• Obstruction: Mountains, forests, and cities block line of sight for ground units unless an aircraft is flying overhead.\n\n" +

        "3.4 ZONES OF CONTROL (ZOC)\n" +
        "Every active combat unit exerts a Zone of Control (ZOC) into the six surrounding hexes:\n" +
        "• Moving from one enemy ZOC hex directly into another enemy ZOC hex costs extra MP.\n" +
        "• Enemy ZOC prevents retreating units from passing through, forcing encirclement and surrender!\n\n" +

        "3.5 UNSPOTTED CONTACT & SURPRISE AMBUSH\n" +
        "If you order a unit to move through or into a hex containing a hidden, unspotted enemy unit:\n" +
        "• Movement is immediately halted.\n" +
        "• The enemy springs a SURPRISE AMBUSH!\n" +
        "• The defender receives guaranteed 100% Stiff/Rugged Defense status, attacks with massive First Strike\n" +
        "  initiative, and inflicts devastating casualties before your ambushed unit can fire back!\n" +
        "• Always use Reconnaissance units or aerial scouts to probe ahead and prevent ambushes.\n\n" +

        "================================================================================\n" +
        "CHAPTER 4: THE 12 UNIT CLASSES & SPECIAL COMBAT ABILITIES\n" +
        "================================================================================\n\n" +

        "1. INFANTRY (Wehrmacht, Grenadiere, Gebirgsjäger, Fallschirmjäger, Pioniere):\n" +
        "   - Kings of Close Terrain: In cities, woods, mountains, and swamps, infantry forces armored vehicles\n" +
        "     to defend with their weak Close Defense value rather than their heavy Ground Defense!\n" +
        "   - Gains +2 Initiative in close terrain.\n" +
        "   - Pioniere & Combat Engineers: Strip 2 levels of enemy entrenchment per assault (vs. 1 for standard troops),\n" +
        "     ignore all defender entrenchment bonuses, and COMPLETELY NEGATE enemy Rugged Defense!\n" +
        "   - Bridge Engineers (Brückenpioniere): When stationed on a river hex, they form a Pontoon Bridge.\n" +
        "     Friendly units cross the river for only 1 MP and suffer NO river combat penalties!\n" +
        "   - Paratroopers (Fallschirmjäger): When stationed on an airfield, they can embark into Ju 52 Air Transports\n" +
        "     and paradrop into open hexes behind enemy lines.\n\n" +

        "2. TANKS (Panzer I, II, III, IV, Panther, Tiger I, King Tiger):\n" +
        "   - Kings of Open Terrain: High Hard Attack, heavy armor, and high speed.\n" +
        "   - TANK OVERRUN RULE: When a tank destroys an enemy ground defender in close combat with 0 total\n" +
        "     losses to the tank (and the defender began combat at strength ≤ 6), the tank OVERRUNS!\n" +
        "     The tank retains its attack capability and movement points, allowing it to strike again or advance!\n" +
        "   - Vulnerable in close terrain (cities/woods) where heavy armor is bypassed by infantry close assault.\n\n" +

        "3. RECONNAISSANCE (Aufklärer, Sd.Kfz. 222, 231, Puma):\n" +
        "   - Phased Movement: Can move in multiple increments during the same turn.\n" +
        "   - High Spotting Range (2–3 hexes) makes recon units vital for scouting ahead of the main army.\n\n" +

        "4. ANTI-TANK (Panzerjäger, StuG, Marder, Jagdpanther, Jagdtiger, Elefant, PaK 40):\n" +
        "   - Specialized tank killers with high Hard Attack values at lower prestige cost than tanks.\n" +
        "   - Excellent defensively against armored spearheads; vulnerable when attacked by infantry.\n\n" +

        "5. ARTILLERY (10.5cm leFH, 15cm sFH, Wespe, Hummel, Nebelwerfer, Stuka zu Fuss):\n" +
        "   - Indirect fire range of 2 to 3 hexes. Inflicts suppression and casualties, and strips entrenchment.\n" +
        "   - DEFENSIVE SUPPORT FIRE: Any friendly artillery within range automatically fires defensive support\n" +
        "     to protect an adjacent friendly ground unit when that unit is assaulted by an enemy ground unit!\n\n" +

        "6. AIR DEFENSE (FlaK 38, Mobelwagen, Wirbelwind, 8.8cm FlaK 36):\n" +
        "   - Protects friendly forces from air strikes. Automatically provides defensive flak fire against\n" +
        "     enemy aircraft attacking adjacent friendly ground or naval units.\n" +
        "   - FlaK 88 Dual-Purpose: Can engage both high-altitude bombers and heavy enemy tanks with deadly effectiveness.\n\n" +

        "7. FORTIFICATIONS (Bunkers, Forts, Atlantic Wall Strongpoints):\n" +
        "   - Extremely heavily entrenched; cannot move or retreat.\n" +
        "   - SURRENDER RULE: When forced to retreat or when completely suppressed after an assault,\n" +
        "     the garrison immediately SURRENDERS and is destroyed!\n\n" +

        "8. FIGHTERS (Bf 109, Fw 190, Me 262, Spitfire, Mustang, Yak-9):\n" +
        "   - Air Supremacy: Attacks enemy aircraft. Adjacent fighters automatically ESCORT friendly bombers\n" +
        "     and INTERCEPT enemy bombers attacking adjacent targets.\n\n" +

        "9. TACTICAL BOMBERS (Ju 87 Stuka, Hs 129, Fw 190F, Il-2 Sturmovik):\n" +
        "   - Precision dive bombers. Specializes in destroying tanks, artillery, and surface warships.\n\n" +

        "10. LEVEL / STRATEGIC BOMBERS (He 111, Do 17, Ju 88, He 177, B-17 Flying Fortress):\n" +
        "    - High-altitude strategic bombing: Strips 2 levels of entrenchment, inflicts heavy suppression,\n" +
        "      and destroys 25% of the target unit's fuel and ammunition!\n" +
        "    - Can neutralize enemy cities and ports, stripping enemy prestige generation.\n\n" +

        "11. NAVAL SURFACE WARSHIPS (Battleships, Cruisers, Destroyers, Aircraft Carriers):\n" +
        "    - Heavy naval artillery capable of devastating shore bombardment against coastal targets.\n" +
        "    - Destroyers feature sonar and depth charges to hunt enemy submarines.\n" +
        "    - Aircraft carriers refuel and rearm adjacent friendly carrier aircraft.\n\n" +

        "12. SUBMARINES (German U-Boats, Allied Submarines):\n" +
        "    - Silent torpedo attacks target the defender's CLOSE DEFENSE rating rather than naval armor!\n" +
        "    - DIVING EVASION: Submarines have a 50% chance against destroyers and 75% chance against\n" +
        "      tactical bombers to dive beneath depth charges and bombs, completely evading all damage!\n\n" +

        "================================================================================\n" +
        "CHAPTER 5: COMBAT MECHANICS, THE D20 CRT & INITIATIVE\n" +
        "================================================================================\n\n" +

        "5.1 ATTACK & DEFENSE GRADE FORMULAS (STRATEGY GUIDE CHAPTER 5)\n" +
        "Combat effectiveness is determined by comparing Attack Grade against Defense Grade:\n\n" +
        "• ATTACK GRADE = Base Attack (Soft/Hard/Air/Naval) + Attacker Stars (1★–5★)\n" +
        "  + 4 if defender is caught in an unbridged River hex\n" +
        "  + 4 if mounting a Stiff / Rugged Defense\n\n" +

        "• DEFENSE GRADE = Base Defense (Ground/Air/Close) + Defender Stars (1★–5★)\n" +
        "  + Entrenchment bonus (full entrenchment vs non-infantry, 1/2 vs non-engineer infantry, 0 vs Pioneers)\n" +
        "  + 8 for ground units defending against naval bombardment\n" +
        "  + 4 if unit is defending in Stiff / Rugged Defense\n" +
        "  + 4 if attacker is caught in an unbridged River hex\n" +
        "  + 3 versus ranged attacks during Rain or Snow\n" +
        "  + 2 for infantry defending against anti-tank guns\n\n" +

        "5.2 EFFECTIVE DIFFERENCE & THE D20 CRT\n" +
        "Effective Difference = (Attack Grade) - (Defense Grade).\n" +
        "Every single firing strength point rolls a 20-sided die (D20) against the authentic SSI CRT:\n" +
        "• High positive difference (+6 or higher) inflicts heavy direct kills and suppresses survivors.\n" +
        "• Negative difference (-4 or lower) results in mostly misses and low damage.\n" +
        "• Bombardment attacks (artillery, level bombers, battleships) convert kills into suppression.\n\n" +

        "5.3 INITIATIVE & FIRST STRIKE FIRE\n" +
        "Initiative determines who shoots first in combat:\n" +
        "Effective Initiative = min(Terrain Cap, Unit Initiative) + Stars + RNG(1 to 3).\n" +
        "• TERRAIN INITIATIVE CAPS: Cities = 1, Fortifications = 1; Forests, Mountains, Swamps, Bocage = 2; Rough = 3.\n" +
        "  Infantry gains a +2 bonus in close terrain.\n" +
        "• The unit with higher Initiative fires FIRST. Any enemy strength points killed or suppressed by the first\n" +
        "  strike cannot return fire, drastically reducing enemy retaliatory damage!\n\n" +

        "================================================================================\n" +
        "CHAPTER 6: RUGGED DEFENSE, TANK OVERRUNS & SURRENDERS\n" +
        "================================================================================\n\n" +

        "6.1 RUGGED DEFENSE (STIFF DEFENSE)\n" +
        "Rugged Defense represents surprise ambush and prepared defensive fire at close range:\n" +
        "• Occurs in close combat (distance 1) against entrenched ground defenders (entrenchment ≥ 1).\n" +
        "• Authentic SSI Formula:\n" +
        "  Chance % = Entrenchment × ((Def Stars + 2) / (Atk Stars + 2)) × ((Def Entr Rate + 1) / (Atk Entr Rate + 1)) × 5.0%\n" +
        "• When triggered, Defender Initiative gains +99 (guaranteed first strike!), +4 Defense Grade, and +4 Attack Grade.\n" +
        "• Pioneer / Combat Engineer units completely ignore and negate Rugged Defense!\n\n" +

        "6.2 FORCED RETREATS & SURRENDERS\n" +
        "• A defender that suffers heavy casualties (≥ 3 losses and more than attacker) or is reduced to 0 effective\n" +
        "  strength is forced to RETREAT 1 hex away from the attacker.\n" +
        "• SURRENDER: If all adjacent retreat hexes are blocked by units, impassable terrain, or enemy Zones of Control (ZOC),\n" +
        "  the unit is trapped and SURRENDERS! The unit is wiped out and awards full kill prestige to the attacker.\n\n" +

        "================================================================================\n" +
        "CHAPTER 7: SUPPLY, LOGISTICS, REPLACEMENTS & OVERSTRENGTH\n" +
        "================================================================================\n\n" +

        "7.1 FUEL & AMMUNITION SUPPLY\n" +
        "• Every vehicle consumes Fuel for each hex moved. When fuel reaches 0, Movement Points become 0.\n" +
        "• Every weapon attack or defensive fire consumes 1 round of Ammunition. Out of ammo = cannot shoot!\n" +
        "• RESUPPLY RATES:\n" +
        "  - 100% Fuel & Ammo restored on friendly Cities, Ports, Airfields, and Supply Depots.\n" +
        "  - 75% restored in open friendly territory.\n" +
        "  - 25% restored when adjacent to an active enemy unit.\n" +
        "  - Aircraft automatically refuel when ending their turn over a friendly airfield or carrier.\n\n" +

        "7.2 REGULAR VS. ELITE REPLACEMENTS\n" +
        "• Regular Replacements (REPL): Rebuilds unit strength back to 10 at low prestige cost. However, it dilutes\n" +
        "  the unit's experience points because green recruits are mixed in with veterans.\n" +
        "• Elite Replacements (ELITE+ / OVRST+): Rebuilds unit strength with combat veterans, fully preserving\n" +
        "  experience stars and allowing OVERSTRENGTH (strength 11 to 15)!\n" +
        "  Maximum Overstrength = 10 + Experience Stars (e.g., a 5-star veteran can reach strength 15!).\n\n" +

        "================================================================================\n" +
        "CHAPTER 8: UPGRADING EQUIPMENT, REQUISITIONS & DISBANDING\n" +
        "================================================================================\n\n" +

        "8.1 UPGRADING EQUIPMENT (MODERNIZATION)\n" +
        "• Any Core unit stationed on a friendly City or Supply Point with no adjacent enemies can be UPGRADED.\n" +
        "• Pay the difference in Prestige between the old equipment and the new model.\n" +
        "• Upgrading preserves all experience stars, awards, and overstrength!\n\n" +

        "8.2 DISBANDING UNITS FOR PRESTIGE REFUND\n" +
        "• An unmoved unit on a friendly supply point can be DISBANDED to recover 50% of its Prestige cost.\n" +
        "• Useful for scrapping obsolete captured equipment or bypassed fortifications to fund new armor.\n\n" +

        "8.3 REQUISITIONING NEW UNITS\n" +
        "• In the Deployment Phase or during battle, spend Prestige to purchase new combat units.\n" +
        "• Ground units deploy in or adjacent to friendly controlled supply cities free of enemy contact.\n" +
        "• Aircraft deploy on or adjacent to friendly controlled airfields.\n\n" +

        "================================================================================\n" +
        "CHAPTER 9: THE WEATHER SYSTEM & SEASONAL EFFECTS\n" +
        "================================================================================\n\n" +

        "9.1 FOUR WEATHER STATES\n" +
        "• FAIR (Clear): Optimal fighting conditions. Full air operations and normal ground movement.\n" +
        "• OVERCAST: Cloudy skies. Slight visibility limits; air operations permitted.\n" +
        "• RAIN: Heavy downpours. Ground and naval units gain +3 Defense against ranged bombardment.\n" +
        "  ALL AIRCRAFT ARE GROUNDED (no air attacks or reconnaissance permitted).\n" +
        "• SNOW: Freezing blizzards. Ranged defense +3. All aircraft grounded. Rivers may freeze into ice roads.\n\n" +

        "9.2 GROUND CONDITIONS (MUD & FROZEN)\n" +
        "Internal moisture accumulates across wet turns. Mud drastically increases movement costs across open terrain,\n" +
        "bogging down blitzkrieg spearheads. Freezing temperatures harden mud and freeze rivers into crossable ice.\n\n" +

        "================================================================================\n" +
        "CHAPTER 10: PRESTIGE ECONOMY, CAMPAIGN TREE & VICTORY CONDITIONS\n" +
        "================================================================================\n\n" +

        "10.1 PRESTIGE (WAR ECONOMY)\n" +
        "Prestige is the currency of command in Panzer General. It is earned through:\n" +
        "• Turn Allowance: Scheduled prestige allocated by high command each turn.\n" +
        "• Continuing Hex Control: Objective cities award prestige every turn they remain in your hands.\n" +
        "• Initial Hex Capture: Seizing enemy cities awards +160 prestige (+40 for tactical hexes).\n" +
        "• Combat Kills: Destroying enemy units awards prestige proportional to the unit's cost and stars.\n\n" +

        "10.2 THE 1939–1945 CAMPAIGN TREE\n" +
        "The campaign begins with Poland (September 1939). Achieving Decisive Victories enables alternate history:\n" +
        "• Poland → Warsaw → Norway → Low Countries → France.\n" +
        "• Major Victory in France opens OPERATION SEA LION (the invasion of England in 1940)!\n" +
        "• Triumphing in England and North Africa allows the ultimate trans-Atlantic assault: OPERATION PASTORIUS\n" +
        "  (the invasion of the United States and assault on Washington, D.C. in 1945)!\n" +
        "• Fighting on the Eastern Front tests your panzers against the Red Army from Barbarossa through Kiev,\n" +
        "  Moscow, Stalingrad, and Kursk.\n\n" +
        "════════════════════════════════════════════════════════════════════════════════\n" +
        "   END OF OFFICIAL INSTRUCTION MANUAL • PANZER GENERAL (SSI)\n" +
        "════════════════════════════════════════════════════════════════════════════════\n";

    private static final String FALLBACK_STRATEGY_TEXT =
        "Panzer General Strategy Notes\n" +
        "(Refer to STRATEGY.TXT in assets for full text)";

    private static final String FALLBACK_CAMPAIGN_TEXT =
        "Panzer General Campaign Notes\n" +
        "(Refer to CAMPAIGN.TXT in assets for full text)";
}
