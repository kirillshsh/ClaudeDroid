package sh.kirill.claudedroid;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Claude Code живёт в tmux, а ввод идёт обычным полем Android — терминальная клавиатура не нужна.
 *  UI перестраивается по содержимому пейна: «esc to interrupt» → кнопка-стоп, «❯» → чипы меню.
 *  Чаты = tmux-сессии поверх транскриптов claude, список — отдельный экран слева. */
public class MainActivity extends Activity implements View.OnClickListener, Handler.Callback,
        TextWatcher, View.OnScrollChangeListener, View.OnLayoutChangeListener,
        View.OnLongClickListener, View.OnTouchListener {

    // палитра резолвится в onCreate: AMOLED-чёрная или бумажно-светлая, по системной теме;
    // нейтрали тёплые (R≥G≥B), один акцент — терракота Anthropic
    private int BG, SURFACE, CHIP, BORDER, TEXT, DIM, ACCENT, ACCENT2, GREEN;
    private boolean light;

    private static final int ID_SEND = 1, ID_ENTER = 2, ID_ESC = 3, ID_CTRLC = 4,
            ID_TAB = 5, ID_BTAB = 6, ID_UP = 7, ID_DOWN = 8, ID_PLUS = 9,
            ID_MORE = 11, ID_JUMP = 12, ID_MENU = 13, ID_TUNE = 14, ID_DIM = 15,
            ID_NEWCHAT = 16, ID_TITLE = 17, ID_CHATROW = 18, ID_SLASHROW = 19,
            ID_MODELROW = 20, ID_EFFORTCHIP = 21, ID_RESTART = 22;
    private static final int REQ_PICK = 1;

    private static final Pattern EFFORT_RE = Pattern.compile("([a-z]+)\\s+·\\s+/effort");

    /** Встроенные команды Claude Code; скиллы с телефона дольются сканом. */
    private static final String[][] BUILTIN = {
            {"clear", "новый диалог, очистить контекст"},
            {"compact", "сжать контекст"},
            {"resume", "открыть прошлую сессию"},
            {"model", "выбрать модель"},
            {"effort", "уровень усилий"},
            {"fast", "быстрый режим вкл/выкл"},
            {"status", "модель, аккаунт, версия"},
            {"usage", "лимиты подписки"},
            {"cost", "расход за сессию"},
            {"context", "что занимает контекст"},
            {"config", "настройки"},
            {"permissions", "права инструментов"},
            {"mcp", "MCP-серверы"},
            {"memory", "память (CLAUDE.md)"},
            {"init", "создать CLAUDE.md"},
            {"agents", "субагенты"},
            {"todos", "список задач"},
            {"export", "экспорт диалога"},
            {"doctor", "диагностика Claude Code"},
            {"help", "справка"},
            {"rewind", "откатить диалог"},
            {"hooks", "хуки"},
            {"output-style", "стиль ответов"},
            {"statusline", "статусная строка"},
            {"bug", "фидбек Anthropic"},
            {"login", "сменить аккаунт"},
            {"release-notes", "что нового"},
            {"add-dir", "добавить папку"},
            {"privacy-settings", "приватность"},
            {"exit", "выйти из claude"},
    };
    private static final String[][] MODELS = {
            {"default", "Default", "рекомендованная (Opus, 1M контекст)"},
            {"opus", "Opus", "основная для сложных задач"},
            {"fable", "Fable", "самая умная, для тяжёлых задач"},
            {"sonnet", "Sonnet", "быстрая для рутины"},
            {"haiku", "Haiku", "мгновенные ответы"},
    };
    private static final String[] EFFORTS = {"low", "medium", "high", "xhigh", "max", "ultracode"};

    private FrameLayout rootFrame;
    private TextView term, chatTitle, deadT, deadS;
    private ScrollView scroll, chatScroll;
    private EditText input;
    private View dim;
    private ImageButton sendBtn, jumpBtn, moreBtn;
    private LinearLayout chipsAuto, drawer, sheet, chatListBox, slashPanel, slashBox, effortRow,
            deadBox;
    private HorizontalScrollView chipsFull;
    private ScrollView slashScroll;
    private TmuxThread tmux;
    private ScrollDown scrollDown;
    private View scrim;
    private Handler handler;
    private Typeface mono;
    private SharedPreferences prefs;
    private boolean busy = false, menu = false, manualKeys = false, deadShown = false;
    private boolean drawerOpen = false, sheetOpen = false;
    private boolean atBottom = true;
    private int measuredW = 0, maxTermH = 0, lastScrollH = 0, drawerW;
    private String curSession, curUuid, curTitle, curEffort = "";
    private List<ChatInfo> chats;
    private List<String> skills = new ArrayList<String>();
    // edge-swipe экрана чатов
    private float edgeDownX = -1, edgeDownY;
    private boolean edgeDrag = false;
    // свайп-влево по строке чата = завершить сессию
    private float rowDownX, rowDownY;
    private boolean rowDrag = false;
    private boolean rowArmed = false; // утянуто за порог — отпускание завершит сессию

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        if (light) {
            BG = 0xFFFAF9F5; SURFACE = 0xFFFFFFFF; CHIP = 0xFFF0EEE6; BORDER = 0xFFE0DCD1;
            TEXT = 0xFF141413; DIM = 0xFF6F6B60; ACCENT = 0xFFC96442; ACCENT2 = 0xFFA84E2F;
            GREEN = 0xFF3E8E5A;
        } else {
            // поверхности — нейтральный тёмно-серый без тёплого подтона (пожелание юзера)
            BG = 0xFF000000; SURFACE = 0xFF161616; CHIP = 0xFF1F1F1F; BORDER = 0xFF2C2C2C;
            TEXT = 0xFFE8E6DC; DIM = 0xFF98968E; ACCENT = 0xFFD97757; ACCENT2 = 0xFFC96442;
            GREEN = 0xFF6FBF8B;
        }
        Ansi.defaultFg = TEXT;
        Ansi.defaultBg = BG;
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        manualKeys = prefs.getBoolean("keys", false);
        curSession = prefs.getString("sess", null);
        curUuid = prefs.getString("uuid", null);
        curTitle = prefs.getString("title", null);
        if (curUuid != null && curUuid.length() == 0) curUuid = null;

        mono = Typeface.MONOSPACE;
        try {
            mono = Typeface.createFromAsset(getAssets(), "mono.ttf");
        } catch (Exception ignored) {
        }

        // системный промпт для claude на телефоне: из assets в filesDir, дальше его разложит root-шелл
        unpack("CLAUDE.md");

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (light) {
            WindowInsetsController ic = getWindow().getInsetsController();
            int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (ic != null) ic.setSystemBarsAppearance(bars, bars);
        }

        drawerW = getResources().getDisplayMetrics().widthPixels; // чаты — отдельный экран

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(topBar(), new LinearLayout.LayoutParams(-1, -2));
        content.addView(termFrame(), new LinearLayout.LayoutParams(-1, 0, 1f));
        content.addView(bottomBox(), new LinearLayout.LayoutParams(-1, -2));

        dim = new View(this);
        dim.setId(ID_DIM);
        dim.setBackgroundColor(0x99000000);
        dim.setAlpha(0f);
        dim.setOnClickListener(this);
        dim.setClickable(false); // после setOnClickListener — тот включает clickable обратно
        dim.setFocusable(false);

        buildDrawer();
        buildSheet();

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(BG);
        rootFrame.setFitsSystemWindows(true);
        rootFrame.addView(content, new FrameLayout.LayoutParams(-1, -1));
        rootFrame.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        rootFrame.addView(drawer, new FrameLayout.LayoutParams(drawerW, -1, Gravity.START));
        FrameLayout.LayoutParams shlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        rootFrame.addView(sheet, shlp);
        setContentView(rootFrame);

        drawer.setTranslationX(-drawerW);
        sheet.setTranslationY(dp(600));

        applyChips();
        updateSend();
        updateTitle();

        input.requestFocus();
        handler = new Handler(Looper.getMainLooper(), this);
        boolean exist = prefs.getBoolean("exist", true);
        tmux = new TmuxThread(handler, curSession, curUuid, exist);
        tmux.start();
    }

    // ---------- сборка UI ----------

    /** Шапка: шторка, заголовок чата, модель, новый чат. */
    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(2));

        bar.addView(iconBtn(ID_MENU, R.drawable.ic_menu, 40, DIM));

        chatTitle = new TextView(this);
        chatTitle.setId(ID_TITLE);
        chatTitle.setOnClickListener(this);
        chatTitle.setTextColor(TEXT);
        chatTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        chatTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chatTitle.setMaxLines(1);
        chatTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMargins(dp(6), 0, 0, 0);
        bar.addView(chatTitle, tlp);

        bar.addView(iconBtn(ID_TUNE, R.drawable.ic_tune, 40, DIM));
        bar.addView(iconBtn(ID_PLUS, R.drawable.ic_plus, 40, DIM));
        return bar;
    }

    private View termFrame() {
        term = new TextView(this);
        term.setTypeface(mono);
        term.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        term.setTextColor(TEXT);
        // ровно одна строка терминала = одна строка TextView, иначе блочная графика рвётся
        term.setLineSpacing(0f, 1f);
        term.setIncludeFontPadding(false);
        // selectable нельзя: TextView забирает IME-фокус у поля ввода, и печать уходит в никуда
        term.setFocusable(false);
        term.setOnLongClickListener(this);
        term.setPadding(dp(12), dp(4), dp(12), dp(6));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        // фокус живёт только в поле ввода: любой focusable-скролл крадёт его при перестройке layout
        scroll.setFocusable(false);
        scroll.addView(term, new LinearLayout.LayoutParams(-1, -2));
        scroll.setOnScrollChangeListener(this);
        scrollDown = new ScrollDown(scroll);
        // размер узнаём с первого layout, до первого кадра — TmuxThread ждёт его,
        // чтобы создать сессию сразу нужной геометрии (без resize и дубля баннера)
        scroll.addOnLayoutChangeListener(this);

        FrameLayout frame = new FrameLayout(this);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // терминал уходит под шапку градиентом, а не обрезается линией;
        // у верхнего края скролла градиент прячется, чтобы не тускнело начало чата
        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG, BG & 0x00FFFFFF}));
        scrim.setAlpha(0f);
        frame.addView(scrim, new FrameLayout.LayoutParams(-1, dp(26), Gravity.TOP));

        jumpBtn = iconBtn(ID_JUMP, R.drawable.ic_up, 38, TEXT);
        jumpBtn.setRotation(180f);
        GradientDrawable jb = round(CHIP, 999);
        jb.setStroke(dp(1), BORDER);
        jumpBtn.setBackground(jb);
        jumpBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams jlp = new FrameLayout.LayoutParams(dp(38), dp(38),
                Gravity.BOTTOM | Gravity.END);
        jlp.setMargins(0, 0, dp(14), dp(10));
        frame.addView(jumpBtn, jlp);

        // заглушка вместо терминала, когда сессию завершили вручную
        deadBox = new LinearLayout(this);
        deadBox.setOrientation(LinearLayout.VERTICAL);
        deadBox.setGravity(Gravity.CENTER);
        deadBox.setBackgroundColor(BG);
        deadBox.setPadding(dp(32), 0, dp(32), dp(24));
        deadBox.setVisibility(View.GONE);
        deadT = new TextView(this);
        deadT.setText("Сессия завершена");
        deadT.setTextColor(TEXT);
        deadT.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        deadT.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        deadT.setGravity(Gravity.CENTER);
        deadBox.addView(deadT, new LinearLayout.LayoutParams(-2, -2));
        deadS = new TextView(this);
        deadS.setText("История сохранена — Claude Code продолжит с того же места");
        deadS.setTextColor(DIM);
        deadS.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        deadS.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dslp = new LinearLayout.LayoutParams(-2, -2);
        dslp.setMargins(0, dp(6), 0, dp(20));
        deadBox.addView(deadS, dslp);
        TextView db = new TextView(this);
        db.setId(ID_RESTART);
        db.setOnClickListener(this);
        db.setFocusable(false);
        db.setText("Запустить Claude Code");
        db.setTextColor(Color.WHITE);
        db.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        db.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        db.setBackground(round(ACCENT, 999));
        db.setPadding(dp(24), dp(12), dp(24), dp(12));
        deadBox.addView(db, new LinearLayout.LayoutParams(-2, -2));
        frame.addView(deadBox, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    /** Файл из assets в filesDir приложения — оттуда его заберёт root-шелл. */
    private void unpack(String name) {
        try {
            java.io.InputStream is = getAssets().open(name);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), name));
            byte[] buf = new byte[8192];
            for (int n; (n = is.read(buf)) > 0; ) fo.write(buf, 0, n);
            fo.close();
            is.close();
        } catch (Exception e) {
            android.util.Log.i("ClaudeDroid", "unpack " + name + ": " + e);
        }
    }

    /** Заглушка «сессия завершена» вместо терминала — и никакого авто-перезапуска. */
    private void showDead(boolean show) {
        deadShown = show;
        deadBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            term.setText("");
            jumpBtn.setVisibility(View.GONE);
            busy = false;
            menu = false;
            updateSend();
            applyChips();
        }
    }

    /** Кнопка на заглушке: поднять сессию текущего чата (--resume, а для нового
     *  чата, где транскрипта ещё нет, --session-id — по prefs «exist»). */
    private void restartChat() {
        showDead(false);
        tmux.switchTo(curSession, curUuid, curUuid != null && prefs.getBoolean("exist", true));
    }

    private View bottomBox() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setLayoutTransition(new LayoutTransition());

        chipsAuto = new LinearLayout(this);
        chipsAuto.setOrientation(LinearLayout.HORIZONTAL);
        chipsAuto.setGravity(Gravity.CENTER_HORIZONTAL);
        chipsAuto.setPadding(dp(8), 0, dp(8), dp(2));
        chipsAuto.addView(chip("↑", ID_UP));
        chipsAuto.addView(chip("↓", ID_DOWN));
        chipsAuto.addView(chip("⏎", ID_ENTER));
        chipsAuto.addView(chip("esc", ID_ESC));
        chipsAuto.setVisibility(View.GONE);
        bottom.addView(chipsAuto, new LinearLayout.LayoutParams(-1, -2));

        chipsFull = new HorizontalScrollView(this);
        chipsFull.setHorizontalScrollBarEnabled(false);
        chipsFull.setFocusable(false);
        LinearLayout rowFull = new LinearLayout(this);
        rowFull.setOrientation(LinearLayout.HORIZONTAL);
        rowFull.setPadding(dp(8), 0, dp(8), dp(2));
        rowFull.addView(chip("esc", ID_ESC));
        rowFull.addView(chip("⌃C", ID_CTRLC));
        rowFull.addView(chip("tab", ID_TAB));
        rowFull.addView(chip("⇧tab", ID_BTAB));
        rowFull.addView(chip("↑", ID_UP));
        rowFull.addView(chip("↓", ID_DOWN));
        rowFull.addView(chip("⏎", ID_ENTER));
        chipsFull.addView(rowFull);
        chipsFull.setVisibility(View.GONE);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.gravity = Gravity.CENTER_HORIZONTAL;
        bottom.addView(chipsFull, flp);

        buildSlashPanel();
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(dp(12), 0, dp(12), dp(4));
        bottom.addView(slashPanel, slp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(dp(12), dp(4), dp(12), dp(12));
        bottom.addView(inputBar(), clp);
        return bottom;
    }

    /** Композер как у claude.ai: карточка, поле сверху, ряд «⋯ · send» внутри снизу. */
    private View inputBar() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = round(light ? SURFACE : CHIP, 22);
        bg.setStroke(dp(1), BORDER);
        box.setBackground(bg);
        box.setPadding(dp(18), dp(8), dp(10), dp(10));
        if (light) box.setElevation(dp(2));

        input = new EditText(this);
        input.setHint("Сообщение для Claude…");
        input.setBackground(null);
        input.setTextColor(TEXT);
        input.setHintTextColor(DIM);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        input.setMaxLines(6);
        input.setPadding(0, dp(8), dp(8), dp(4));
        input.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        GradientDrawable cursor = round(ACCENT, 1);
        cursor.setSize(dp(2), dp(18));
        input.setTextCursorDrawable(cursor);
        input.setHighlightColor((ACCENT & 0x00FFFFFF) | 0x33000000);
        input.addTextChangedListener(this);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        moreBtn = iconBtn(ID_MORE, R.drawable.ic_plus, 34, DIM);
        row.addView(moreBtn);
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        sendBtn = iconBtn(ID_SEND, R.drawable.ic_return, 38, DIM);
        row.addView(sendBtn);
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    /** Панель подсказок по «/»: список команд с фильтром по мере ввода. */
    private void buildSlashPanel() {
        slashBox = new LinearLayout(this);
        slashBox.setOrientation(LinearLayout.VERTICAL);
        slashBox.setPadding(dp(6), dp(6), dp(6), dp(6));

        slashScroll = new ScrollView(this);
        slashScroll.setVerticalScrollBarEnabled(false);
        slashScroll.setFocusable(false);
        slashScroll.addView(slashBox, new FrameLayout.LayoutParams(-1, -2));

        slashPanel = new LinearLayout(this);
        slashPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = round(SURFACE, 16);
        bg.setStroke(dp(1), BORDER);
        slashPanel.setBackground(bg);
        if (light) slashPanel.setElevation(dp(3));
        slashPanel.addView(slashScroll, new LinearLayout.LayoutParams(-1, -2));
        slashPanel.setVisibility(View.GONE);
    }

    /** Экран чатов: полноэкранный список (jsonl-транскрипты + живые tmux-сессии). */
    private void buildDrawer() {
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(BG);
        drawer.setClickable(true);
        drawer.setElevation(dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(10), dp(8), dp(6));
        ImageButton back = iconBtn(ID_MENU, R.drawable.ic_up, 40, DIM);
        back.setRotation(270f); // стрелка влево = назад в чат
        head.addView(back);
        TextView t = new TextView(this);
        t.setText("Чаты");
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(8), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(iconBtn(ID_NEWCHAT, R.drawable.ic_plus, 42, ACCENT));
        drawer.addView(head, new LinearLayout.LayoutParams(-1, -2));

        chatListBox = new LinearLayout(this);
        chatListBox.setOrientation(LinearLayout.VERTICAL);
        chatListBox.setPadding(dp(8), dp(2), dp(8), dp(12));
        chatScroll = new ScrollView(this);
        chatScroll.setVerticalScrollBarEnabled(false);
        chatScroll.setFocusable(false);
        chatScroll.addView(chatListBox, new FrameLayout.LayoutParams(-1, -2));
        drawer.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("свайп влево по чату — завершить живую сессию");
        hint.setTextColor(DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        hint.setPadding(dp(18), dp(4), dp(18), dp(12));
        drawer.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Нижний шит: модель и уровень усилий — уходит в claude как /model и /effort. */
    private void buildSheet() {
        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        if (!light) bg.setStroke(dp(1), BORDER);
        sheet.setBackground(bg);
        sheet.setClickable(true);
        sheet.setElevation(dp(12));
        sheet.setPadding(dp(10), dp(8), dp(10), dp(18));
        sheet.addOnLayoutChangeListener(this);

        View grip = new View(this);
        GradientDrawable g = round(BORDER, 3);
        grip.setBackground(g);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(34), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.setMargins(0, dp(4), 0, dp(10));
        sheet.addView(grip, glp);

        sheet.addView(sheetLabel("Модель"));
        for (int i = 0; i < MODELS.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setId(ID_MODELROW);
            row.setTag(MODELS[i][0]);
            row.setFocusable(false);
            row.setOnClickListener(this);
            row.setBackground(pressable(10));
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            TextView name = new TextView(this);
            name.setText(MODELS[i][1]);
            name.setTextColor(TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(name);
            TextView desc = new TextView(this);
            desc.setText(MODELS[i][2]);
            desc.setTextColor(DIM);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            row.addView(desc);
            sheet.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        sheet.addView(sheetLabel("Усилия (reasoning)"));
        effortRow = new LinearLayout(this);
        effortRow.setOrientation(LinearLayout.HORIZONTAL);
        effortRow.setPadding(dp(8), dp(2), dp(8), 0);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFocusable(false);
        hs.addView(effortRow);
        sheet.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        renderEffortRow();
    }

    private TextView sheetLabel(String s) {
        TextView l = new TextView(this);
        l.setText(s);
        l.setTextColor(DIM);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        l.setPadding(dp(12), dp(10), dp(12), dp(4));
        return l;
    }

    private void renderEffortRow() {
        effortRow.removeAllViews();
        for (int i = 0; i < EFFORTS.length; i++) {
            TextView c = new TextView(this);
            c.setText(EFFORTS[i]);
            c.setId(ID_EFFORTCHIP);
            c.setTag(EFFORTS[i]);
            c.setFocusable(false);
            c.setOnClickListener(this);
            c.setTypeface(mono);
            c.setGravity(Gravity.CENTER);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            boolean sel = EFFORTS[i].equals(curEffort);
            c.setTextColor(sel ? (light ? Color.WHITE : 0xFF141413) : TEXT);
            GradientDrawable b = round(sel ? ACCENT : CHIP, 10);
            if (!sel) b.setStroke(dp(1), BORDER);
            c.setBackground(b);
            c.setPadding(dp(13), dp(8), dp(13), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            effortRow.addView(c, lp);
        }
    }

    // ---------- мелкие фабрики ----------

    private ImageButton iconBtn(int id, int icon, int sizeDp, int tint) {
        ImageButton bt = new ImageButton(this);
        bt.setId(id);
        bt.setFocusable(false); // фокус живёт только в поле ввода
        bt.setOnClickListener(this);
        bt.setImageResource(icon);
        bt.setBackground(null);
        bt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bt.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        int p = dp(sizeDp >= 38 ? 9 : 8);
        bt.setPadding(p, p, p, p);
        bt.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return bt;
    }

    private TextView chip(String label, int id) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setId(id);
        t.setFocusable(false);
        t.setOnClickListener(this);
        t.setTypeface(mono);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        GradientDrawable b = round(CHIP, 10);
        b.setStroke(dp(1), BORDER);
        t.setBackground(b);
        t.setPadding(dp(13), dp(8), dp(13), dp(8));
        t.setMinWidth(dp(40));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** Фон строки списка: прозрачный, при нажатии — CHIP. */
    private StateListDrawable pressable(int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(CHIP, radiusDp));
        s.addState(new int[]{}, round(0, radiusDp));
        return s;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------- состояние кнопок ----------

    /** Кнопка-хамелеон: стоп пока Claude работает, стрелка при тексте, тихая ⏎ на пустом поле. */
    private void updateSend() {
        if (busy) {
            sendBtn.setBackground(round(ACCENT, 999));
            sendBtn.setImageResource(R.drawable.ic_stop);
            sendBtn.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        } else if (input.getText().length() > 0) {
            sendBtn.setBackground(round(ACCENT, 999));
            sendBtn.setImageResource(R.drawable.ic_up);
            sendBtn.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        } else {
            sendBtn.setBackground(round(CHIP, 999));
            sendBtn.setImageResource(R.drawable.ic_return);
            sendBtn.setColorFilter(DIM, PorterDuff.Mode.SRC_IN);
        }
    }

    /** Чипы по контексту: полный ряд по тумблеру, ряд меню — когда в пейне селектор «❯». */
    private void applyChips() {
        chipsFull.setVisibility(manualKeys ? View.VISIBLE : View.GONE);
        chipsAuto.setVisibility(!manualKeys && menu ? View.VISIBLE : View.GONE);
        if (manualKeys) {
            moreBtn.setBackground(round(CHIP, 999));
            moreBtn.setColorFilter(TEXT, PorterDuff.Mode.SRC_IN);
        } else {
            moreBtn.setBackground(null);
            moreBtn.setColorFilter(DIM, PorterDuff.Mode.SRC_IN);
        }
    }

    private void updateJump() {
        View child = scroll.getChildAt(0);
        boolean show = child != null
                && scroll.getScrollY() + scroll.getHeight() < child.getHeight() - dp(80);
        jumpBtn.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateTitle() {
        chatTitle.setText(curTitle != null ? curTitle : "Новый чат");
    }

    // ---------- шторка и шит ----------

    /** Оверлей поверх композера — клавиатуре там не место. */
    private void hideKb() {
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void openDrawer() {
        drawerOpen = true;
        hideKb();
        // текущий чат на экране — его ответ по определению прочитан
        markSeen(curUuid != null ? curUuid : curSession);
        drawer.animate().translationX(0).setDuration(200);
        tmux.requestChats();
        renderChats();
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.animate().translationX(-drawerW).setDuration(200);
    }

    private void markSeen(String key) {
        if (key != null) prefs.edit().putLong("seen_" + key, System.currentTimeMillis()).apply();
    }

    private void openSheet() {
        sheetOpen = true;
        hideKb();
        dim.setClickable(true);
        dim.animate().alpha(1f).setDuration(180);
        sheet.animate().translationY(0).setDuration(200);
    }

    private void closeSheet() {
        sheetOpen = false;
        dim.setClickable(false);
        dim.animate().alpha(0f).setDuration(180);
        sheet.animate().translationY(sheet.getHeight()).setDuration(200);
    }

    /** Список чатов; статус: работает (оранжевый) / ответ готов (зелёный) / живая / архив. */
    private void renderChats() {
        chatListBox.removeAllViews();
        if (chats == null) {
            TextView t = new TextView(this);
            t.setText("загружаю…");
            t.setTextColor(DIM);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            t.setPadding(dp(12), dp(12), dp(12), dp(12));
            chatListBox.addView(t);
            return;
        }
        for (int i = 0; i < chats.size(); i++) {
            ChatInfo c = chats.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setId(ID_CHATROW);
            row.setTag(c);
            row.setFocusable(false);
            row.setOnClickListener(this);
            row.setOnTouchListener(this);
            boolean cur = c.tmuxName.equals(curSession);
            if (cur) {
                GradientDrawable b = round(CHIP, 12);
                row.setBackground(b);
            } else row.setBackground(pressable(12));
            row.setPadding(dp(12), dp(9), dp(12), dp(9));

            String key = c.uuid != null ? c.uuid : c.tmuxName;
            long seen = prefs.getLong("seen_" + key, 0);
            // чат виден впервые — берём его mtime за базу, иначе всё живое сразу «ответ готов»
            if (seen == 0 && c.mtime > 0) {
                seen = c.mtime;
                prefs.edit().putLong("seen_" + key, seen).apply();
            }
            // у легаси «cc» нет транскрипта, её mtime — «сейчас», непрочитанность не посчитать
            boolean unread = !cur && c.uuid != null && c.live && !c.busy && !c.waiting
                    && c.mtime > seen;

            TextView title = new TextView(this);
            title.setText(c.title);
            title.setTextColor(TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            if (unread) title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title);

            LinearLayout sub = new LinearLayout(this);
            sub.setOrientation(LinearLayout.HORIZONTAL);
            sub.setGravity(Gravity.CENTER_VERTICAL);
            int dotColor = c.busy || c.waiting ? ACCENT : unread ? GREEN : c.live ? DIM : 0;
            String label = c.busy ? "работает…" : c.waiting ? "ждёт выбора"
                    : unread ? "ответ готов" : c.live ? "живая" : null;
            if (dotColor != 0) {
                View ld = new View(this);
                GradientDrawable lb = new GradientDrawable();
                lb.setShape(GradientDrawable.OVAL);
                lb.setColor(dotColor);
                ld.setBackground(lb);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
                lp.setMargins(0, dp(1), dp(6), 0);
                sub.addView(ld, lp);
            }
            TextView time = new TextView(this);
            time.setText((label != null ? label + " · " : "") + fmtTime(c.mtime));
            time.setTextColor(c.busy || c.waiting ? ACCENT : unread ? GREEN : DIM);
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            sub.addView(time);
            LinearLayout.LayoutParams sublp = new LinearLayout.LayoutParams(-2, -2);
            sublp.setMargins(0, dp(2), 0, 0);
            row.addView(sub, sublp);

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, dp(1), 0, dp(1));
            chatListBox.addView(row, rlp);
        }
        if (chats.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("пока пусто — начни первый чат");
            t.setTextColor(DIM);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            t.setPadding(dp(12), dp(12), dp(12), dp(12));
            chatListBox.addView(t);
        }
    }

    private String fmtTime(long ms) {
        if (ms <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR))
            return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR))
            return "вчера " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
        return new SimpleDateFormat("d MMM HH:mm", new Locale("ru")).format(new Date(ms));
    }

    // ---------- чаты ----------

    /** «+»: чат создаётся сразу, но claude не стартует — заглушка с кнопкой,
     *  чтобы пустой чат не ел ОЗУ, пока юзер не начал в нём работать. */
    private void newChat() {
        String id = UUID.randomUUID().toString();
        curSession = TmuxThread.sessName(id);
        curUuid = id;
        curTitle = "Новый чат";
        prefs.edit().putString("sess", curSession).putString("uuid", id)
                .putString("title", curTitle).putBoolean("exist", false).apply();
        tmux.setIdle(true);
        updateTitle();
        deadT.setText("Новый чат");
        deadS.setText("Claude Code запустится по кнопке — пустой чат не тратит память");
        showDead(true);
        if (drawerOpen) closeDrawer();
    }

    private void openChat(ChatInfo c) {
        if (c.tmuxName.equals(curSession)) {
            closeDrawer();
            return;
        }
        switchChat(c.tmuxName, c.uuid, true, c.title);
    }

    private void switchChat(String sess, String id, boolean exists, String title) {
        markSeen(id != null ? id : sess);
        curSession = sess;
        curUuid = id;
        curTitle = title;
        prefs.edit().putString("sess", sess).putString("uuid", id == null ? "" : id)
                .putString("title", title).putBoolean("exist", exists).apply();
        term.setText("");
        showDead(false);
        busy = false;
        menu = false;
        updateSend();
        applyChips();
        updateTitle();
        tmux.switchTo(sess, id, exists);
        if (drawerOpen) closeDrawer();
    }

    private void killChat(ChatInfo c) {
        boolean cur = c.tmuxName.equals(curSession);
        // idle до kill: иначе поллер увидит мёртвую сессию и тут же её воскресит
        if (cur) tmux.setIdle(true);
        tmux.post(TmuxThread.tmuxCmd() + " kill-session -t " + c.tmuxName);
        if (cur) {
            deadT.setText("Сессия завершена");
            deadS.setText("История сохранена — Claude Code продолжит с того же места");
            showDead(true);
        }
        // список обновляем после того, как строка вернулась на место — иначе перестройка рвёт анимацию
        handler.postDelayed(tmux::requestChats, 300);
    }

    // ---------- слэш-панель ----------

    private void updateSlash() {
        String t = input.getText().toString();
        boolean show = t.startsWith("/") && !t.contains(" ") && !t.contains("\n") && t.length() < 40;
        if (!show) {
            slashPanel.setVisibility(View.GONE);
            return;
        }
        String q = t.substring(1).toLowerCase(Locale.US);
        slashBox.removeAllViews();
        int count = 0;
        // сперва встроенные по префиксу, затем скиллы, затем substring-совпадения
        for (int pass = 0; pass < 2 && count < 40; pass++) {
            for (int i = 0; i < BUILTIN.length && count < 40; i++) {
                boolean hit = pass == 0 ? BUILTIN[i][0].startsWith(q)
                        : (BUILTIN[i][0].contains(q) && !BUILTIN[i][0].startsWith(q));
                if (hit) {
                    slashBox.addView(slashRow(BUILTIN[i][0], BUILTIN[i][1]));
                    count++;
                }
            }
            for (int i = 0; i < skills.size() && count < 40; i++) {
                String s = skills.get(i);
                if (isBuiltin(s)) continue;
                boolean hit = pass == 0 ? s.startsWith(q) : (s.contains(q) && !s.startsWith(q));
                if (hit) {
                    slashBox.addView(slashRow(s, "скилл"));
                    count++;
                }
            }
        }
        if (count == 0) {
            slashPanel.setVisibility(View.GONE);
            return;
        }
        int cap = dp(238);
        int want = count * dp(41) + dp(12);
        slashScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(want, cap)));
        slashPanel.setVisibility(View.VISIBLE);
        slashScroll.scrollTo(0, 0);
    }

    private static boolean isBuiltin(String name) {
        for (int i = 0; i < BUILTIN.length; i++)
            if (BUILTIN[i][0].equals(name)) return true;
        return false;
    }

    private View slashRow(String cmd, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setId(ID_SLASHROW);
        row.setTag(cmd);
        row.setFocusable(false);
        row.setOnClickListener(this);
        row.setOnLongClickListener(this);
        row.setBackground(pressable(10));
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView name = new TextView(this);
        name.setText("/" + cmd);
        name.setTypeface(mono);
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        row.addView(name);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(DIM);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        d.setMaxLines(1);
        d.setEllipsize(TextUtils.TruncateAt.END);
        d.setGravity(Gravity.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, -2, 1f);
        dlp.setMargins(dp(12), 0, 0, 0);
        row.addView(d, dlp);
        return row;
    }

    // ---------- клики ----------

    @Override
    public void onClick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        switch (v.getId()) {
            case ID_SEND:
                if (deadShown) restartChat(); // сессии нет — слать некуда, сперва поднимаем
                else if (busy) keys("Escape");
                else if (input.getText().length() == 0) keys("Enter");
                else sendText();
                break;
            case ID_RESTART:
                restartChat();
                break;
            case ID_ENTER: keys("Enter"); break;
            case ID_ESC: keys("Escape"); break;
            case ID_CTRLC: keys("C-c"); break;
            case ID_TAB: keys("Tab"); break;
            case ID_BTAB: keys("BTab"); break;
            case ID_UP: keys("Up"); break;
            case ID_DOWN: keys("Down"); break;
            case ID_MORE: showPlusMenu(v); break;
            case ID_PLUS:
            case ID_NEWCHAT:
                newChat();
                break;
            case ID_JUMP: scroll.post(scrollDown); break;
            case ID_MENU:
            case ID_TITLE:
                if (drawerOpen) closeDrawer();
                else openDrawer();
                break;
            case ID_TUNE:
                if (sheetOpen) closeSheet();
                else openSheet();
                break;
            case ID_DIM:
                if (sheetOpen) closeSheet();
                break;
            case ID_CHATROW:
                openChat((ChatInfo) v.getTag());
                break;
            case ID_SLASHROW:
                slashSend("/" + v.getTag());
                input.setText("");
                break;
            case ID_MODELROW:
                slashSend("/model " + v.getTag());
                closeSheet();
                break;
            case ID_EFFORTCHIP:
                slashSend("/effort " + v.getTag());
                closeSheet();
                break;
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == term) {
            android.content.ClipboardManager cb =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("терминал", term.getText()));
            Toast.makeText(this, "текст скопирован", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (v.getId() == ID_SLASHROW) {
            input.setText("/" + v.getTag() + " ");
            input.setSelection(input.getText().length());
            input.requestFocus();
            return true;
        }
        return false;
    }

    /** Свайп влево по строке живого чата — завершить его tmux-сессию. */
    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        ChatInfo c = (ChatInfo) v.getTag();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                rowDownX = ev.getRawX();
                rowDownY = ev.getRawY();
                rowDrag = false;
                rowArmed = false;
                return false;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getRawX() - rowDownX;
                float dy = Math.abs(ev.getRawY() - rowDownY);
                if (!rowDrag && c.live && dx < -dp(16) && -dx > dy * 1.5f) {
                    rowDrag = true;
                    v.setPressed(false);
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (rowDrag) {
                    float t = Math.min(0, dx);
                    float th = -v.getWidth() * 0.35f;
                    boolean armed = t < th;
                    if (armed != rowArmed) {
                        rowArmed = armed;
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    // за порогом строка тянется всё туже — резиновое сопротивление
                    if (t < th) t = th + (t - th) * 0.35f;
                    v.setTranslationX(t);
                    v.setAlpha(1f - Math.min(0.5f, -t / (float) v.getWidth()));
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
                if (rowDrag) {
                    rowDrag = false;
                    if (rowArmed) {
                        rowArmed = false;
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        killChat(c);
                        // строка улетает влево и мягко проявляется на месте:
                        // чат остаётся в списке как архив, статус сменится при обновлении
                        v.animate().translationX(-v.getWidth()).alpha(0f).setDuration(200)
                                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                                .withEndAction(() -> {
                                    v.setTranslationX(0);
                                    v.animate().alpha(1f).setStartDelay(120).setDuration(280)
                                            .setInterpolator(new android.view.animation.DecelerateInterpolator());
                                });
                    } else {
                        // не дотянули — пружинисто возвращается
                        v.animate().translationX(0).alpha(1f).setDuration(320)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f));
                    }
                    return true;
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                if (rowDrag) {
                    rowDrag = false;
                    rowArmed = false;
                    v.animate().translationX(0).alpha(1f).setDuration(200)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator());
                    return true;
                }
                return false;
        }
        return false;
    }

    // ---------- вложения ----------

    /** «+» в композере: приложить файл или показать/скрыть панель команд.
     *  Свой попап, а не PopupMenu — системное меню игнорирует палитру приложения. */
    private void showPlusMenu(View anchor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = round(SURFACE, 18);
        bg.setStroke(dp(1), BORDER);
        box.setBackground(bg);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));

        final PopupWindow pw = new PopupWindow(box, dp(232), -2, true);
        pw.setBackgroundDrawable(new ColorDrawable(0)); // без него не закрывается тапом мимо
        pw.setElevation(dp(14));
        pw.setAnimationStyle(0);

        box.addView(menuRow(R.drawable.ic_attach, "Фото или файл", DIM, () -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_PICK);
            pw.dismiss();
        }));
        box.addView(menuRow(R.drawable.ic_keys,
                manualKeys ? "Скрыть панель команд" : "Панель команд",
                manualKeys ? ACCENT : DIM, () -> {
            manualKeys = !manualKeys;
            prefs.edit().putBoolean("keys", manualKeys).apply();
            applyChips();
            pw.dismiss();
        }));

        // попап уезжает вверх над «+», а не вниз под клавиатуру
        box.measure(View.MeasureSpec.makeMeasureSpec(dp(232), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        pw.showAsDropDown(anchor, -dp(4), -(box.getMeasuredHeight() + anchor.getHeight() + dp(8)));

        box.setAlpha(0f);
        box.setScaleX(0.92f);
        box.setScaleY(0.92f);
        box.setPivotX(dp(24));
        box.setPivotY(box.getMeasuredHeight());
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130);
    }

    private View menuRow(int icon, String label, int tint, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(pressable(12));
        row.setPadding(dp(12), dp(11), dp(14), dp(11));
        row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });

        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        row.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(12), 0, 0, 0);
        row.addView(t, lp);
        return row;
    }

    /** Выбранные файлы копируются в files/attach (claude под root их читает),
     *  а их пути дописываются в поле ввода — Claude Code сам подхватит их из промпта. */
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<Uri>();
        if (data.getClipData() != null)
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            File dir = new File(getFilesDir(), "attach");
            dir.mkdirs();
            for (Uri u : uris) {
                try {
                    // nanoTime — чтобы два файла из одного пика не столкнулись именами
                    File f = new File(dir, System.nanoTime() % 100000000 + "-" + fileName(u));
                    InputStream in = getContentResolver().openInputStream(u);
                    FileOutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                    sb.append(f.getAbsolutePath()).append(' ');
                } catch (Exception ignored) {
                }
            }
            final String paths = sb.toString();
            runOnUiThread(() -> {
                if (paths.isEmpty()) {
                    Toast.makeText(this, "не удалось приложить файл", Toast.LENGTH_SHORT).show();
                    return;
                }
                Editable e = input.getText();
                if (e.length() > 0 && e.charAt(e.length() - 1) != ' '
                        && e.charAt(e.length() - 1) != '\n') e.append(' ');
                e.append(paths);
                input.setSelection(e.length());
                input.requestFocus();
            });
        }).start();
    }

    /** Имя из провайдера, но без пробелов и экзотики — путь уходит в промпт как есть. */
    private String fileName(Uri u) {
        String name = "file";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) name = c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return name.replaceAll("[^\\w.\\-]", "_");
    }

    // ---------- отправка ----------

    private void keys(String k) {
        tmux.post(TmuxThread.tmuxCmd() + " send-keys -t " + tmux.session() + " " + k);
    }

    /** Текст уходит через буфер tmux (bracketed paste) — так не ломаются переносы строк. */
    private void sendText() {
        String t = input.getText().toString();
        slashSend(t);
        if (("Новый чат".equals(curTitle) || curTitle == null) && !t.startsWith("/")) {
            curTitle = t.length() > 48 ? t.substring(0, 48) : t;
            prefs.edit().putString("title", curTitle).apply();
            updateTitle();
        }
        input.setText("");
        input.requestFocus();
    }

    private void slashSend(String t) {
        String esc = t.replace("'", "'\\''");
        String tm = TmuxThread.tmuxCmd();
        String sess = tmux.session();
        tmux.post(tm + " set-buffer -- '" + esc + "'; " + tm + " paste-buffer -p -t " + sess + " -d");
        tmux.post("sleep 0.2; " + tm + " send-keys -t " + sess + " Enter");
        // перерисовка после /clear оставляет старый кадр в scrollback — стираем его
        if (t.trim().equals("/clear"))
            tmux.post("sleep 1.5; " + tm + " clear-history -t " + sess);
    }

    // ---------- сообщения из TmuxThread ----------

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case TmuxThread.MSG_SCREEN: {
                if (deadShown) return true; // запоздавший кадр убитой сессии
                CharSequence parsed = (CharSequence) msg.obj;
                // состояние Claude Code читается из хвоста экрана (выше — скроллбек)
                int len = parsed.length();
                String tail = parsed.subSequence(Math.max(0, len - 1500), len).toString();
                boolean b = tail.contains("esc to interrupt");
                boolean m = hasMenu(tail);
                if (b != busy || m != menu) {
                    // Claude закончил ход — к этому моменту он мог сгенерить заголовок чата
                    if (busy && !b) tmux.requestTitle();
                    busy = b;
                    menu = m;
                    updateSend();
                    applyChips();
                }
                Matcher em = EFFORT_RE.matcher(tail);
                if (em.find()) {
                    String e = em.group(1);
                    if (!e.equals(curEffort)) {
                        curEffort = e;
                        renderEffortRow();
                    }
                }
                if (!prefs.getBoolean("exist", true))
                    prefs.edit().putBoolean("exist", true).apply();
                int prevY = scroll.getScrollY();
                View child = scroll.getChildAt(0);
                boolean wasBottom = child == null
                        || prevY + scroll.getHeight() >= child.getHeight() - dp(60);
                term.setText(parsed);
                if (measuredW == 0) measureAndResize();
                if (wasBottom) scroll.post(scrollDown);
                else scroll.scrollTo(0, prevY);
                atBottom = wasBottom;
                updateJump();
                return true;
            }
            case TmuxThread.MSG_ERROR:
                term.setText((String) msg.obj);
                Toast.makeText(this, (String) msg.obj, Toast.LENGTH_LONG).show();
                return true;
            case TmuxThread.MSG_CHATS: {
                chats = (List<ChatInfo>) msg.obj;
                if (drawerOpen) renderChats();
                return true;
            }
            case TmuxThread.MSG_SKILLS: {
                skills = (List<String>) msg.obj;
                if (slashPanel.getVisibility() == View.VISIBLE) updateSlash();
                return true;
            }
            case TmuxThread.MSG_SESSION: {
                String[] s = (String[]) msg.obj;
                curSession = s[0];
                curUuid = s[1].length() == 0 ? null : s[1];
                curTitle = curUuid == null ? "Терминал" : "Новый чат";
                prefs.edit().putString("sess", curSession).putString("uuid", s[1])
                        .putString("title", curTitle).putBoolean("exist", "1".equals(s[2])).apply();
                updateTitle();
                return true;
            }
            case TmuxThread.MSG_TITLE: {
                String[] s = (String[]) msg.obj;
                if (s[0].equals(curUuid) && !s[1].equals(curTitle)) {
                    curTitle = s[1];
                    prefs.edit().putString("title", curTitle).apply();
                    updateTitle();
                }
                return true;
            }
        }
        return true;
    }

    // ---------- скролл и раскладка ----------

    @Override
    public void onScrollChange(View v, int x, int y, int ox, int oy) {
        View child = scroll.getChildAt(0);
        atBottom = child == null || y + scroll.getHeight() >= child.getHeight() - dp(60);
        scrim.setAlpha(Math.min(1f, y / (float) dp(26)));
        updateJump();
    }

    @Override
    public void onLayoutChange(View v, int l, int t, int r, int b,
                               int ol, int ot, int oR, int ob) {
        if (v == sheet) {
            if (!sheetOpen) sheet.setTranslationY(sheet.getHeight());
            return;
        }
        // клавиатура открылась/закрылась: если были внизу — остаёмся внизу
        int h = b - t;
        if (h != lastScrollH) {
            lastScrollH = h;
            if (atBottom) scroll.post(scrollDown);
        }
        measureAndResize();
    }

    /** Ширина pane = ширина экрана в символах; высота — от максимального виденного
     *  вьюпорта: клавиатура не должна дёргать resize (каждый resize перерисовывает TUI
     *  и стирает scrollback), а при закрытой клаве вьюпорт и есть максимум. */
    private void measureAndResize() {
        float cw = term.getPaint().measureText("MMMMMMMMMM") / 10f;
        float lh = term.getPaint().getFontSpacing();
        int w = term.getWidth() - term.getPaddingLeft() - term.getPaddingRight();
        int h = scroll.getHeight() - term.getPaddingTop() - term.getPaddingBottom();
        if (cw <= 0 || lh <= 0 || w <= 0 || h <= 0) return;
        if (w != measuredW) {
            measuredW = w;
            maxTermH = h;
        } else if (h > maxTermH) maxTermH = h;
        else return;
        android.util.Log.i("ClaudeDroid", "measure w=" + w + " h=" + h + " cw=" + cw + " lh=" + lh
                + " cols=" + (int) (w / cw) + " rows=" + (int) (maxTermH / lh));
        tmux.setSize((int) (w / cw), (int) (maxTermH / lh));
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable e) {
        updateSend();
        updateSlash();
    }

    /** «❯» — ещё и промпт ввода Claude Code, поэтому меню — только «❯» с цифрой после
     *  («❯ 1. Yes»). Ненумерованные селекторы редки — для них есть полный ряд по тумблеру. */
    static boolean hasMenu(String tail) {
        int idx = -1;
        while ((idx = tail.indexOf('❯', idx + 1)) >= 0) {
            int j = idx + 1;
            while (j < tail.length() && tail.charAt(j) == ' ') j++;
            if (j < tail.length() && Character.isDigit(tail.charAt(j))) return true;
        }
        return false;
    }

    // ---------- edge-swipe шторки ----------

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!drawerOpen && !sheetOpen) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (ev.getX() < dp(24)) {
                        edgeDownX = ev.getX();
                        edgeDownY = ev.getY();
                        edgeDrag = false;
                    } else edgeDownX = -1;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (edgeDownX >= 0 && !edgeDrag) {
                        float dx = ev.getX() - edgeDownX;
                        float dy = Math.abs(ev.getY() - edgeDownY);
                        if (dx > dp(14) && dx > dy * 1.5f) {
                            edgeDrag = true;
                            // забираем жест себе: детям — отмена
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(cancel);
                            cancel.recycle();
                        }
                    }
                    if (edgeDrag) {
                        float dx = Math.max(0, Math.min(ev.getX() - edgeDownX, drawerW));
                        drawer.setTranslationX(dx - drawerW);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (edgeDrag) {
                        edgeDrag = false;
                        edgeDownX = -1;
                        if (drawer.getTranslationX() > -drawerW * 0.6f) openDrawer();
                        else closeDrawer();
                        return true;
                    }
                    edgeDownX = -1;
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (sheetOpen) {
            closeSheet();
            return;
        }
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
    }

    // ---------- жизненный цикл ----------

    @Override
    protected void onStart() {
        super.onStart();
        if (tmux != null) tmux.setPaused(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // в фоне экран не опрашиваем: tmux и claude живут сами, батарею не тратим
        if (tmux != null) tmux.setPaused(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tmux != null) tmux.quitThread();
    }
}
