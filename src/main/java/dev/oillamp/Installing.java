package dev.oillamp;

/**
 * Whether oillamp may install host packages on this run, and if not, why not.
 *
 * <p>The reason matters for the advice given about missing packages. A user who passed
 * {@code --no-install} should be told to drop it. A user running {@code doctor}, which never
 * changes anything, should be pointed at {@code oillamp at}; telling them to drop a flag they
 * never passed is confusing. This used to be a single boolean, and produced exactly that message.
 */
enum Installing {

    /** oillamp may install what is missing; the missing packages become steps in the plan. */
    ALLOWED,

    /** The user passed {@code --no-install}. */
    DECLINED,

    /**
     * The command never changes the machine: {@code doctor} and {@code config check}. The advice
     * then points at {@code oillamp at}, the command that does install.
     */
    NEVER;

    public boolean allowed() {
        return this == ALLOWED;
    }
}
