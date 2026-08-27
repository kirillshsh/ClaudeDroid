package sh.kirill.claudedroid;

/** Чат = транскрипт claude (uuid) + возможно живая tmux-сессия. */
public class ChatInfo {
    public String uuid;     // null у легаси-сессии «cc»
    public String tmuxName;
    public String title;
    public long mtime;
    public boolean live;
    public boolean busy; // живая сессия прямо сейчас выполняет команду
    public boolean waiting; // claude остановился на меню и ждёт выбора
}
