package helfi2012.server.models

import helfi2012.server.tcpconnection.TCPServer
import java.util.ArrayDeque

class ServerUser(var login: String, internal var password: String) {
    var ipAddress = ""
    var online: Boolean = false
    var iconPath: String = ""
    internal var connection: TCPServer.Connection? = null
    var stringsToSend = ArrayDeque<String>()
    var messagesToSend = ArrayDeque<ChatMessage>()
    var callCompanion: ServerUser? = null

    override fun equals(other: Any?): Boolean {
        val that = other as ServerUser
        return that.login == this.login
    }

    override fun hashCode(): Int {
        var result = login.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + ipAddress.hashCode()
        result = 31 * result + online.hashCode()
        result = 31 * result + iconPath.hashCode()
        result = 31 * result + (connection?.hashCode() ?: 0)
        result = 31 * result + stringsToSend.hashCode()
        result = 31 * result + messagesToSend.hashCode()
        result = 31 * result + (callCompanion?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Login: $login \nOnline: $online \nIpAddress: $ipAddress"
    }
}
