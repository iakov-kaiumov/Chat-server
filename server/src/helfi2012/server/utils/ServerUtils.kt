package helfi2012.server.utils

import helfi2012.server.models.ChatMessage
import helfi2012.server.models.Dialog
import helfi2012.server.models.ServerUser
import helfi2012.server.tcpconnection.JSONKeys
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser

import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.PrintWriter
import java.net.NetworkInterface
import java.util.*

internal object ServerUtils {

    fun getIPAddress(useIPv4: Boolean): String {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (inf in interfaces) {
            val adds = Collections.list(inf.inetAddresses)
            for (add in adds) {
                if (!add.isLoopbackAddress) {
                    val sAdd = add.hostAddress
                    val isIPv4 = sAdd.indexOf(':') < 0

                    if (useIPv4) {
                        if (isIPv4)
                            return sAdd
                    } else {
                        if (!isIPv4) {
                            val delim = sAdd.indexOf('%') // drop ip6 zone suffix
                            return if (delim < 0) sAdd.toUpperCase() else sAdd.substring(0, delim).toUpperCase()
                        }
                    }
                }
            }
        }
        return ""
    }

    fun loadUsers(usersFilePath: String): ArrayList<ServerUser> {
        println("ServerUtils: Users: Loading")
        val users = ArrayList<ServerUser>()
        try {
            val file = File(usersFilePath)
            if (file.exists()) {
                val jsonParser = JSONParser()
                val jsonObject = jsonParser.parse(FileReader(file)) as JSONObject
                val jsonArray = jsonObject[JSONKeys.JSON_KEY_USER_LIST] as JSONArray
                for (item in jsonArray) {
                    val obj = item as JSONObject
                    val user = ServerUser(obj[JSONKeys.JSON_KEY_USER_LOGIN] as String, obj[JSONKeys.JSON_KEY_USER_PASSWORD] as String)
                    user.iconPath = obj[JSONKeys.JSON_KEY_ATTACHMENT_NAME] as String
                    users.add(user)
                }
            } else {
                if (file.createNewFile()) {
                    System.out.println("New file: $usersFilePath created")
                } else {
                    System.err.println("Error while creating new file: " + usersFilePath)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        println("ServerUtils: Users: Done")
        return users
    }

    fun saveUsers(usersFilePath: String, serverUsers: ArrayList<ServerUser>) {
        println("ServerUtils: Users: Saving")
        val mainObject = JSONObject()
        val jsonArray = JSONArray()
        for (user in serverUsers) {
            val userObject = JSONObject()
            userObject.put(JSONKeys.JSON_KEY_USER_LOGIN, user.login)
            userObject.put(JSONKeys.JSON_KEY_USER_PASSWORD, user.password)
            userObject.put(JSONKeys.JSON_KEY_ATTACHMENT_NAME, user.iconPath)
            jsonArray.add(userObject)
        }
        mainObject.put(JSONKeys.JSON_KEY_USER_LIST, jsonArray)

        try {
            val fileOutput = PrintWriter(usersFilePath)
            fileOutput.println(mainObject.toJSONString())
            fileOutput.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        println("ServerUtils: Users: Done")
    }

    fun loadMessages(messagesFilePath: String): ArrayList<Dialog> {
        println("ServerUtils: Messages: Loading")
        val dialogs = ArrayList<Dialog>()
        try {
            val file = File(messagesFilePath)
            if (file.exists()) {
                val jsonParser = JSONParser()
                val mainObject = jsonParser.parse(FileReader(file)) as JSONObject
                val dialogsArray = mainObject[JSONKeys.JSON_KEY_DIALOG_LIST] as JSONArray
                for (dItem in dialogsArray) {
                    val dialogObject = dItem as JSONObject
                    val dialog = Dialog(dialogObject[JSONKeys.JSON_KEY_USER_LOGIN] as String,
                            dialogObject[JSONKeys.JSON_KEY_COMPANION_LOGIN] as String)
                    val messagesArray = dialogObject[JSONKeys.JSON_KEY_MESSAGE_LIST] as JSONArray
                    for (mItem in messagesArray) {
                        val messageObject = mItem as JSONObject
                        val chatMessage = ChatMessage(messageObject[JSONKeys.JSON_KEY_MESSAGE_NAME] as String,
                                messageObject[JSONKeys.JSON_KEY_MESSAGE_TEXT] as String,
                                messageObject[JSONKeys.JSON_KEY_MESSAGE_TIME] as Long)
                        val attachmentsArray = messageObject[JSONKeys.JSON_KEY_MESSAGE_ATTACHMENTS] as JSONArray
                        attachmentsArray.mapTo(chatMessage.attachments) { it as String}
                        dialog.messages.add(chatMessage)
                    }
                    dialog.messages.sortBy { it.time }
                    dialogs.add(dialog)
                }
            } else {
                if (file.createNewFile()) {
                    System.out.println("New file: $messagesFilePath created")
                } else {
                    System.err.println("Error while creating new file: " + messagesFilePath)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        println("ServerUtils: Messages: Done")
        return dialogs
    }

    fun saveMessages(messagesFilePath: String, dialogs: ArrayList<Dialog>) {
        println("ServerUtils: Messages: Saving")
        val mainObject = JSONObject()
        val dialogsArray = JSONArray()
        for (dialog in dialogs) {
            val dialogObject = JSONObject()
            dialogObject.put(JSONKeys.JSON_KEY_USER_LOGIN, dialog.login1)
            dialogObject.put(JSONKeys.JSON_KEY_COMPANION_LOGIN, dialog.login2)
            val messagesArray = JSONArray()
            for (message in dialog.messages) {
                val messageObject = JSONObject()
                val attachmentsArray = JSONArray()
                attachmentsArray.addAll(message.attachments)
                messageObject.put(JSONKeys.JSON_KEY_MESSAGE_NAME, message.name)
                messageObject.put(JSONKeys.JSON_KEY_MESSAGE_TEXT, message.text)
                messageObject.put(JSONKeys.JSON_KEY_MESSAGE_TIME, message.time)
                messageObject.put(JSONKeys.JSON_KEY_MESSAGE_ATTACHMENTS, attachmentsArray)
                messagesArray.add(messageObject)
            }
            dialogObject.put(JSONKeys.JSON_KEY_MESSAGE_LIST, messagesArray)
            dialogsArray.add(dialogObject)
        }
        mainObject.put(JSONKeys.JSON_KEY_DIALOG_LIST, dialogsArray)
        try {
            val fileOutput = PrintWriter(messagesFilePath)
            fileOutput.println(mainObject.toJSONString())
            fileOutput.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        println("ServerUtils: Messages: Done")
    }
}
