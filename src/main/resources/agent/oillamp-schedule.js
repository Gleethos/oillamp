// oillamp's scheduling tools for pi.
//
// oillamp writes this file into the agent's home, as ~/.pi/agent/extensions/oillamp-schedule.js,
// at the start of every session while the lamp's schedule is switched on, and removes it when it
// is off. pi loads every extension in that directory.
//
// The tools change no file themselves. Each one sends one request to the session on the host,
// through the socket below, and shows the agent what the host answered. The host decides what is
// allowed: how many jobs the agent may have, how often they may run, and that the user's jobs are
// not the agent's to change. The protocol is one JSON object per line, one request per connection.

import { Type } from "@earendil-works/pi-ai";
import net from "node:net";

const SOCKET = "/oillamp/sockets/host/schedule.sock";

function ask(request, signal) {
    return new Promise((resolve, reject) => {
        const socket = net.createConnection(SOCKET);
        let answer = "";
        socket.setEncoding("utf8");
        socket.setTimeout(20000, () => socket.destroy(new Error("the session did not answer in time")));
        socket.on("connect", () => socket.end(JSON.stringify(request) + "\n"));
        socket.on("data", (chunk) => { answer += chunk; });
        socket.on("end", () => {
            try {
                resolve(JSON.parse(answer.split("\n")[0]));
            } catch {
                reject(new Error("the session answered with something that is not JSON"));
            }
        });
        socket.on("error", (error) => reject(new Error(
            error.code === "ENOENT" || error.code === "ECONNREFUSED"
                ? "oillamp's schedule cannot be reached: the session is ending, or the user switched the schedule off"
                : error.message)));
        if (signal) signal.addEventListener("abort", () => socket.destroy(new Error("stopped")));
    });
}

async function call(request, signal) {
    const reply = await ask(request, signal);
    if (!reply.ok) throw new Error(reply.error || "the session said no, without saying why");
    return { content: [{ type: "text", text: reply.text || "Done." }], details: undefined };
}

export default function (pi) {
    pi.registerTool({
        name: "schedule_add",
        label: "Schedule a job",
        description:
            "Add a job to this sandbox's schedule: oillamp wakes you with its prompt at the time you give, " +
            "in a new conversation, while the user's session runs. Give either `cron` (repeats) or `at` (once). " +
            "Your jobs are limited in number and in how often they run, and they expire; the answer says when.",
        parameters: Type.Object({
            prompt: Type.String({
                description: "What you will be asked when you wake. Write it for yourself in a new conversation, " +
                    "with no memory of this one: say what to do, and where to look.",
            }),
            cron: Type.Optional(Type.String({
                description: "For a job that repeats: a five-field cron expression on the host's clock, " +
                    "such as \"0 9 * * 1-5\" (9:00 on weekdays) or \"*/30 * * * *\".",
            })),
            at: Type.Optional(Type.String({
                description: "For a job that runs once: \"2026-10-01 09:00\" on the host's clock, " +
                    "\"2026-10-01T07:00Z\", or \"in 2h\" (m, h, d or w).",
            })),
            expires: Type.Optional(Type.String({
                description: "When to take the job off the schedule, in the same forms as `at`. " +
                    "Your jobs expire anyway after a number of days the user chose.",
            })),
        }),
        async execute(_toolCallId, params, signal) {
            return call({ op: "add", prompt: params.prompt, cron: params.cron ?? "", at: params.at ?? "",
                          expires: params.expires ?? "" }, signal);
        },
    });

    pi.registerTool({
        name: "schedule_list",
        label: "List the schedule",
        description: "List every job on this sandbox's schedule, yours and the user's, with when each runs next.",
        parameters: Type.Object({}),
        async execute(_toolCallId, _params, signal) {
            return call({ op: "list" }, signal);
        },
    });

    pi.registerTool({
        name: "schedule_remove",
        label: "Remove a job",
        description: "Take one of your own jobs off the schedule. The user's jobs are theirs to remove.",
        parameters: Type.Object({
            id: Type.String({ description: "The job, such as \"job-3\", as schedule_list names it." }),
        }),
        async execute(_toolCallId, params, signal) {
            return call({ op: "remove", id: params.id }, signal);
        },
    });

    pi.registerTool({
        name: "run_history",
        label: "Look at earlier runs",
        description:
            "Look back at earlier times you were woken. Without `run`, lists the recent runs. With a run, " +
            "such as \"run-12\", shows which files it changed and what you said at its end. " +
            "oillamp keeps this history on the host; you cannot change it.",
        parameters: Type.Object({
            run: Type.Optional(Type.String({ description: "The run, such as \"run-12\"." })),
        }),
        async execute(_toolCallId, params, signal) {
            return call({ op: "history", run: params.run ?? "" }, signal);
        },
    });
}
