package dev.gui.view;

import dev.gui.model.Genie;

/// The pictures in Genies, drawn as SVG text so they stay sharp at any size: an oil lamp whose
/// flame shows how its genie is doing.
final class LampSvgUtil {

    private LampSvgUtil() {}

    /// The lamp of a genie in `phase`: lit while it is awake, flaring while it works, a wisp of
    /// smoke while it sleeps, and red when something went wrong.
    static String lamp(Genie.Phase phase) {
        String flame = switch (phase) {
            case READY -> "#f0a940";
            case WORKING -> "#ffd166";
            case WAKING -> "#b8864a";
            case BROKEN -> "#e0645a";
            case ASLEEP -> "none";
        };
        String smoke = phase == Genie.Phase.ASLEEP
                ? "<path d='M33 20 C29 16 36 13 32 8 C30 5 33 3 34 1' fill='none' stroke='#6f6479' stroke-width='2' stroke-linecap='round'/>"
                : "";
        String glow = phase == Genie.Phase.READY || phase == Genie.Phase.WORKING
                ? "<circle cx='33' cy='14' r='12' fill='" + flame + "' opacity='0.22'/>"
                : "";
        return """
            <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'>
              %s
              <path d='M33 4 C38 10 39 15 33 21 C27 15 28 10 33 4 Z' fill='%s'/>
              %s
              <path d='M27 22 h12 a3 3 0 0 1 3 3 v4 h-18 v-4 a3 3 0 0 1 3 -3 Z' fill='#c8963e'/>
              <path d='M6 34 C16 30 26 29 34 29 C43 29 50 31 55 27 C58 24 60 21 62 19
                       C61 28 57 36 49 41 C44 45 39 47 33 47 C23 47 14 43 6 34 Z' fill='#c8963e'/>
              <path d='M14 36 C22 40 30 41 38 40' fill='none' stroke='#e7c27a' stroke-width='2' stroke-linecap='round' opacity='0.7'/>
              <path d='M24 49 h18 a2 2 0 0 1 2 2 v3 h-22 v-3 a2 2 0 0 1 2 -2 Z' fill='#8f6a2a'/>
            </svg>
            """.formatted(glow, flame, smoke);
    }
}
