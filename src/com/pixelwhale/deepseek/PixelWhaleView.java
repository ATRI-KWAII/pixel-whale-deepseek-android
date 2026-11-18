package com.pixelwhale.deepseek;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** A tiny pixel illustration drawn in code so it stays sharp at every screen size. */
final class PixelWhaleView extends View {
    private static final String[] ART = {
            "................................",
            ".....................Y..........",
            "....................YYY.........",
            ".....................Y..........",
            ".................L...D..........",
            "................LLL.DDD.........",
            ".................L...D..........",
            "...................L............",
            ".................LLLL...........",
            ".................L..L...........",
            "......DD........DDDDDDDDDDD.....",
            ".....DCCD.....DDCCCCCCCCCCDD....",
            "....DCCCCD...DCCCCCCCCCCCCCDD...",
            "...DCCCCCCD.DCCCCCCCCCCCCCCCCD..",
            "....DCCCCCCDDCCCCCCCCCCCCCCCCD..",
            ".....DDCCCCCCCCCCCCCCCCCCCCCCCD..",
            ".......DDCCCCCCCCCCCCCCCDCCCCCD..",
            "........DCCCCCCCCCCCCCCCCCCCCCD..",
            "........DCCCCCCCCCCCCCCCCCCPCCD..",
            "........DCCCCCCCCCCBBBBBBBBBBDD..",
            ".........DCCCCCBBBBBBBBBBBBBD...",
            "..........DDDDDBBBBBBBBBBBBD....",
            "...............DDDDDDDDDDDDD.....",
            "................................"
    };
    private final Paint paint = new Paint();

    PixelWhaleView(Context context) { super(context); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cell = Math.min(getWidth() / 32f, getHeight() / 24f);
        float left = (getWidth() - cell * 32) / 2f;
        float top = (getHeight() - cell * 24) / 2f;
        for (int y = 0; y < ART.length; y++) {
            String row = ART[y];
            for (int x = 0; x < row.length(); x++) {
                int color;
                switch (row.charAt(x)) {
                    case 'D': color = Color.rgb(24, 62, 89); break;
                    case 'C': color = Color.rgb(79, 168, 200); break;
                    case 'B': color = Color.rgb(228, 248, 246); break;
                    case 'P': color = Color.rgb(244, 151, 160); break;
                    case 'L': color = Color.rgb(137, 211, 226); break;
                    case 'Y': color = Color.rgb(255, 208, 104); break;
                    default: continue;
                }
                paint.setColor(color);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRect(left + x * cell, top + y * cell,
                        left + (x + 1) * cell, top + (y + 1) * cell, paint);
            }
        }
        // Eye and two small bubbles sit above the whale's pixel grid.
        paint.setColor(Color.rgb(24, 62, 89));
        canvas.drawRect(left + 23 * cell, top + 14 * cell,
                left + 24 * cell, top + 15 * cell, paint);
        paint.setColor(Color.rgb(137, 211, 226));
        canvas.drawRect(left + 3 * cell, top + 5 * cell,
                left + 5 * cell, top + 7 * cell, paint);
        canvas.drawRect(left + 7 * cell, top + 2 * cell,
                left + 8 * cell, top + 3 * cell, paint);
    }
}
