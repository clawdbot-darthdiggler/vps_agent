package com.termux.terminal;

/** VPS Agent remote-only boundary; never starts a local subprocess. */
public interface RemoteSession {
    TerminalEmulator getEmulator();
    void updateSize(int columns, int rows, int cellWidth, int cellHeight);
    void write(String data);
    void writeCodePoint(boolean alt, int codePoint);
    void onCopyTextToClipboard(String text);
    void onPasteTextFromClipboard();
}
