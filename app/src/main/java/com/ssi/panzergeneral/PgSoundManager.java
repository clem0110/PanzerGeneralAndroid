package com.ssi.panzergeneral;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class PgSoundManager {
    private final Context context;
    private final SoundPool soundPool;
    private final Map<String, Integer> soundIds = new HashMap<>();
    private MediaPlayer musicPlayer;
    private MediaPlayer voicePlayer;
    private boolean soundEnabled = true;
    private boolean musicEnabled = true;
    private int currentTrack = 0;

    public PgSoundManager(Context context) {
        this.context = context;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        this.soundPool = new SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(attrs)
                .build();
        preloadSounds();
    }

    private void preloadSounds() {
        String[] sfx = {
            "001_WAR.WAV", "002_WAR.WAV", "003_WAR.WAV", "004_WAR.WAV",
            "005_WAR.WAV", "006_WAR.WAV", "007_WAR.WAV", "008_WAR.WAV",
            "009_WAR.WAV", "011_WAR.WAV", "012_WAR.WAV", "013_WAR.WAV",
            "014_WAR.WAV", "015_WAR.WAV", "016_WAR.WAV",
            "1_VOC.WAV", "3_VOC.WAV", "10_VOC.WAV", "11_VOC.WAV",
            "12_VOC.WAV", "13_VOC.WAV", "14_VOC.WAV", "15_VOC.WAV", "20_VOC.WAV", "100_VOC.WAV"
        };
        for (String name : sfx) {
            try {
                AssetFileDescriptor afd = context.getAssets().openFd("pgdata/sound/" + name);
                int id = soundPool.load(afd, 1);
                soundIds.put(name, id);
                afd.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void playClick() {
        playSfx("100_VOC.WAV", 0.7f);
    }

    public void playSelect() {
        playSfx("1_VOC.WAV", 0.8f);
    }

    public void playMoveSound(PgDatabase.UnitDef def) {
        if (def == null) {
            playSfx("10_VOC.WAV", 0.85f);
            return;
        }
        if (def.isAir()) {
            playSfx("14_VOC.WAV", 0.9f);
        } else if (def.isNaval()) {
            playSfx("15_VOC.WAV", 0.9f);
        } else if (def.unitClass == PgDatabase.CLASS_TANK || def.unitClass == PgDatabase.CLASS_RECON) {
            playSfx("11_VOC.WAV", 0.9f);
        } else if (def.unitClass == PgDatabase.CLASS_INFANTRY) {
            playSfx("10_VOC.WAV", 0.9f);
        } else {
            playSfx("12_VOC.WAV", 0.9f);
        }
    }

    public void playCombatSound(PgDatabase.UnitDef attacker) {
        if (attacker == null) {
            playSfx("001_WAR.WAV", 1.0f);
            return;
        }
        switch (attacker.unitClass) {
            case PgDatabase.CLASS_INFANTRY:
                playSfx("001_WAR.WAV", 1.0f);
                break;
            case PgDatabase.CLASS_TANK:
            case PgDatabase.CLASS_ANTI_TANK:
                playSfx("003_WAR.WAV", 1.0f);
                break;
            case PgDatabase.CLASS_ARTILLERY:
            case PgDatabase.CLASS_FORT:
                playSfx("005_WAR.WAV", 1.0f);
                break;
            case PgDatabase.CLASS_FIGHTER:
                playSfx("008_WAR.WAV", 1.0f);
                break;
            case PgDatabase.CLASS_TAC_BOMBER:
            case PgDatabase.CLASS_LEVEL_BOMBER:
                playSfx("009_WAR.WAV", 1.0f);
                break;
            case PgDatabase.CLASS_SUBMARINE:
            case PgDatabase.CLASS_DESTROYER:
            case PgDatabase.CLASS_CAPITAL_SHIP:
                playSfx("012_WAR.WAV", 1.0f);
                break;
            default:
                playSfx("002_WAR.WAV", 1.0f);
                break;
        }
    }

    public void playExplosion() {
        playSfx("006_WAR.WAV", 1.0f);
    }

    private void playSfx(String key, float vol) {
        if (!soundEnabled) return;
        Integer id = soundIds.get(key);
        if (id != null) {
            soundPool.play(id, vol, vol, 1, 0, 1.0f);
        }
    }

    public void playVoiceBriefing(int scenNumber) {
        if (!soundEnabled) return;
        stopVoice();
        int clipIdx = ((scenNumber - 1) % 15) + 1;
        String path = String.format(Locale.ROOT, "pgdata/voice/PANZ%04d.WAV", clipIdx);
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(path);
            voicePlayer = new MediaPlayer();
            voicePlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            voicePlayer.prepare();
            voicePlayer.setVolume(0.95f, 0.95f);
            voicePlayer.start();
        } catch ( Exception ignored) {
        }
    }

    public void stopVoice() {
        if (voicePlayer != null) {
            try {
                if (voicePlayer.isPlaying()) voicePlayer.stop();
                voicePlayer.release();
            } catch (Exception ignored) {
            }
            voicePlayer = null;
        }
    }

    public void startMusic(int trackIndex) {
        if (!musicEnabled) return;
        currentTrack = trackIndex % 6;
        stopMusic();
        String path = "pgdata/music/TRACK" + currentTrack + ".MID";
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(path);
            musicPlayer = new MediaPlayer();
            musicPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            musicPlayer.setLooping(true);
            musicPlayer.setVolume(0.45f, 0.45f);
            musicPlayer.prepare();
            musicPlayer.start();
        } catch (Exception ignored) {
        }
    }

    public void stopMusic() {
        if (musicPlayer != null) {
            try {
                if (musicPlayer.isPlaying()) musicPlayer.stop();
                musicPlayer.release();
            } catch (Exception ignored) {
            }
            musicPlayer = null;
        }
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public void setSoundEnabled(boolean enabled) {
        this.soundEnabled = enabled;
        if (!enabled) stopVoice();
    }

    public boolean isMusicEnabled() {
        return musicEnabled;
    }

    public void setMusicEnabled(boolean enabled) {
        this.musicEnabled = enabled;
        if (enabled) {
            startMusic(currentTrack);
        } else {
            stopMusic();
        }
    }

    public void release() {
        stopVoice();
        stopMusic();
        soundPool.release();
    }
}
