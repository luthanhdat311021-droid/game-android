package com.shadowduel.arena.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.game.render.Camera;
import com.shadowduel.arena.game.render.StageRenderer;
import com.shadowduel.arena.game.sim.GameMap;

import java.util.HashMap;
import java.util.Map;

/** Thumbnail of a whole arena rendered with the in-game stage renderer (cached per size). */
public class MapPreviewView extends View {
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private String mapId = "shadow_temple";
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public MapPreviewView(Context c) {
        this(c, null);
    }

    public MapPreviewView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setMap(String id) {
        mapId = id;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        String key = mapId + w + "x" + h;
        Bitmap b = CACHE.get(key);
        if (b == null) {
            b = render(getContext(), mapId, w, h);
            CACHE.put(key, b);
        }
        c.drawBitmap(b, 0, 0, paint);
    }

    static Bitmap render(Context ctx, String id, int w, int h) {
        GameMap map = GameAssets.map(ctx, id);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        StageRenderer stage = new StageRenderer(map);
        Camera cam = new Camera();
        cam.resize(w, h);
        // zoom out until the whole map width fits
        cam.targetZoom = cam.zoom = (float) (Camera.BASE_VIEW_H * w / (map.pixelW * h));
        cam.update((float) map.pixelW / 2, (float) (map.pixelH * 0.62), map, 0.016f);
        stage.drawBackground(c, cam, 1f);
        c.save();
        cam.apply(c);
        stage.drawProps(c, cam, 1f);
        stage.drawTiles(c, cam, 1f);
        stage.drawMovers(c);
        c.restore();
        return b;
    }
}
