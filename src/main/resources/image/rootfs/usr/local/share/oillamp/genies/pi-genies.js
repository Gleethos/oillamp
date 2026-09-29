// Lets the Genies app move around in a conversation's tree, which pi's RPC mode cannot do by
// itself: it can show the tree (get_tree), but only an extension's command may move within it.
//
// Genies starts pi with `--extension` pointing here, and sends these commands as prompts:
//
//   /genies-goto <entry id>          continue from that entry
//   /genies-edit <entry id> <text>   ask <text> instead of the user message <entry id>, which
//                                    leaves the old question and its answers as a branch of their own
//
// When pi has moved, a notification says so, which is how Genies knows to ask for the
// conversation again: `genies: moved`, or `genies: could not move: <why>`.

export default function (pi) {
    pi.registerCommand("genies-goto", {
        description: "Continue the conversation from an earlier entry (used by the Genies app)",
        handler: async (args, ctx) => {
            const id = args.trim();
            await move(ctx, id);
        },
    });

    pi.registerCommand("genies-edit", {
        description: "Ask something else instead of an earlier message (used by the Genies app)",
        handler: async (args, ctx) => {
            const space = args.search(/\s/);
            const id = space < 0 ? args : args.slice(0, space);
            const text = space < 0 ? "" : args.slice(space + 1);
            const entry = ctx.sessionManager.getEntry(id);
            if (!entry || entry.type !== "message" || entry.message.role !== "user") {
                ctx.ui.notify("genies: could not move: " + id + " is not a message of the user's", "error");
                return;
            }
            // Going to a user message leaves pi just before it, so what is sent next is its sibling.
            if (await move(ctx, id) && text.trim() !== "") pi.sendUserMessage(text);
        },
    });
}

async function move(ctx, id) {
    try {
        await ctx.waitForIdle();
        const result = await ctx.navigateTree(id, { summarize: false });
        if (result.cancelled) {
            ctx.ui.notify("genies: could not move: an extension said no", "error");
            return false;
        }
        ctx.ui.notify("genies: moved", "info");
        return true;
    } catch (failed) {
        ctx.ui.notify("genies: could not move: " + (failed && failed.message ? failed.message : failed), "error");
        return false;
    }
}
