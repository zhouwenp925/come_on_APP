package cn.shouxin.core

object Versions {
    fun parse(tag: String): List<Int>? {
        val text = tag.removePrefix("v")
        if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(text)) return null
        return text.split('.').map { it.toIntOrNull() ?: return null }
    }
    fun newer(candidate: String, current: String): Boolean {
        val next = parse(candidate) ?: return false
        val old = parse(current.substringBefore('-')) ?: return false
        for (index in 0..2) if (next[index] != old[index]) return next[index] > old[index]
        return false
    }
    fun validRepository(repository: String): Boolean = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository) && !repository.contains("..")
}
