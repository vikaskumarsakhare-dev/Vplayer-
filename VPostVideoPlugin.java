package com.vplayer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;

/* One video player for the post cards. The page tells where the card is; the player sits on top of the card at that place.
   The page hides it while the finger is moving and asks for it again when everything has stopped. */
@CapacitorPlugin(name = "VPostVideo")
public class VPostVideoPlugin extends Plugin {
  private FrameLayout box;
  private TextureView tv;
  private Btn btn;
  private ExoPlayer pl;
  private String key = "";
  private String path = "";
  private long cs = 0, ce = 0;
  private boolean muted = true;
  private int vw = 0, vh = 0;
  private float radius = 0;

  /* the sound button, drawn by hand: a speaker, with a cross when it is off */
  private static class Btn extends View {
    boolean off = true;
    final Paint bg = new Paint(), fg = new Paint();

    Btn(Context c) {
      super(c);
      bg.setColor(Color.argb(150, 0, 0, 0));
      bg.setAntiAlias(true);
      fg.setColor(Color.WHITE);
      fg.setAntiAlias(true);
      fg.setStyle(Paint.Style.STROKE);
      fg.setStrokeWidth(5f);
    }

    @Override
    protected void onDraw(Canvas cv) {
      float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, u = w / 40f;
      cv.drawCircle(cx, cy, w / 2f, bg);
      Path p = new Path();
      p.moveTo(cx - 9 * u, cy - 4 * u);
      p.lineTo(cx - 4 * u, cy - 4 * u);
      p.lineTo(cx + 2 * u, cy - 9 * u);
      p.lineTo(cx + 2 * u, cy + 9 * u);
      p.lineTo(cx - 4 * u, cy + 4 * u);
      p.lineTo(cx - 9 * u, cy + 4 * u);
      p.close();
      Paint f = new Paint();
      f.setColor(Color.WHITE);
      f.setAntiAlias(true);
      cv.drawPath(p, f);
      if (off) {
        cv.drawLine(cx + 6 * u, cy - 5 * u, cx + 14 * u, cy + 5 * u, fg);
        cv.drawLine(cx + 14 * u, cy - 5 * u, cx + 6 * u, cy + 5 * u, fg);
      } else {
        cv.drawLine(cx + 7 * u, cy - 4 * u, cx + 7 * u, cy + 4 * u, fg);
        cv.drawLine(cx + 12 * u, cy - 8 * u, cx + 12 * u, cy + 8 * u, fg);
      }
    }
  }

  private float dens() {
    return getContext().getResources().getDisplayMetrics().density;
  }

  private void ensure() {
    if (box != null) return;
    Context c = getContext();
    ViewGroup parent = (ViewGroup) getBridge().getWebView().getParent();
    box = new FrameLayout(c);
    box.setVisibility(View.GONE);
    tv = new TextureView(c);
    box.addView(tv, new FrameLayout.LayoutParams(1, 1));
    btn = new Btn(c);
    int s = (int) (40 * dens());
    FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(s, s, Gravity.BOTTOM | Gravity.END);
    bl.setMargins(0, 0, (int) (10 * dens()), (int) (10 * dens()));
    box.addView(btn, bl);
    btn.setOnClickListener(
      new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          muted = !muted;
          apply();
          JSObject o = new JSObject();
          o.put("muted", muted);
          notifyListeners("mute", o);
        }
      }
    );
    box.setOutlineProvider(
      new ViewOutlineProvider() {
        @Override
        public void getOutline(View v, Outline o) {
          o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
        }
      }
    );
    box.setClipToOutline(true);
    parent.addView(box, new FrameLayout.LayoutParams(1, 1));
    DefaultRenderersFactory rf = new DefaultRenderersFactory(c).setEnableDecoderFallback(true);
    DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(800, 4000, 150, 300).build();
    pl = new ExoPlayer.Builder(c, rf).setLoadControl(lc).build();
    pl.setRepeatMode(Player.REPEAT_MODE_ONE);
    pl.setVideoTextureView(tv);
    pl.addListener(
      new Player.Listener() {
        @Override
        public void onVideoSizeChanged(VideoSize s) {
          vw = s.width;
          vh = s.height;
          fit();
        }

        @Override
        public void onPlayerError(PlaybackException e) {
          JSObject o = new JSObject();
          o.put("key", key);
          notifyListeners("fail", o);
        }
      }
    );
  }

  private void apply() {
    if (pl != null) pl.setVolume(muted ? 0f : 1f);
    if (btn != null) {
      btn.off = muted;
      btn.invalidate();
    }
  }

  /* the picture fills the card, cropped, the way the page's own picture does */
  private void fit() {
    if (box == null || vw <= 0 || vh <= 0) return;
    int bw = box.getWidth(), bh = box.getHeight();
    if (bw <= 0 || bh <= 0) return;
    float sc = Math.max(bw / (float) vw, bh / (float) vh);
    int tw = Math.round(vw * sc), th = Math.round(vh * sc);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(tw, th);
    tv.setLayoutParams(lp);
    tv.setTranslationX((bw - tw) / 2f);
    tv.setTranslationY((bh - th) * 0.25f);
  }

  /* key, path, x, y, w, h (page units), cl, ct, cr, cb (the part of the page where it may show), s, e (ms), muted, r */
  @PluginMethod
  public void show(final PluginCall call) {
    final String k = call.getString("key", "");
    final String pa = call.getString("path", "");
    final double x = call.getData().optDouble("x", 0), y = call.getData().optDouble("y", 0);
    final double w = call.getData().optDouble("w", 0), h = call.getData().optDouble("h", 0);
    final double cl = call.getData().optDouble("cl", 0), ct = call.getData().optDouble("ct", 0);
    final double cr = call.getData().optDouble("cr", 0), cb = call.getData().optDouble("cb", 0);
    final long s = call.getData().optLong("s", 0), e = call.getData().optLong("e", 0);
    final boolean mu = call.getBoolean("muted", true);
    final double r = call.getData().optDouble("r", 0);
    getActivity().runOnUiThread(
      new Runnable() {
        @Override
        public void run() {
          try {
            File f = new File(pa);
            if (!f.exists() || w < 20 || h < 20) {
              call.reject("no file");
              return;
            }
            ensure();
            float d = dens();
            View web = getBridge().getWebView();
            int ox = Math.round(web.getLeft() + (float) x * d), oy = Math.round(web.getTop() + (float) y * d);
            int bw = Math.round((float) w * d), bh = Math.round((float) h * d);
            Rect clip = new Rect(
              Math.round(web.getLeft() + (float) cl * d) - ox,
              Math.round(web.getTop() + (float) ct * d) - oy,
              Math.round(web.getLeft() + (float) cr * d) - ox,
              Math.round(web.getTop() + (float) cb * d) - oy
            );
            clip.intersect(0, 0, bw, bh);
            if (clip.width() < 10 || clip.height() < 10) {
              hideNow();
              call.resolve();
              return;
            }
            radius = (float) r * d;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
            if (lp.width != bw || lp.height != bh) {
              lp.width = bw;
              lp.height = bh;
              box.setLayoutParams(lp);
            }
            box.setTranslationX(ox);
            box.setTranslationY(oy);
            box.setClipBounds(clip);
            box.invalidateOutline();
            muted = mu;
            if (!k.equals(key) || !pa.equals(path) || s != cs || e != ce) {
              key = k;
              path = pa;
              cs = s;
              ce = e;
              vw = 0;
              vh = 0;
              MediaItem.Builder mib = new MediaItem.Builder().setUri(Uri.fromFile(f));
              if (e > s) {
                MediaItem.ClippingConfiguration.Builder cc = new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s).setEndPositionMs(e);
                mib.setClippingConfiguration(cc.build());
              } else if (s > 0) {
                MediaItem.ClippingConfiguration.Builder cc = new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s);
                mib.setClippingConfiguration(cc.build());
              }
              pl.setMediaItem(mib.build());
              pl.prepare();
            }
            apply();
            pl.setPlayWhenReady(true);
            box.setVisibility(View.VISIBLE);
            box.post(
              new Runnable() {
                @Override
                public void run() {
                  fit();
                }
              }
            );
            call.resolve();
          } catch (Throwable t) {
            call.reject("could not show");
          }
        }
      }
    );
  }

  private void hideNow() {
    if (box == null) return;
    box.setVisibility(View.GONE);
    if (pl != null) pl.setPlayWhenReady(false);
  }

  @PluginMethod
  public void hide(final PluginCall call) {
    getActivity().runOnUiThread(
      new Runnable() {
        @Override
        public void run() {
          hideNow();
          call.resolve();
        }
      }
    );
  }

  /* forgets the video, so a new one starts clean */
  @PluginMethod
  public void stop(final PluginCall call) {
    getActivity().runOnUiThread(
      new Runnable() {
        @Override
        public void run() {
          hideNow();
          key = "";
          path = "";
          if (pl != null) pl.clearMediaItems();
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void mute(final PluginCall call) {
    final boolean mu = call.getBoolean("muted", true);
    getActivity().runOnUiThread(
      new Runnable() {
        @Override
        public void run() {
          muted = mu;
          apply();
          call.resolve();
        }
      }
    );
  }

  @Override
  protected void handleOnPause() {
    if (box != null) hideNow();
  }

  @Override
  protected void handleOnDestroy() {
    try {
      if (pl != null) pl.release();
    } catch (Throwable t) {
    }
    pl = null;
  }
}
