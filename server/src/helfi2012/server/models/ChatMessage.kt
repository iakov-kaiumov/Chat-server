package helfi2012.server.models

import java.util.ArrayList

data class ChatMessage(val name: String, val text: String, val time: Long) {
    var attachments: ArrayList<String> = ArrayList()
    var isRead = false
    override fun toString(): String { return "name: $name | text: $text | time: $time" }
}