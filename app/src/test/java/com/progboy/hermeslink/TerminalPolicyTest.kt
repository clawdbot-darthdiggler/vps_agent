package com.progboy.hermeslink

import org.junit.Assert.*
import org.junit.Test

class TerminalPolicyTest {
    @Test fun discoveryDoesNotExecuteTheConfiguredProgram() {
        val command=TerminalPolicy.locate("test-container","/opt/agent x'y", "/work dir")
        assertEquals(TerminalPolicy.docker("test-container","/bin/sh",listOf("-c","command -v '/opt/agent x'\"'\"'y' && pwd"),"/work dir"),command)
        assertTrue(runCatching {TerminalPolicy.locate("test-container","-bad","")}.isFailure)
    }
    @Test fun dockerLaunchQuotesEveryArgumentAndDirectory() {
        assertEquals("docker exec -it --workdir '/work dir' -- 'test-container' '/usr/bin/agent' 'a' 'x'\"'\"'y' '$(touch /tmp/no)'",
            TerminalPolicy.docker("test-container", "/usr/bin/agent", listOf("a", "x'y", "$(touch /tmp/no)"), "/work dir"))
        assertEquals("docker exec -it -- 'test-container' '/bin/sh'", TerminalPolicy.docker("test-container"))
        for (container in listOf("", "-bad", "a;id", "a\nb")) assertTrue(runCatching { TerminalPolicy.docker(container) }.isFailure)
        for (command in listOf("", "-bad", "a\nb", "a\u0000b")) assertTrue(runCatching { TerminalPolicy.docker("test", command) }.isFailure)
        assertTrue(runCatching { TerminalPolicy.docker("test", "agent", listOf("x\rquit")) }.isFailure)
        assertTrue(runCatching { TerminalPolicy.docker("test", "agent", emptyList(), "relative") }.isFailure)
    }
}
