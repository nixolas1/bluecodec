package no.nixo.bluecodec;

import android.content.Context;
import android.view.View;
import androidx.viewpager.widget.ViewPager;

/** A native swipe pager sized to the selected device rather than the tallest page. */
final class DevicePager extends ViewPager {
  DevicePager(Context context) {
    super(context);
  }

  @Override
  protected void onMeasure(int width, int height) {
    int pageHeight = 0;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (Integer.valueOf(getCurrentItem()).equals(child.getTag())) {
        child.measure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(width), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        pageHeight = child.getMeasuredHeight();
      }
    }
    super.onMeasure(
        width,
        pageHeight > 0 ? MeasureSpec.makeMeasureSpec(pageHeight, MeasureSpec.EXACTLY) : height);
  }
}
