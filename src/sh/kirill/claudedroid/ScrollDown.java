package sh.kirill.claudedroid;

import android.view.View;
import android.widget.ScrollView;

/** d8 8.2.2 падает на анонимных классах — отдельный top-level Runnable для автоскролла. */
public class ScrollDown implements Runnable {
    private final ScrollView sv;

    public ScrollDown(ScrollView sv) {
        this.sv = sv;
    }

    @Override
    public void run() {
        sv.fullScroll(View.FOCUS_DOWN);
    }
}
