// Lets oillamp continue a conversation from any entry in it, which pi's RPC mode cannot do by
// itself: it can open a conversation (switch_session), but only an extension's command may move
// within one.
//
// oillamp writes this file into the agent's home, as
// ~/.pi/agent/extensions/oillamp-conversations.js, at the start of every session. The session sends
// the command as a prompt, before the question it is asking:
//
//   /oillamp-goto <entry id>
//
// Moving to one of the user's questions leaves pi just before it, so the question asked next is
// asked instead of it. Moving to any other entry, such as an answer, continues after it. When pi
// has moved, a notification says so: `oillamp: moved`, or `oillamp: could not move: <why>`.

export default function (pi) {
    pi.registerCommand("oillamp-goto", {
        description: "Continue the conversation from an earlier entry (used by oillamp)",
        handler: async (args, ctx) => {
            const id = args.trim();
            try {
                await ctx.waitForIdle();
                const result = await ctx.navigateTree(id, { summarize: false });
                if (result.cancelled) ctx.ui.notify("oillamp: could not move: an extension said no", "error");
                else ctx.ui.notify("oillamp: moved", "info");
            } catch (failed) {
                ctx.ui.notify("oillamp: could not move: " + (failed && failed.message ? failed.message : failed), "error");
            }
        },
    });
}
