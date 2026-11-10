package com.dhillon.calculatorvault;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Shows a hidden photo or plays a hidden video. Videos are decrypted on the fly,
 * so no unencrypted copy is ever written to storage.
 */
public class ViewerActivity extends Activity implements MediaController.MediaPlayerControl {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ProgressBar spinner;
    private MediaPlayer player;
    private MediaController controller;
    private SurfaceView surface;
    private VaultCrypto.EncryptedDataSource source;
    private boolean prepared;
    private VaultStore.Item item;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        VaultStore store = VaultStore.get(this);
        item = store.find(getIntent().getStringExtra("id"));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        if (item == null) {
            finish();
            return;
        }
        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        if (item.isVideo()) showVideo(store);
        else showImage(store);
    }

    private void showImage(VaultStore store) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(iv, 0, new FrameLayout.LayoutParams(-1, -1));
        iv.setOnClickListener(v -> finish());
        int max = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        new Thread(() -> {
            Bitmap bm = null;
            try {
                bm = VaultActivity.decodeImage(this, store, item, max / 2);
            } catch (Throwable ignored) {
            }
            final Bitmap result = bm;
            ui.post(() -> {
                if (isDestroyed()) return;
                spinner.setVisibility(View.GONE);
                if (result != null) iv.setImageBitmap(result);
                else error("This photo can't be shown");
            });
        }).start();
    }

    private void showVideo(VaultStore store) {
        surface = new SurfaceView(this);
        root.addView(surface, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        controller = new MediaController(this);
        controller.setMediaPlayer(this);
        controller.setAnchorView(root);
        root.setOnClickListener(v -> {
            if (prepared) {
                if (controller.isShowing()) controller.hide();
                else controller.show();
            }
        });
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                startPlayer(store, holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                releasePlayer();
            }
        });
    }

    private void startPlayer(VaultStore store, SurfaceHolder holder) {
        if (player != null) return;
        try {
            source = new VaultCrypto.EncryptedDataSource(this, store.fileFor(item));
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            player.setDataSource(source);
            player.setDisplay(holder);
            player.setScreenOnWhilePlaying(true);
            player.setOnPreparedListener(mp -> {
                prepared = true;
                spinner.setVisibility(View.GONE);
                fitVideo(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
                controller.show(3000);
            });
            player.setOnVideoSizeChangedListener((mp, w, h) -> fitVideo(w, h));
            player.setOnCompletionListener(mp -> controller.show(0));
            player.setOnErrorListener((mp, what, extra) -> {
                error("This video can't be played on this phone");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            error("This video can't be played");
        }
    }

    private void fitVideo(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || surface == null) return;
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) return;
        float scale = Math.min(w / (float) vw, h / (float) vh);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(vw * scale), Math.round(vh * scale), Gravity.CENTER);
        surface.setLayoutParams(lp);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (player != null && prepared) {
            root.post(() -> {
                if (player != null) fitVideo(player.getVideoWidth(), player.getVideoHeight());
            });
        }
    }

    private void error(String msg) {
        if (spinner != null) spinner.setVisibility(View.GONE);
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        root.addView(t, new FrameLayout.LayoutParams(-1, -1));
    }

    private void releasePlayer() {
        prepared = false;
        if (controller != null) controller.hide();
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (source != null) {
            try {
                source.close();
            } catch (Exception ignored) {
            }
            source = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing() && !isChangingConfigurations()) {
            // Leaving the app from the viewer locks the whole vault.
            VaultActivity.lockNow = true;
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }

    // ---- MediaController.MediaPlayerControl ----

    @Override
    public void start() {
        if (player != null && prepared) player.start();
    }

    @Override
    public void pause() {
        if (player != null && prepared) player.pause();
    }

    @Override
    public int getDuration() {
        return player != null && prepared ? player.getDuration() : 0;
    }

    @Override
    public int getCurrentPosition() {
        return player != null && prepared ? player.getCurrentPosition() : 0;
    }

    @Override
    public void seekTo(int pos) {
        if (player != null && prepared) player.seekTo(pos);
    }

    @Override
    public boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    @Override
    public int getBufferPercentage() {
        return 100;
    }

    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public boolean canSeekBackward() {
        return true;
    }

    @Override
    public boolean canSeekForward() {
        return true;
    }

    @Override
    public int getAudioSessionId() {
        return player != null ? player.getAudioSessionId() : 0;
    }
}
