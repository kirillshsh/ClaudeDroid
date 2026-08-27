package sh.kirill.claudedroid;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

/** Минимальный разбор ANSI SGR: цвет текста/фона и жирный. Остальные CSI просто выкидываем. */
public class Ansi {

    /** Стандартные 16 ANSI-цветов. Основной путь всё равно truecolor — Claude Code шлёт свои RGB. */
    private static final int[] BASIC = {
            0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510,
            0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5,
            0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543,
            0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFFFFFFF
    };
    /** Дефолтные цвета зависят от темы приложения — MainActivity выставляет их в onCreate. */
    public static int defaultFg = 0xFFEDEDED;
    public static int defaultBg = 0xFF000000;

    /** Этих символов нет ни в шрифте, ни в системном фолбэке — рисуются пустыми квадратами. */
    private static String fixGlyphs(String s) {
        return s.replace('\u23F5', '\u25B6').replace('\u23F4', '\u25C0')
                .replace('\u23F8', '\u2016').replace('\u23FA', '\u25CF');
    }

    public static CharSequence parse(String raw) {
        String s = fixGlyphs(raw);
        SpannableStringBuilder out = new SpannableStringBuilder();
        // sentinel «нет цвета» = 0: -1 нельзя, это 0xFFFFFFFF — чистый белый (текст юзера)
        int fg = 0, bg = 0;
        boolean bold = false, inverse = false;
        int segStart = 0;
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != 27) {
                out.append(c);
                i++;
                continue;
            }
            // нашли escape: закрываем текущий сегмент прежним стилем
            applySpans(out, segStart, out.length(), fg, bg, bold, inverse);
            segStart = out.length();
            int j = i + 1;
            if (j < n && s.charAt(j) == ']') {
                // OSC (гиперссылки, заголовок) — скипаем до BEL или ST (ESC \)
                int k = j + 1;
                while (k < n && s.charAt(k) != 7 && !(s.charAt(k) == 27 && k + 1 < n && s.charAt(k + 1) == '\\')) k++;
                i = k < n ? (s.charAt(k) == 7 ? k + 1 : k + 2) : n;
                continue;
            }
            if (j < n && s.charAt(j) == '[') {
                int k = j + 1;
                while (k < n && "0123456789;:?".indexOf(s.charAt(k)) >= 0) k++;
                if (k < n) {
                    char fin = s.charAt(k);
                    if (fin == 'm') {
                        String body = s.substring(j + 1, k);
                        int[] st = {fg, bg, bold ? 1 : 0, inverse ? 1 : 0};
                        applySgr(body, st);
                        fg = st[0];
                        bg = st[1];
                        bold = st[2] != 0;
                        inverse = st[3] != 0;
                    }
                    i = k + 1;
                    continue;
                }
            }
            i = j + 1; // непонятная последовательность — пропускаем
        }
        applySpans(out, segStart, out.length(), fg, bg, bold, inverse);
        return out;
    }

    private static void applySgr(String body, int[] st) {
        if (body.length() == 0) {
            st[0] = 0; st[1] = 0; st[2] = 0; st[3] = 0;
            return;
        }
        String[] parts = body.split(";");
        for (int p = 0; p < parts.length; p++) {
            int v;
            try {
                v = parts[p].length() == 0 ? 0 : Integer.parseInt(parts[p]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (v == 0) { st[0] = 0; st[1] = 0; st[2] = 0; st[3] = 0; }
            else if (v == 1) st[2] = 1;
            else if (v == 22) st[2] = 0;
            else if (v == 7) st[3] = 1;
            else if (v == 27) st[3] = 0;
            else if (v >= 30 && v <= 37) st[0] = BASIC[v - 30];
            else if (v == 39) st[0] = 0;
            else if (v >= 40 && v <= 47) st[1] = BASIC[v - 40];
            else if (v == 49) st[1] = 0;
            else if (v >= 90 && v <= 97) st[0] = BASIC[v - 90 + 8];
            else if (v >= 100 && v <= 107) st[1] = BASIC[v - 100 + 8];
            else if ((v == 38 || v == 48) && p + 1 < parts.length) {
                int mode = Integer.parseInt(parts[p + 1]);
                int col = 0;
                if (mode == 5 && p + 2 < parts.length) {
                    col = xterm(Integer.parseInt(parts[p + 2]));
                    p += 2;
                } else if (mode == 2 && p + 4 < parts.length) {
                    col = Color.rgb(Integer.parseInt(parts[p + 2]),
                            Integer.parseInt(parts[p + 3]), Integer.parseInt(parts[p + 4]));
                    p += 4;
                }
                if (col != 0) st[v == 38 ? 0 : 1] = col;
            }
        }
    }

    private static int xterm(int i) {
        if (i < 16) return BASIC[i];
        if (i < 232) {
            int c = i - 16;
            return Color.rgb(step(c / 36), step((c / 6) % 6), step(c % 6));
        }
        int g = 8 + (i - 232) * 10;
        return Color.rgb(g, g, g);
    }

    private static int step(int x) {
        return x == 0 ? 0 : 55 + 40 * x;
    }

    private static void applySpans(SpannableStringBuilder out, int from, int to,
                                   int fg, int bg, boolean bold, boolean inverse) {
        if (to <= from) return;
        int f = fg == 0 ? defaultFg : fg;
        int b = bg;
        if (inverse) {
            int t = f;
            f = b == 0 ? defaultBg : b;
            b = t;
        }
        out.setSpan(new ForegroundColorSpan(f), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (b != 0) out.setSpan(new BackgroundColorSpan(b), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) out.setSpan(new StyleSpan(Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
