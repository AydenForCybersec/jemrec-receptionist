package com.jemcik.jemrec.shell;

/**
 * The answering machine's settings as the app sends them, "on,seconds,route"
 * or, since the AI receptionist, "on,seconds,route,ai": "1,120,music,ai",
 * the seconds being the longest message or the longest call with the
 * assistant, 0 for the old no-limit rule. In the spawn environment
 * (JEMREC_ANSWER) and over the socket (Wire.COMMAND_ANSWER_SETTINGS). Anything
 * unreadable is OFF - the same fail-closed rule as the recording mode: a call
 * answered against the user's choice is worse than one the carrier's voicemail
 * takes.
 */
final class AnswerSettings {

    /** The greeting's routes: see GreetingPlayer. */
    static final String ROUTE_MUSIC = "music";
    static final String ROUTE_SCREENING = "screening";

    /** A message as long as the caller wants - until they are silent for a
     *  while (AnswerMachine.SILENCE_HANGUP_MS). */
    static final int NO_LIMIT = 0;

    /** The assistant takes the call: the AI service speaks, and no greeting
     *  file is played. */
    static final String MODE_AI = "ai";
    static final String MODE_MESSAGE = "message";

    static final AnswerSettings OFF = new AnswerSettings(false, 60, ROUTE_MUSIC, false);

    final boolean on;
    final int maxMessageSeconds;
    final String route;
    final boolean ai;

    AnswerSettings(boolean on, int maxMessageSeconds, String route) {
        this(on, maxMessageSeconds, route, false);
    }

    AnswerSettings(boolean on, int maxMessageSeconds, String route, boolean ai) {
        this.on = on;
        this.maxMessageSeconds = maxMessageSeconds;
        this.route = route;
        this.ai = ai;
    }

    static AnswerSettings parse(String wire) {
        if (wire == null) {
            return OFF;
        }
        String[] parts = wire.trim().split(",");
        if (parts.length != 3 && parts.length != 4) {
            return OFF;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return OFF;
        }
        String route = parts[2].trim();
        if (!route.equals(ROUTE_MUSIC) && !route.equals(ROUTE_SCREENING)) {
            return OFF;
        }
        boolean ai = false;
        if (parts.length == 4) {
            String mode = parts[3].trim();
            if (MODE_AI.equals(mode)) {
                ai = true;
            } else if (!MODE_MESSAGE.equals(mode)) {
                return OFF;
            }
        }
        if (seconds != NO_LIMIT && (seconds < 10 || seconds > 600)) {
            return OFF;
        }
        return new AnswerSettings("1".equals(parts[0].trim()), seconds, route, ai);
    }

    /** Three fields for a message, four for the assistant: an app before this
     *  one reads three, and the message machine's own line is unchanged. */
    String wire() {
        return (on ? "1" : "0") + "," + maxMessageSeconds + "," + route
                + (ai ? "," + MODE_AI : "");
    }
}
