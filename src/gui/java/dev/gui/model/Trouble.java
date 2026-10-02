package dev.gui.model;

import java.time.Instant;

/// Something that went wrong that Genies did not expect, such as a bug, as the window shows it.
///
/// @param when    when it happened
/// @param where   the thread it happened on, or the library that reported it
/// @param what    the exception and its message, on one line
/// @param details everything the error log holds about it, with the stack trace
public record Trouble(Instant when, String where, String what, String details) {}
