package dev.gui.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/// Days and spans of time in the words the schedule page uses.
public final class Dates {

    private Dates() {}

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    /// "Today", "Tomorrow", "Yesterday", or the day written out, such as "Thursday 2 October".
    public static String day(LocalDate day, LocalDate today) {
        if (day.equals(today)) return "Today";
        if (day.equals(today.plusDays(1))) return "Tomorrow";
        if (day.equals(today.minusDays(1))) return "Yesterday";
        return DAY.format(day);
    }

    /// The same, lower case where it is a word, for the middle of a sentence: "tomorrow",
    /// "Thursday 2 October".
    public static String dayInSentence(LocalDate day, LocalDate today) {
        String said = day(day, today);
        return said.equals("Today") || said.equals("Tomorrow") || said.equals("Yesterday")
                ? said.toLowerCase(Locale.ENGLISH) : "on " + said;
    }

    /// "Thu 2 Oct".
    public static String shortDay(LocalDate day) {
        return SHORT_DAY.format(day);
    }

    /// How far off something is, roughly: "in 5 minutes", "in 3 hours", "in 2 days". Under a
    /// minute is "now".
    public static String fromNow(Duration ahead) {
        long minutes = ahead.toMinutes();
        if (minutes < 1) return "now";
        if (minutes < 60) return "in " + minutes + (minutes == 1 ? " minute" : " minutes");
        long hours = Math.round(minutes / 60.0);
        if (hours < 24) return "in " + hours + (hours == 1 ? " hour" : " hours");
        long days = Math.round(hours / 24.0);
        return "in " + days + (days == 1 ? " day" : " days");
    }

    /// How long ago something was: "just now", "5 minutes ago", "3 hours ago", "2 days ago".
    public static String ago(Duration since) {
        long minutes = since.toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        long hours = Math.round(minutes / 60.0);
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = Math.round(hours / 24.0);
        return days + (days == 1 ? " day ago" : " days ago");
    }
}
