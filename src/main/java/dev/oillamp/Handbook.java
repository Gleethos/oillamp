package dev.oillamp;


/// The two texts that let someone learn oillamp from oillamp itself: `oillamp about`, which says
/// why it exists and what it is built from, and `oillamp guide`, which walks through a first
/// session step by step.
///
/// `help` lists the commands, which is enough once you know what they are for. These are for the
/// moment before that. They are short on purpose and point to the README for the rest, so keep
/// them true when a command or a default changes.
final class Handbook {

    private Handbook() {}

    static String about(String version) {
        return "oillamp " + version + "\n\n" + """
            WHY IT EXISTS

              An AI coding agent does its best work when it can act like a programmer: install
              packages, run the program, open a browser, look at the screen and click on it.
              On your own computer that puts your files, your SSH keys and your other projects
              within its reach. A virtual machine keeps them out of reach, but is slow to set
              up and heavy to run.

              oillamp gives the agent its own Linux computer, with a graphical desktop. It runs
              on your machine, starts in seconds, and can reach the internet, but it cannot
              reach your machine. The danger it guards against is the agent getting at your
              computer, not the agent reading the web.

              Each sandbox is a directory you choose, called a lamp. Everything belonging to it
              lives there, and one command deletes it again.

            WHAT IT IS BUILT FROM

              Container     podman, rootless: no part of it runs as root on your machine.
                            User namespaces map the sandbox's users onto numbers that own
                            nothing of yours.
              Two users     `agent` is you and the AI. `lamp` runs the desktop, the screen
                            recorder and the connections out, where `agent` cannot touch them.
              The image     Debian 13, built on your machine the first time (about 3.5 GB):
                            a JDK and SDKMAN, Node.js, Python, git, Firefox and the harnesses
                            `pi` and `opencode`.
              Desktop       sway (a Wayland desktop), with Xwayland so X11 and Java Swing
                            applications work. wayvnc shows it in a viewer window on yours.
              Shell         ssh, over a file socket rather than a network port.
              Network       The sandbox has none of its own. Web traffic goes through a proxy
                            inside oillamp that checks every connection against the rules in
                            the lamp's oillamp.toml. By default your home network and this
                            machine are refused, and Eden AI is reached only in the EU.
              oillamp       Java 25, packed with its own Java runtime into one file. Nothing
                            to install; copy the file anywhere.

            READ MORE

              oillamp guide          a first session, step by step
              oillamp help           every command
              README.md              how it all works, for programmers new to containers
            """;
    }

    static String guide() {
        return """
            A FIRST SESSION, STEP BY STEP

            1. Check your machine

                 oillamp doctor

               Changes nothing. It lists everything that would stop a sandbox from starting,
               with the command that fixes each one. If podman is missing, `oillamp at`
               (step 3) installs it, asking for your password once.

            2. Make the agent's model key available (optional)

               The harnesses in the sandbox, pi and opencode, use Eden AI. oillamp passes
               EDENAI_API_KEY into the sandbox if it is set in the terminal you start from:

                 export EDENAI_API_KEY=...

            3. Start a sandbox

                 oillamp at ~/lamps/first

               You choose the directory; oillamp creates it. The first start builds the image
               and takes several minutes. Later starts take seconds. Then two windows open: a
               shell inside the sandbox and a viewer showing its desktop. The terminal you
               typed in keeps showing the sandbox's health until the session ends.

            4. Bring in your project and start the agent

               The sandbox cannot see your files; that is the point. In the sandbox shell,
               clone what you want to work on and start a harness:

                 cd ~/workspace
                 git clone https://github.com/you/your-project.git
                 cd your-project
                 pi                       # or: opencode

               ~/AGENTS.md explains the sandbox to the agent: what lasts, how to drive the
               desktop, and why a request might be refused. If your agent does not read it
               on its own, ask it to.

            5. Watch and help while it works

                 oillamp view   ~/lamps/first    another viewer onto the desktop
                 oillamp shell  ~/lamps/first    another shell, in this terminal
                 oillamp status ~/lamps/first    what the session is doing

               Closing a shell or a viewer never ends the session.

            6. Adjust the sandbox

               Its settings are in ~/lamps/first/oillamp.toml, with comments explaining each
               one: which hosts the agent may reach, extra system packages, screen size,
               recording. Check your edits, then restart the session to apply them:

                 oillamp config ~/lamps/first check

            7. Stop, and come back later

               Press Ctrl-C in the terminal you started from, or run:

                 oillamp stop ~/lamps/first

               The files stay. `oillamp at ~/lamps/first` brings it all back, including
               everything in the agent's home directory. Anything installed outside it (with
               apt, for example) does not survive; list such packages under
               extra_apt_packages in oillamp.toml instead.

            8. Delete it when you are done

                 oillamp remove ~/lamps/first           shows what would be deleted
                 oillamp remove ~/lamps/first --yes     deletes it

               Use this rather than rm -rf: parts of a lamp belong to the sandbox's own users,
               and rm cannot remove them.

            WHEN SOMETHING GOES WRONG

              oillamp doctor ~/lamps/first     checks your machine and that lamp
              oillamp list                     every sandbox running on this machine
              add --verbose to any command     shows every step as it happens

            MORE

              oillamp about                    why oillamp exists, and what it is built from
              oillamp help                     every command and option
              eval "$(oillamp completion bash)"   tab completion in this shell
            """;
    }
}
