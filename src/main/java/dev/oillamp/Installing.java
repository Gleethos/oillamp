package dev.oillamp;

/**
 * Whether oillamp may install host packages on this run, and if not, why not — spec §11, FR-60.
 *
 * <p>The distinction exists for the sake of the remedy text. "These packages are missing" is the
 * same finding in all three cases, but what the user should do about it is not: someone who passed
 * {@code --no-install} needs to be told to drop it, while someone running {@code doctor} never
 * asked oillamp to change anything and would be baffled to be told to stop passing a flag they
 * never passed. Collapsing the two into one boolean is what produced exactly that message.
 *
 * <p>Deliberately <b>package-private</b>: an internal distinction between two reasons for the same
 * state. Users see its consequence — which remedy they are offered — not the enum.
 */
enum Installing {

    /** oillamp may install what is missing; the missing packages become a plan (FR-01). */
    ALLOWED,

    /** The user declined: {@code --no-install}, or {@code host.auto_install = false} (FR-63). */
    DECLINED,

    /**
     * This command never changes the machine, whatever the configuration says — {@code doctor}
     * and {@code config check}. Not a refusal to be argued with, so the remedy points at the
     * command that <em>does</em> install rather than at a flag.
     */
    NEVER;

    public boolean allowed() {
        return this == ALLOWED;
    }
}
