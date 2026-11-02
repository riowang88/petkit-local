package io.github.riowang88.nonfc.core

/** Parsers for output of the stripped busybox on these boxes (`patchers/common.py`). */
object Shell {
    /**
     * Available bytes on the filesystem holding `mount`, from `df -k`. Read from the right,
     * because busybox wraps a long filesystem name onto its own line. Null means unknown.
     */
    fun availableBytes(text: String, mount: String): Long? {
        var best: Pair<Int, Long>? = null
        var pending = emptyList<String>()
        for (line in text.lines()) {
            val fields = pending + line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            pending = emptyList()
            if (fields.isEmpty() || fields[0] == "Filesystem" || fields[0] == "df:") continue
            if (fields.size < 6) {
                pending = fields
                continue
            }
            val mountedOn = fields.last()
            if (mount != mountedOn && !mount.startsWith(mountedOn.trimEnd('/') + "/")) continue
            val available = fields[fields.size - 3].toLongOrNull() ?: continue
            if (best == null || mountedOn.length > best.first) {
                best = mountedOn.length to available * 1024
            }
        }
        return best?.second
    }

    /** path -> size from `wc -c`. A missing file is an error line and simply absent. */
    fun sizes(text: String): Map<String, Long> =
        text.lines().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val size = fields.firstOrNull()?.toLongOrNull()
            if (fields.size == 2 && fields[1] != "total" && size != null) fields[1] to size else null
        }.toMap()
}
