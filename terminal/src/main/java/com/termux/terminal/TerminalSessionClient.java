// Modified for VPS Agent: remote-neutral session callback types.
package com.termux.terminal;

/**
 * The interface for communication between {@link RemoteSession} and its client. It is used to
 * send callbacks to the client when {@link RemoteSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(RemoteSession changedSession);

    void onTitleChanged(RemoteSession changedSession);

    void onSessionFinished(RemoteSession finishedSession);

    void onCopyTextToClipboard(RemoteSession session, String text);

    void onPasteTextFromClipboard(RemoteSession session);

    void onBell(RemoteSession session);

    void onColorsChanged(RemoteSession session);

    void onTerminalCursorStateChange(boolean state);



    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
