package helfi2012.server.models

class Dialog(val login1: String, val login2: String) {
    val messages = ArrayList<ChatMessage>()

    fun getCompanion(login: String): String? {
        if (login1 == login) return login2
        if (login2 == login) return login1
        return null
    }

    fun contains(login: String): Boolean {
        return login == login1 || login == login2
    }

    override fun equals(other: Any?): Boolean {
        val that = other as Dialog
        return (login1 == that.login1 && login2 == that.login2) || (login1 == that.login2 && login2 == that.login1)
    }

    override fun toString(): String {
        var s = "Dialog between $login1 and $login2 :"
        s += "  Messages: ${messages.size}"
        messages.forEach { s += "\n     " + it.toString() }
        return s
    }

    override fun hashCode(): Int {
        var result = login1.hashCode()
        result = 31 * result + login2.hashCode()
        result = 31 * result + messages.hashCode()
        return result
    }
}