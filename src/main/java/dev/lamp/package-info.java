/// What an application needs to use oillamp: the words oillamp speaks in, and a handle on a
/// running lamp.
///
/// oillamp's engine, `dev.oillamp`, runs a sandbox. This package is what the rest of the world
/// sees of it:
///
/// - [dev.lamp.LampEvent]: everything oillamp reports, as values
/// - [dev.lamp.Problem]: what went wrong, with evidence and fixes
/// - [dev.lamp.ExitStatus]: how an oillamp process ended
/// - [dev.lamp.Lamp]: a lamp an application started or joined, until it closes it
///
/// The engine uses these types, and an application that embeds oillamp receives them. So they are
/// here rather than in the engine, and this package never imports `dev.oillamp`: an application
/// that uses it depends on the engine's words, not on the engine. `TheShapeOfTheCodeSpec`
/// enforces that.
///
/// `docs/DECISIONS.md`, "Other applications embed oillamp through `dev.lamp`", says why the engine
/// runs in a process of its own.
package dev.lamp;
