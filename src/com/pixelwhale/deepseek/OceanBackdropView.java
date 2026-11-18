package com.pixelwhale.deepseek;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** A quiet pixel-ocean backdrop for the header or welcome artwork. */
final class OceanBackdropView extends View {
    private final Paint paint = new Paint();
    private boolean light;

    OceanBackdropView(Context context) {
        super(context);
        paint.setAntiAlias(false);
    }

    void setLight(boolean enabled) {
        light = enabled;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        int left = light ? Color.rgb(233, 250, 251) : Color.rgb(17, 59, 87);
        int right = light ? Color.rgb(210, 239, 246) : Color.rgb(29, 104, 135);
        paint.setShader(new LinearGradient(0, 0, width, height * .75f,
                left, right, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        float pixel = Math.max(getResources().getDisplayMetrics().density * 4f,
                height / 35f);
        int columns = (int) Math.ceil(width / pixel);
        drawWave(canvas, columns, pixel, height, light ?
                Color.rgb(196, 230, 239) : Color.rgb(42, 126, 153), .79f, 2.4f);
        drawWave(canvas, columns, pixel, height, light ?
                Color.rgb(175, 221, 233) : Color.rgb(33, 111, 142), .91f, 1.4f);

        int bubble = light ? Color.rgb(146, 208, 224) : Color.rgb(111, 189, 207);
        drawBubble(canvas, width * .77f, height * .22f, pixel * 2f, bubble);
        drawBubble(canvas, width * .91f, height * .47f, pixel * 1.5f, bubble);
        drawBubble(canvas, width * .69f, height * .51f, pixel, bubble);
        drawSpark(canvas, width * .86f, height * .16f, pixel, light ?
                Color.rgb(245, 191, 111) : Color.rgb(245, 208, 133));
    }

    private void drawWave(Canvas canvas, int columns, float pixel, float height,
                          int color, float baseline, float amplitude) {
        paint.setColor(color);
        for (int column = 0; column < columns; column++) {
            float offset = (float) Math.sin(column * .32f + (baseline * 7f));
            float top = baseline * height + offset * amplitude * pixel;
            top = Math.round(top / pixel) * pixel;
            canvas.drawRect(column * pixel, top,
                    Math.min(getWidth(), (column + 1) * pixel), height, paint);
        }
    }

    private void drawBubble(Canvas canvas, float centerX, float centerY,
                            float size, int color) {
        float unit = Math.max(2f, size / 3f);
        float x = Math.round(centerX / unit) * unit;
        float y = Math.round(centerY / unit) * unit;
        paint.setColor(color);
        canvas.drawRect(x - unit, y - 2 * unit, x + unit, y - unit, paint);
        canvas.drawRect(x - 2 * unit, y - unit, x - unit, y + unit, paint);
        canvas.drawRect(x + unit, y - unit, x + 2 * unit, y + unit, paint);
        canvas.drawRect(x - unit, y + unit, x + unit, y + 2 * unit, paint);
    }

    private void drawSpark(Canvas canvas, float x, float y, float pixel, int color) {
        float unit = Math.max(2f, pixel * .7f);
        float left = Math.round(x / unit) * unit;
        float top = Math.round(y / unit) * unit;
        paint.setColor(color);
        canvas.drawRect(left + unit, top, left + 2 * unit, top + 3 * unit, paint);
        canvas.drawRect(left, top + unit, left + 3 * unit, top + 2 * unit, paint);
    }
}
