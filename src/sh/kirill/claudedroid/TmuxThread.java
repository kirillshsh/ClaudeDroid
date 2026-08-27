package sh.kirill.claudedroid;

import android.os.Handler;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Единственный поток, который говорит с root-шеллом: шлёт команды в tmux и снимает экран.
 *  Умеет несколько чатов (tmux-сессия на чат), переживает смерть su-шелла и паузу в фоне. */
public class TmuxThread extends Thread {

    public static final String PREFIX = "/data/data/com.termux/files/usr";
    public static final String HOME = "/data/data/com.termux/files/home";
    /** Транскрипты claude для cwd=HOME — источник списка чатов. */
    public static final String PROJ = HOME + "/.claude/projects/-data-data-com-termux-files-home";
    public static final int MSG_SCREEN = 1;
    public static final int MSG_ERROR = 2;
    public static final int MSG_CHATS = 3;
    public static final int MSG_SKILLS = 4;
    public static final int MSG_SESSION = 5;
    public static final int MSG_TITLE = 6;

    private static final String RUNDIR = "/dev/claudedroid-run";
    /** su-шелл берёт PATH из mkshrc — на него не полагаемся, всё зовём по абсолютному пути. */
    private static final String ENV = "TMUX_TMPDIR=/data/local/tmp TERM=xterm-256color "
            + "LANG=en_US.UTF-8 HOME=" + HOME + " TMPDIR=" + PREFIX + "/tmp "
            + "PREFIX=" + PREFIX + " PATH=" + PREFIX + "/bin:/system/bin:/system/xbin "
            + "IS_SANDBOX=1 COLORTERM=truecolor FORCE_COLOR=3 SHELL=" + PREFIX + "/bin/bash "
            // сокеты cross-session: claude требует приватный каталог, принадлежащий ему или root,
            // а весь /data — group-writable (system), поэтому кладём в /dev
            + "CLAUDE_CODE_TMPDIR=" + RUNDIR;
    private static final String TMUX = "env " + ENV + " " + PREFIX + "/bin/tmux";
    private static final String MARK = "__CCPAD_EOF__";
    private static final long POLL_MS = 300;

    private final Handler ui;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();
    private volatile boolean running = true;
    private volatile boolean paused = false;
    // текущий чат завершён вручную: экран не снимаем и сессию не пересоздаём,
    // пока юзер сам не запустит её кнопкой или не уйдёт в другой чат
    private volatile boolean idle = false;
    private volatile int cols = 62;
    private volatile int rows = 34;
    private volatile boolean resizeNeeded = false;
    private volatile boolean sizeKnown = false;
    private volatile boolean chatsWanted = false;
    private volatile boolean skillsWanted = true;
    private volatile boolean ensureWanted = false;
    private volatile boolean titleWanted = false;
    // текущий чат: tmux-сессия + uuid транскрипта (null у легаси «cc»)
    private volatile String session;
    private volatile String uuid;
    private volatile boolean existing;
    private Process proc;
    private OutputStream out;
    private BufferedReader in;
    private String last = null;
    private long lastEnsure = 0;
    private int ensureBurst = 0;

    public TmuxThread(Handler ui, String session, String uuid, boolean existing) {
        this.ui = ui;
        this.session = session; // null → решаем сами на старте (легаси cc или новый чат)
        this.uuid = uuid;
        this.existing = existing;
    }

    public static String tmuxCmd() {
        return TMUX;
    }

    public String session() {
        return session;
    }

    /** Поставить команду в очередь (выполнится в root-шелле). */
    public void post(String cmd) {
        queue.offer(cmd);
    }

    /** Переключиться на другой чат: сессия создастся при необходимости. */
    public void switchTo(String name, String id, boolean exists) {
        session = name;
        uuid = id;
        existing = exists;
        last = null;
        ensureBurst = 0;
        ensureWanted = true;
        idle = false;
        queue.offer(""); // разбудить
    }

    /** Текущий чат завершён: замереть, не пересоздавая сессию (UI показывает заглушку). */
    public void setIdle(boolean i) {
        idle = i;
        if (!i) {
            last = null;
            queue.offer("");
        }
    }

    public void requestChats() {
        chatsWanted = true;
        queue.offer("");
    }

    /** Перечитать заголовок текущего чата из транскрипта (claude сам его генерит). */
    public void requestTitle() {
        titleWanted = true;
        queue.offer("");
    }

    public void requestSkills() {
        skillsWanted = true;
        queue.offer("");
    }

    /** В фоне экран не снимаем — su-шелл и tmux живут, батарею не жжём. */
    public void setPaused(boolean p) {
        paused = p;
        if (!p) {
            last = null; // форсим полную перерисовку при возврате
            queue.offer("");
        }
    }

    public void setSize(int c, int r) {
        if (c > 20 && r > 8) {
            if (c != cols || r != rows) {
                cols = c;
                rows = r;
                resizeNeeded = true;
            }
            sizeKnown = true;
        }
    }

    public void quitThread() {
        running = false;
        queue.offer("");
    }

    private String run(String cmd) throws Exception {
        out.write(("{ " + cmd + " ; } 2>&1\necho " + MARK + "\n").getBytes("UTF-8"));
        out.flush();
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (MARK.equals(line)) break;
            sb.append(line).append('\n');
        }
        if (line == null) throw new Exception("su-шелл закрылся");
        return sb.toString();
    }

    @Override
    public void run() {
        boolean announcedError = false;
        while (running) {
            try {
                connect();
                announcedError = false;
                loop();
                return; // штатный выход
            } catch (Exception e) {
                Log.i("ClaudeDroid", "reconnect: " + e);
                if (!announcedError) {
                    announcedError = true;
                    ui.obtainMessage(MSG_ERROR, "Переподключаюсь к root-шеллу… (" + e.getMessage() + ")").sendToTarget();
                }
                closeQuietly();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        closeQuietly();
    }

    private void connect() throws Exception {
        proc = openSu();
        out = proc.getOutputStream();
        in = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
        run("cd " + HOME);
        String id = run("id -u").trim();
        if (!"0".equals(id)) throw new Exception("нет root: uid " + id);
        // системный промпт claude (~/.claude/CLAUDE.md) — MainActivity кладёт его в filesDir
        run("mkdir -p " + HOME + "/.claude && cp /data/data/sh.kirill.claudedroid/files/CLAUDE.md "
                + HOME + "/.claude/CLAUDE.md 2>/dev/null");
        // ждём первый measure из UI: сессия рождается сразу правильного размера,
        // иначе resize после старта заставляет Ink перерисоваться и дублирует баннер в истории
        for (int w = 0; w < 80 && !sizeKnown; w++) Thread.sleep(50);
        resizeNeeded = false;
        run(TMUX + " set -g default-terminal tmux-256color; " + TMUX + " set -g history-limit 10000; "
                + TMUX + " set -sa terminal-features ',tmux-256color:RGB'; " + TMUX + " set -g status off");
        if (session == null) pickInitialSession();
        ensureSession();
        syncSize();
        titleWanted = true;
    }

    /** Уже существующая сессия могла жить с другой геометрией — выравниваем. */
    private void syncSize() throws Exception {
        String sz = run(TMUX + " list-windows -t " + session
                + " -F '#{window_width}x#{window_height}' 2>/dev/null").trim();
        if (sz.length() > 0 && !sz.equals(cols + "x" + rows)) resizeNeeded = true;
    }

    /** Первый запуск/потеря prefs: живая легаси-сессия «cc» — берём её, иначе новый чат. */
    private void pickInitialSession() throws Exception {
        String has = run(TMUX + " has-session -t cc 2>/dev/null && echo yes").trim();
        if (has.endsWith("yes")) {
            session = "cc";
            uuid = null;
            existing = true;
        } else {
            uuid = UUID.randomUUID().toString();
            session = sessName(uuid);
            existing = false;
        }
        ui.obtainMessage(MSG_SESSION, new String[]{session, uuid == null ? "" : uuid,
                existing ? "1" : "0"}).sendToTarget();
    }

    public static String sessName(String uuid) {
        return "cc_" + uuid.substring(0, 8);
    }

    private void loop() throws Exception {
        while (running) {
            String cmd = queue.poll(paused ? 2000 : POLL_MS, TimeUnit.MILLISECONDS);
            if (cmd != null && cmd.length() > 0) run(cmd);
            if (!running) break;
            if (skillsWanted) {
                skillsWanted = false;
                scanSkills();
            }
            if (chatsWanted) {
                chatsWanted = false;
                listChats();
            }
            if (ensureWanted) {
                ensureWanted = false;
                ensureSession();
                syncSize();
                titleWanted = true;
            }
            if (titleWanted) {
                titleWanted = false;
                readTitle();
            }
            if (paused || idle) continue;
            if (resizeNeeded) {
                run(TMUX + " resize-window -t " + session + " -x " + cols + " -y " + rows);
                // перерисовка после resize выталкивает старый кадр в scrollback — стираем
                run("sleep 1; " + TMUX + " clear-history -t " + session);
                resizeNeeded = false;
            }
            String screen = run(TMUX + " capture-pane -p -e -S -800 -t " + session);
            if (screen.length() == 0 || looksDead(screen)) {
                // защита от вечного цикла пересоздания, если claude падает на старте
                long now = System.currentTimeMillis();
                if (now - lastEnsure > 60000) ensureBurst = 0;
                if (ensureBurst >= 4) {
                    ui.obtainMessage(MSG_ERROR, "Сессия не поднимается — открой другой чат или новый").sendToTarget();
                    Thread.sleep(5000);
                    continue;
                }
                ensureBurst++;
                lastEnsure = now;
                ensureSession();
                continue;
            }
            screen = trimTail(screen);
            if (!screen.equals(last)) {
                last = screen;
                // разбор ANSI здесь, не на UI-потоке
                ui.obtainMessage(MSG_SCREEN, Ansi.parse(screen)).sendToTarget();
            }
        }
        try {
            out.write("exit\n".getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    /** После kill-session сервер может умереть, и capture-pane возвращает одну строку
     *  ошибки вместо экрана — это сигнал пересоздать сессию. */
    private static boolean looksDead(String s) {
        return s.indexOf('\n') == s.length() - 1
                && (s.startsWith("no server running") || s.startsWith("error connecting")
                || s.startsWith("can't find") || s.startsWith("no current target")
                || s.startsWith("no server") || s.startsWith("lost server"));
    }

    private static String trimTail(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\n' || c == ' ' || c == '\r') end--;
            else break;
        }
        return s.substring(0, end) + "\n";
    }

    /** su лежит не в /system/bin (у Magisk это /system_ext/bin/su), а -M обязателен:
     *  Android прячет чужие /data/data в mount namespace приложения, и без глобального
     *  namespace бинарники Termux не видны даже под root. */
    private Process openSu() throws Exception {
        String[] paths = {"/system/bin/su", "/system_ext/bin/su", "/debug_ramdisk/su", "su"};
        Exception lastE = null;
        for (int i = 0; i < paths.length; i++) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{paths[i], "-M"});
                Log.i("ClaudeDroid", "su ok: " + paths[i]);
                return p;
            } catch (Exception e) {
                lastE = e;
            }
        }
        throw lastE;
    }

    private void closeQuietly() {
        try {
            if (proc != null) proc.destroy();
        } catch (Exception ignored) {
        }
        proc = null;
    }

    private static String claudeCmd(String args) {
        return "env -u TMUX -u TMUX_PANE -u TERM_PROGRAM -u TERM_PROGRAM_VERSION " + ENV + " "
                + PREFIX + "/bin/claude" + (args.length() > 0 ? " " + args : "");
    }

    /** Сессия текущего чата: создаём, если её нет. Легаси «cc» — плоский claude,
     *  новый чат — --session-id (uuid наш заранее), старый — --resume. */
    private void ensureSession() throws Exception {
        run("mkdir -p " + RUNDIR + "; chmod 700 " + RUNDIR + "; chown 0:0 " + RUNDIR);
        String args = "";
        if (uuid != null) args = existing ? "--resume " + uuid : "--session-id " + uuid;
        run(TMUX + " has-session -t " + session + " 2>/dev/null || " + TMUX
                + " new-session -d -s " + session + " -x " + cols + " -y " + rows
                + " \"" + claudeCmd(args) + "\"");
        // файл сессии появился — дальше пересоздание только через --resume,
        // повторный --session-id с занятым uuid уронил бы claude в цикл
        if (!existing && uuid != null) existing = true;
    }

    /** Список чатов: транскрипты *.jsonl (uuid, mtime, первое сообщение) + живые tmux-сессии. */
    private void listChats() {
        try {
            HashSet<String> live = new HashSet<String>();
            String ls = run(TMUX + " ls -F '#{session_name}' 2>/dev/null");
            String[] lines = ls.split("\n");
            for (int i = 0; i < lines.length; i++)
                if (lines[i].length() > 0 && !lines[i].contains("no server")) live.add(lines[i].trim());

            // статус живых сессий по хвосту пейна: «esc to interrupt» → работает,
            // селектор «❯ 1.» → ждёт выбора. Хвост берём с запасом: под строкой статуса
            // ещё поле ввода, подсказки и меню, tail -6 их не покрывал
            HashSet<String> busySet = new HashSet<String>();
            HashSet<String> waitSet = new HashSet<String>();
            for (String s : live) {
                String tail = run(TMUX + " capture-pane -p -t " + s + " 2>/dev/null | tail -20");
                if (tail.contains("esc to interrupt")) busySet.add(s);
                else if (MainActivity.hasMenu(tail)) waitSet.add(s);
            }

            String meta = run("cd " + PROJ + " 2>/dev/null && stat -c '%Y %n' *.jsonl 2>/dev/null | sort -rn | head -40; cd " + HOME);
            ArrayList<String> files = new ArrayList<String>();
            ArrayList<Long> times = new ArrayList<Long>();
            String[] ml = meta.split("\n");
            for (int i = 0; i < ml.length; i++) {
                int sp = ml[i].indexOf(' ');
                if (sp <= 0 || !ml[i].endsWith(".jsonl")) continue;
                try {
                    times.add(Long.parseLong(ml[i].substring(0, sp)) * 1000L);
                    files.add(ml[i].substring(sp + 1));
                } catch (NumberFormatException ignored) {
                }
            }

            ArrayList<ChatInfo> chats = new ArrayList<ChatInfo>();
            if (live.contains("cc")) {
                ChatInfo legacy = new ChatInfo();
                legacy.uuid = null;
                legacy.tmuxName = "cc";
                legacy.title = "Терминал (старая сессия)";
                legacy.mtime = System.currentTimeMillis();
                legacy.live = true;
                legacy.busy = busySet.contains("cc");
                legacy.waiting = waitSet.contains("cc");
                chats.add(legacy);
            }
            if (!files.isEmpty()) {
                // заголовки от самого claude: ai-title (генерит) и custom-title (переименовал юзер)
                java.util.HashMap<String, String> named = new java.util.HashMap<String, String>();
                StringBuilder gt = new StringBuilder("grep -H '\"aiTitle\"\\|\"customTitle\"'");
                for (int i = 0; i < files.size(); i++) gt.append(" ").append(PROJ).append("/").append(files.get(i));
                String[] nl = run(gt + " 2>/dev/null").split("\n");
                java.util.HashMap<String, String> ai = new java.util.HashMap<String, String>();
                for (int i = 0; i < nl.length; i++) {
                    int colon = nl[i].indexOf(".jsonl:");
                    if (colon < 0) continue;
                    String fn = nl[i].substring(nl[i].lastIndexOf('/', colon) + 1, colon + 6);
                    String json = nl[i].substring(colon + 7);
                    String custom = extractString(json, "\"customTitle\":\"");
                    if (custom != null && custom.length() > 0) named.put(fn, custom);
                    else {
                        String a = extractString(json, "\"aiTitle\":\"");
                        if (a != null && a.length() > 0) ai.put(fn, a);
                    }
                }
                for (java.util.Map.Entry<String, String> e : ai.entrySet())
                    if (!named.containsKey(e.getKey())) named.put(e.getKey(), e.getValue());

                StringBuilder g = new StringBuilder("grep -m1 -H '\"promptSource\":\"typed\"'");
                for (int i = 0; i < files.size(); i++) g.append(" ").append(PROJ).append("/").append(files.get(i));
                String titles = run(g + " 2>/dev/null");
                String[] tl = titles.split("\n");
                for (int i = 0; i < tl.length; i++) {
                    int colon = tl[i].indexOf(".jsonl:");
                    if (colon < 0) continue;
                    String path = tl[i].substring(0, colon + 6);
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    String id = name.substring(0, name.length() - 6);
                    if (id.length() < 36) continue;
                    String title = named.get(name);
                    if (title == null) title = extractContent(tl[i].substring(colon + 7));
                    if (title == null || title.length() == 0) continue;
                    ChatInfo c = new ChatInfo();
                    c.uuid = id;
                    c.tmuxName = sessName(id);
                    c.title = title;
                    int fi = files.indexOf(name);
                    c.mtime = fi >= 0 ? times.get(fi) : 0;
                    c.live = live.contains(c.tmuxName);
                    c.busy = busySet.contains(c.tmuxName);
                    c.waiting = waitSet.contains(c.tmuxName);
                    chats.add(c);
                }
            }
            ui.obtainMessage(MSG_CHATS, chats).sendToTarget();
        } catch (Exception e) {
            Log.i("ClaudeDroid", "listChats: " + e);
        }
    }

    /** Заголовок текущего чата из его транскрипта: customTitle важнее aiTitle, берём последние. */
    private void readTitle() {
        String id = uuid;
        if (id == null) return;
        try {
            String[] nl = run("grep '\"aiTitle\"\\|\"customTitle\"' " + PROJ + "/" + id
                    + ".jsonl 2>/dev/null | tail -20").split("\n");
            String custom = null, ai = null;
            for (int i = 0; i < nl.length; i++) {
                String c = extractString(nl[i], "\"customTitle\":\"");
                if (c != null && c.length() > 0) custom = c;
                String a = extractString(nl[i], "\"aiTitle\":\"");
                if (a != null && a.length() > 0) ai = a;
            }
            String title = custom != null ? custom : ai;
            if (title != null)
                ui.obtainMessage(MSG_TITLE, new String[]{id, title}).sendToTarget();
        } catch (Exception e) {
            Log.i("ClaudeDroid", "readTitle: " + e);
        }
    }

    /** Достаём "content":"…" первого typed-сообщения из строки jsonl, с JSON-unescape. */
    static String extractContent(String json) {
        return extractString(json, "\"content\":\"");
    }

    /** Строковое значение по маркеру вида "key":" — с JSON-unescape, до 90 символов. */
    static String extractString(String json, String marker) {
        int m = json.indexOf(marker);
        if (m < 0) return null;
        int i = m + marker.length();
        StringBuilder sb = new StringBuilder();
        while (i < json.length() && sb.length() < 90) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                if (n == 'n' || n == 't') sb.append(' ');
                else if (n == 'u' && i + 5 < json.length()) {
                    try {
                        sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                    } catch (NumberFormatException ignored) {
                    }
                    i += 4;
                } else sb.append(n);
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString().trim();
    }

    /** Слэш-панель: скиллы и кастомные команды с телефона, поверх встроенного списка. */
    private void scanSkills() {
        try {
            String outp = run("ls -1 " + HOME + "/.claude/skills 2>/dev/null; ls -1 " + HOME + "/.claude/commands 2>/dev/null");
            ArrayList<String> names = new ArrayList<String>();
            String[] lines = outp.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i].trim();
                if (s.endsWith(".md")) s = s.substring(0, s.length() - 3);
                if (s.length() > 0 && !s.contains(" ") && !names.contains(s)) names.add(s);
            }
            ui.obtainMessage(MSG_SKILLS, names).sendToTarget();
        } catch (Exception e) {
            Log.i("ClaudeDroid", "scanSkills: " + e);
        }
    }
}
