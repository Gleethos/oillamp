package dev.oillamp;

/// The `git.identity` setting: whose name and email the agent's commits carry.
///
/// [#GENIE], the default, is oillamp's own: `genie agent <genie@<lamp id>>`. Nothing about the
/// user goes into the sandbox, and each commit says which lamp made it. [#HOST] copies
/// `user.name` and `user.email` from the user's own git configuration at the start of each
/// session, for a user who wants the agent's commits to look like their own. [#CUSTOM] uses
/// `git.name` and `git.email`. [#NONE] gives the agent no identity, and git in the sandbox refuses to commit until
/// one is set there.
enum GitIdentity {
    GENIE, HOST, CUSTOM, NONE;

    public String configName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
