package no.nixo.bluecodec;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** A local, deterministic avatar. Renaming a device never changes its colors or drawing. */
final class DeviceAvatar extends Drawable {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int background, ink, variant;

  DeviceAvatar(String id) {
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
    float hue = (digest[0] & 255) * 360f / 256;
    background =
        Color.HSVToColor(new float[] {hue, 0.24f + (digest[1] & 255) / 255f * 0.15f, 0.96f});
    ink = Color.HSVToColor(new float[] {hue, 0.48f, 0.30f});
    variant = digest[2] & 255;
  }

  public void draw(Canvas canvas) {
    canvas.save();
    canvas.translate(getBounds().left, getBounds().top);
    canvas.scale(getBounds().width() / 64f, getBounds().height() / 64f);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(background);
    canvas.drawRoundRect(0, 0, 64, 64, 22, 22, paint);
    paint.setColor(ink);
    paint.setStrokeWidth(3.8f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStyle(Paint.Style.STROKE);
    float left = 15 + (variant % 4), right = 64 - left, top = 14 + (variant % 3);
    canvas.drawArc(left, top, right, top + 34, 180, 180, false, paint);
    canvas.drawLine(left, 31, left, 43, paint);
    canvas.drawLine(right, 31, right, 43, paint);
    if ((variant & 4) != 0) {
      paint.setStrokeWidth(1.4f);
      canvas.drawArc(left + 5, top + 5, right - 5, top + 31, 205, 130, false, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    float radius = (variant & 8) != 0 ? 5 : 3;
    canvas.drawRoundRect(left - 2, 31, left + 7, 48, radius, radius, paint);
    canvas.drawRoundRect(right - 7, 31, right + 2, 48, radius, radius, paint);
    paint.setColor(background);
    canvas.drawRoundRect(left + 1, 35, left + 3, 43, 1, 1, paint);
    canvas.drawRoundRect(right - 3, 35, right - 1, 43, 1, 1, paint);
    paint.setColor(ink);
    paint.setStrokeWidth(2);
    int dots = 1 + (variant % 3);
    for (int i = 0; i < dots; i++)
      canvas.drawCircle(32 + (i - (dots - 1) / 2f) * 5, 47, 1.4f, paint);
    canvas.restore();
  }

  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
  }

  public void setColorFilter(ColorFilter filter) {
    paint.setColorFilter(filter);
  }

  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
