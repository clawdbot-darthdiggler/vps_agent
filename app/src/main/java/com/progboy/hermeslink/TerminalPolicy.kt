package com.progboy.hermeslink

/** No provider command guesses. A command and each argument are distinct literal argv entries. */
object TerminalPolicy {
    private fun literal(s: String): String {
        require(s.length <= 4096 && s.none { it.isISOControl() }) { "Control characters or oversized argument" }
        return "'" + s.replace("'", "'\"'\"'") + "'"
    }
    /** Explicit read-only shell builtins; does NOT run a guessed provider --version command. */
    fun locate(container:String, command:String, workdir:String=""):String {
        docker(container,command,emptyList(),workdir) // Same executable/path validation.
        return docker(container,"/bin/sh",listOf("-c","command -v ${literal(command)} && pwd"),workdir)
    }
    fun docker(container: String, command: String = "/bin/sh", args: List<String> = emptyList(), workdir: String = ""): String {
        require(container.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"))) { "Select a valid explicit container" }
        require(command.isNotBlank() && !command.startsWith("-") && command.length <= 1024) { "Enter the exact installed command" }
        require(args.size <= 64) { "Too many arguments" }
        require(workdir.isEmpty() || workdir.startsWith("/")) { "Working directory must be absolute" }
        val directory = if (workdir.isEmpty()) "" else " --workdir ${literal(workdir)}"
        return "docker exec -it$directory -- " + (listOf(container, command) + args).joinToString(" ") { literal(it) }
    }
}
