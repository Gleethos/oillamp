package dev.oillamp;

/// Whether oillamp may install host packages on this run, and if not, why not.
///
/// The reason matters for the advice given about missing packages. A user who passed
/// `--no-install` should be told to drop it. A user running `doctor`, which never
/// changes anything, should be pointed at `oillamp at`; telling them to drop a flag they
/// never passed is confusing. This used to be a single boolean, and produced exactly that message.
enum Installing {

    /// oillamp may install what is missing; the missing packages become steps in the plan.
    ALLOWED,

    /// The user passed `--no-install`.
    DECLINED,

    /// The command never changes the machine: `doctor` and `config check`. The advice
    /// then points at `oillamp at`, the command that does install.
    NEVER;

    public boolean allowed() {
        return this == ALLOWED;
    }
}
