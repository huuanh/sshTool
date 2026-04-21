package com.example.termiusclone.ssh

/** Parsed port-forward spec. Local: bind on phone, traffic goes to remoteHost via SSH server.
 *  Remote: bind on SSH server, traffic comes back to localHost on phone. */
sealed class PortForwardSpec {
    data class Local(
        val localPort: Int,
        val remoteHost: String,
        val remotePort: Int
    ) : PortForwardSpec()

    data class Remote(
        val remotePort: Int,
        val localHost: String,
        val localPort: Int
    ) : PortForwardSpec()
}

object PortForwardParser {
    /** One spec per non-blank line. Format:
     *    L:<localPort>:<remoteHost>:<remotePort>
     *    R:<remotePort>:<localHost>:<localPort>
     *  Returns the parsed list and the indices of unparseable lines.
     */
    fun parse(text: String?): List<PortForwardSpec> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { parseLine(it) }
            .toList()
    }

    private fun parseLine(line: String): PortForwardSpec? {
        val parts = line.split(':')
        if (parts.size != 4) return null
        return runCatching {
            when (parts[0].uppercase()) {
                "L" -> PortForwardSpec.Local(parts[1].toInt(), parts[2], parts[3].toInt())
                "R" -> PortForwardSpec.Remote(parts[1].toInt(), parts[2], parts[3].toInt())
                else -> null
            }
        }.getOrNull()
    }
}
