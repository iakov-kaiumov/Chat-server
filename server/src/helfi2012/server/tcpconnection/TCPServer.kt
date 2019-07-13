package helfi2012.server.tcpconnection

import helfi2012.server.encryption.RSAEncryptionUtil
import helfi2012.server.models.ServerUser
import helfi2012.server.utils.ServerUtils
import helfi2012.server.models.ChatMessage
import helfi2012.server.models.Dialog
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.io.*
import java.net.*
import java.util.*
import javax.imageio.ImageIO
import kotlin.collections.ArrayList

@Suppress("DEPRECATION")
class TCPServer(port: Int, private val USERS_FILE_PATH: String, private val MESSAGES_FILE_PATH: String, private val SOCKET_TIMEOUT1: Int,
                private val SOCKET_TIMEOUT2: Int, private val ATTACHMENTS_PATH: String) {

    private val connections = Collections.synchronizedList(ArrayList<Connection>())
    var dialogs = ArrayList<Dialog>()
    var users = ArrayList<ServerUser>()
        private set
    private var server: ServerSocket? = null
    private var socketListener: Thread? = null

    init {
        try {
            users = ServerUtils.loadUsers(USERS_FILE_PATH)
            dialogs = ServerUtils.loadMessages(MESSAGES_FILE_PATH)

            server = ServerSocket(port)

            socketListener = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val socket = server!!.accept()
                        val con = Connection(socket)
                        connections.add(con)
                        con.start()
                    } catch (e: Exception) {
                        //e.printStackTrace();
                    }
                }
                try {
                    server!!.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            socketListener!!.start()
        } catch (e: IOException) {
            e.printStackTrace()
            System.exit(-1)
        } finally {
            println("Server: Create")
        }

    }

    private fun createFilePath(fileName: String): String {
        if (fileName.isEmpty())
            return fileName
        return ATTACHMENTS_PATH + fileName
    }

    private fun getFileName(path: String): String {
        if (path.isEmpty())
            return path
        return path.substring(path.lastIndexOf("/") + 1)
    }

    private fun createShortJSON(key: Any, value: Any): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put(key, value)
        return jsonObject
    }

    val usersCount: Int
        get() = users.size

    fun closeAll() {
        try {
            socketListener!!.interrupt()
            server!!.close()
            for (connection in connections) {
                connection.destroy()
            }
            connections.clear()
        } catch (e: Exception) {
            System.err.println("Потоки не были закрыты!")
        } finally {
            println("Server: Close")
        }
        ServerUtils.saveUsers(USERS_FILE_PATH, users)
        ServerUtils.saveMessages(MESSAGES_FILE_PATH, dialogs)
    }

    private fun sendStringToUser(serverUser: ServerUser, s: String) {
        if (serverUser.online) {
            serverUser.connection!!.printStringToClient(s)
        }
    }

    private fun sendStringToUser(login: String, s: String) {
        val user = users.find { it.login == login } ?: return
        sendStringToUser(user, s)
    }

    private fun sendMessageToUser(recipient: ServerUser, sender: ServerUser, chatMessage: ChatMessage) {
        if (recipient.online) {
            val jsonObject = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_CLIENT_MESSAGE)
            jsonObject.put(JSONKeys.JSON_KEY_MESSAGE_NAME, chatMessage.name)
            jsonObject.put(JSONKeys.JSON_KEY_MESSAGE_TEXT, chatMessage.text)
            jsonObject.put(JSONKeys.JSON_KEY_MESSAGE_TIME, chatMessage.time)
            jsonObject.put(JSONKeys.JSON_KEY_USER_LOGIN, sender.login)
            jsonObject.put(JSONKeys.JSON_KEY_ATTACHMENT_NAME, sender.iconPath)
            val list = JSONArray()
            for (path in chatMessage.attachments) {
                val obj = JSONObject()
                val file = File(path)
                val image = ImageIO.read(file)
                obj.put(JSONKeys.JSON_KEY_ATTACHMENT_NAME, getFileName(path))
                obj.put(JSONKeys.JSON_KEY_ATTACHMENT_RATIO, image.height.toDouble() / image.width.toDouble())
                list.add(obj)
            }
            jsonObject.put(JSONKeys.JSON_KEY_MESSAGE_ATTACHMENTS, list)
            recipient.connection!!.printStringToClient(jsonObject.toJSONString())
            println("			Sending chatMessage to: " + recipient.login + " : " + jsonObject.toJSONString())
        } else {
            recipient.messagesToSend.add(chatMessage)
        }
    }

    inner class Connection(private val socket: Socket) : Thread() {
        private var input: DataInputStream? = null
        private var output: DataOutputStream? = null
        private var userIndex = -1
        private var currentTime = System.currentTimeMillis()
        private val keyPair = RSAEncryptionUtil.generateKeys()

        init {
            try {
                socket.soTimeout = SOCKET_TIMEOUT1
                input = DataInputStream(socket.getInputStream())
                output = DataOutputStream(socket.getOutputStream())
            } catch (e: IOException) {
                e.printStackTrace()
                destroy()
            }
        }

        override fun run() {
            println("Server: New client: " + this.socket.inetAddress.toString())

            onConnect()

            var checkingThread: Thread? = null
            var run = true

            while (run) {
                try {
                    val s = input!!.readUTF()
                    if (s != null) {
                        val inputJsonObject = JSONParser().parse(s) as JSONObject
                        val responseType = inputJsonObject[JSONKeys.JSON_KEY_RESPONSE_TYPE] as String
                        if (responseType != ServerKeys.ON_CONNECTION_CHECK) println(s)
                        when (responseType) {
                            ServerKeys.ON_LOGIN -> {
                                val login = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                //val password =
                                //        RSAEncryptionUtil.decrypt(inputJsonObject[JSONKeys.JSON_KEY_USER_PASSWORD] as String, keyPair.private)
                                val password = inputJsonObject[JSONKeys.JSON_KEY_USER_PASSWORD] as String
                                println("Password: $password")

                                val index = users.indices.find { users[it].login == login && users[it].password == password  }
                                if (index == null) {
                                    printStringToClient(
                                            createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_UNSUCCESSFUL_LOGIN).toJSONString())
                                } else {
                                    userIndex = index
                                    val it = connections.indices.find { connections[it].userIndex != -1 && users[connections[it].userIndex].login == login }
                                    if (it != null) connections.removeAt(it)
                                    printStringToClient(createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_SUCCESSFUL_LOGIN).toJSONString())

                                    val user = users[userIndex]
                                    while (!user.stringsToSend.isEmpty()) printStringToClient(user.stringsToSend.poll())
                                    user.online = true
                                    user.ipAddress = socket.inetAddress.toString().replace("/", "")
                                    user.connection = this
                                    socket.soTimeout = SOCKET_TIMEOUT2
                                    checkingThread = Thread {
                                        while (!Thread.currentThread().isInterrupted) {
                                            if (System.currentTimeMillis() - currentTime > 500) {
                                                printStringToClient(createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE,
                                                        ServerKeys.ON_CONNECTION_CHECK).toJSONString())
                                                currentTime = System.currentTimeMillis()
                                            }
                                        }
                                    }
                                    checkingThread.start()
                                    println("	ServerUser connected: " + login)
                                }
                            }
                            ServerKeys.ON_REGISTER -> {
                                val login = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                val password =
                                        RSAEncryptionUtil.decrypt(inputJsonObject[JSONKeys.JSON_KEY_USER_PASSWORD] as String, keyPair.private)
                                if (users.find { it.login == login } == null) {
                                    users.add(ServerUser(login, password))
                                    printStringToClient(
                                            createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_SUCCESSFUL_REG).toJSONString())
                                } else {
                                    printStringToClient(
                                            createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_UNSUCCESSFUL_REG).toJSONString())
                                }
                            }
                            ServerKeys.ON_ATTACHMENT_SEND -> {
                                val str = inputJsonObject[JSONKeys.JSON_KEY_ATTACHMENT_LENGTH] as String
                                loadFile(inputJsonObject[JSONKeys.JSON_KEY_ATTACHMENT_NAME] as String, str.toInt())
                            }
                            ServerKeys.GET_FILE -> {
                                val fileName = inputJsonObject[JSONKeys.JSON_KEY_ATTACHMENT_NAME] as String
                                val path = createFilePath(fileName)
                                val obj = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.GET_FILE)
                                obj.put(JSONKeys.JSON_KEY_ATTACHMENT_NAME, fileName)
                                obj.put(JSONKeys.JSON_KEY_ATTACHMENT_LENGTH, File(path).length().toString())
                                printStringToClient(obj.toJSONString())
                                sendFile(this, path)
                            }
                            ServerKeys.ON_CHANGE_AVATAR -> {
                                val login = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                val path = inputJsonObject[JSONKeys.JSON_KEY_ATTACHMENT_NAME] as String
                                val serverPath = createFilePath(path)
                                for (user in users) {
                                    if (user.login == login) {
                                        user.iconPath = serverPath
                                        break
                                    }
                                }
                                printStringToClient(inputJsonObject.toJSONString())
                            }
                            ServerKeys.ON_CLIENT_MESSAGE -> {
                                val thisLogin = users[userIndex].login
                                val thatLogin = inputJsonObject[JSONKeys.JSON_KEY_MESSAGE_NAME] as String
                                val thisUser = users[userIndex]
                                val thatUser = users.find { it.login == thatLogin }!!

                                val chatMessage = ChatMessage(users[userIndex].login,
                                        inputJsonObject[JSONKeys.JSON_KEY_MESSAGE_TEXT] as String,
                                        inputJsonObject[JSONKeys.JSON_KEY_MESSAGE_TIME] as Long)
                                val jsonArray = inputJsonObject[JSONKeys.JSON_KEY_MESSAGE_ATTACHMENTS] as JSONArray
                                for (item in jsonArray) {
                                    chatMessage.attachments.add(createFilePath(item as String))
                                }
                                var dialog: Dialog? = Dialog(thisLogin, thatLogin)
                                dialog = dialogs.find {it -> it == dialog }
                                if (dialog != null) {
                                    dialog.messages.add(chatMessage)
                                } else {
                                    dialog = Dialog(thisLogin, thatLogin)
                                    dialog.messages.add(chatMessage)
                                    dialogs.add(dialog)
                                }
                                sendMessageToUser(thisUser, thisUser, chatMessage)
                                for (user in users) {
                                    if (user == thatUser && user != thisUser) {
                                        sendMessageToUser(user, thisUser, chatMessage)
                                        break
                                    }
                                }
                            }
                            ServerKeys.ON_FIND_USER -> {
                                val name = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                val count = inputJsonObject[JSONKeys.JSON_KEY_COUNT] as Long
                                val jsonObject = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_FIND_USER)
                                val jsonArray = JSONArray()

                                val names = ArrayList<String>()
                                users.mapTo(names) { it.login}
                                FuzzySearch.extractTop(name, names, count.toInt()).forEach {
                                    val user = users.find { serverUser: ServerUser -> serverUser.login == it.string}!!
                                    val dialog = dialogs.find { Dialog(users[userIndex].login, user.login) == it }
                                    val userObject = JSONObject()
                                    userObject.put(JSONKeys.JSON_KEY_USER_LOGIN, user.login)
                                    userObject.put(JSONKeys.JSON_KEY_USER_IP_ADDRESS, user.ipAddress)
                                    userObject.put(JSONKeys.JSON_KEY_USER_ONLINE, user.online)
                                    userObject.put(JSONKeys.JSON_KEY_ATTACHMENT_NAME, getFileName(user.iconPath))
                                    if (dialog != null && dialog.messages.isNotEmpty()) {
                                        val message = dialog.messages.last()
                                        userObject.put(JSONKeys.JSON_KEY_MESSAGE_NAME, message.name)
                                        userObject.put(JSONKeys.JSON_KEY_MESSAGE_TEXT, message.text)
                                        userObject.put(JSONKeys.JSON_KEY_MESSAGE_TIME, message.time)
                                    }
                                    jsonArray.add(userObject)
                                }
                                jsonObject.put(JSONKeys.JSON_KEY_USER_LIST, jsonArray)
                                printStringToClient(jsonObject.toJSONString())
                            }
                            ServerKeys.ON_CALL -> {
                                val thisLogin = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                val thatLogin = inputJsonObject[JSONKeys.JSON_KEY_COMPANION_LOGIN] as String
                                val jsonObject = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_CALL)
                                val user = users.find { it.login == thisLogin }!!
                                jsonObject.put(JSONKeys.JSON_KEY_USER_LOGIN, user.login)
                                jsonObject.put(JSONKeys.JSON_KEY_ATTACHMENT_NAME, getFileName(user.iconPath))
                                jsonObject.put(JSONKeys.JSON_KEY_USER_IP_ADDRESS, user.ipAddress)
                                val response = jsonObject.toJSONString()
                                sendStringToUser(thisLogin, response)
                                sendStringToUser(thatLogin, response)

                                val thisUser = users[userIndex]
                                val thatUser = users.find { it.login == thatLogin }!!
                                thisUser.callCompanion = thatUser
                                thatUser.callCompanion = thisUser
                            }
                            ServerKeys.ON_SUCCESSFUL_CALL -> {
                                val thisLogin = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                val thatLogin = inputJsonObject[JSONKeys.JSON_KEY_COMPANION_LOGIN] as String
                                val response = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_SUCCESSFUL_CALL).toJSONString()
                                sendStringToUser(thisLogin, response)
                                sendStringToUser(thatLogin, response)
                            }
                            ServerKeys.ON_UNSUCCESSFUL_CALL -> {
                                val thisLogin = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                val thatLogin = inputJsonObject[JSONKeys.JSON_KEY_COMPANION_LOGIN] as String
                                val response = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_UNSUCCESSFUL_CALL).toJSONString()
                                sendStringToUser(thisLogin, response)
                                sendStringToUser(thatLogin, response)

                                val thisUser = users[userIndex]
                                val thatUser = users.find { it.login == thatLogin }!!
                                thisUser.callCompanion = null
                                thatUser.callCompanion = null
                            }
                            ServerKeys.GET_DIALOG -> {
                                val thisLogin = users[userIndex].login
                                val thatLogin = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                val startIndex = (inputJsonObject[JSONKeys.JSON_KEY_START_INDEX] as Long).toInt()
                                val count = (inputJsonObject[JSONKeys.JSON_KEY_COUNT] as Long).toInt()
                                var dialog: Dialog? = Dialog(thisLogin, thatLogin)
                                dialog = dialogs.find {it -> it == dialog }

                                val jsonObject = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.GET_DIALOG)
                                val messageArray = JSONArray()
                                if (dialog != null) {
                                    val range = maxOf(0, dialog.messages.size - startIndex - count) until dialog.messages.size - startIndex
                                    for (i in range.reversed()) {
                                        val chatMessage = dialog.messages[i]
                                        val messageObject = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_CLIENT_MESSAGE)
                                        messageObject.put(JSONKeys.JSON_KEY_MESSAGE_NAME, chatMessage.name)
                                        messageObject.put(JSONKeys.JSON_KEY_MESSAGE_TEXT, chatMessage.text)
                                        messageObject.put(JSONKeys.JSON_KEY_MESSAGE_TIME, chatMessage.time)
                                        val list = JSONArray()
                                        for (path in chatMessage.attachments) {
                                            val obj = JSONObject()
                                            val file = File(path)
                                            val image = ImageIO.read(file)
                                            obj.put(JSONKeys.JSON_KEY_ATTACHMENT_NAME, getFileName(path))
                                            obj.put(JSONKeys.JSON_KEY_ATTACHMENT_RATIO, image.height.toDouble() / image.width.toDouble())
                                            list.add(obj)
                                        }
                                        messageObject.put(JSONKeys.JSON_KEY_MESSAGE_ATTACHMENTS, list)
                                        messageArray.add(messageObject)
                                    }
                                }
                                jsonObject.put(JSONKeys.JSON_KEY_MESSAGE_LIST, messageArray)
                                printStringToClient(jsonObject.toJSONString())
                            }
                            ServerKeys.GET_DIALOGS -> {
                                val thisLogin = users[userIndex].login
                                val jsonObject = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_FIND_USER)
                                val dialogsArray = JSONArray()
                                dialogs.filter { it.contains(thisLogin) }
                                        .forEach {
                                            val thatLogin = it.getCompanion(thisLogin)
                                            val thatUser = users.find { it.login == thatLogin }
                                            if (thatUser != null) {
                                                val userObject = JSONObject()
                                                userObject.put(JSONKeys.JSON_KEY_USER_LOGIN, thatUser.login)
                                                userObject.put(JSONKeys.JSON_KEY_USER_IP_ADDRESS, thatUser.ipAddress)
                                                userObject.put(JSONKeys.JSON_KEY_USER_ONLINE, thatUser.online)
                                                userObject.put(JSONKeys.JSON_KEY_ATTACHMENT_NAME, getFileName(thatUser.iconPath))
                                                if (it.messages.isNotEmpty()) {
                                                    val message = it.messages.last()
                                                    userObject.put(JSONKeys.JSON_KEY_MESSAGE_NAME, message.name)
                                                    userObject.put(JSONKeys.JSON_KEY_MESSAGE_TEXT, message.text)
                                                    userObject.put(JSONKeys.JSON_KEY_MESSAGE_TIME, message.time)
                                                }
                                                dialogsArray.add(userObject)
                                            }
                                        }
                                jsonObject.put(JSONKeys.JSON_KEY_USER_LIST, dialogsArray)
                                printStringToClient(jsonObject.toJSONString())
                            }
                            ServerKeys.CREATE_DIALOG -> {
                                val thisLogin = users[userIndex].login
                                val thatLogin = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                inputJsonObject.remove(JSONKeys.JSON_KEY_USER_LOGIN)
                                inputJsonObject.put(JSONKeys.JSON_KEY_USER_LOGIN, thisLogin)
                                sendStringToUser(thatLogin, inputJsonObject.toJSONString())
                            }
                            ServerKeys.DELETE_MESSAGES -> {
                                val thisLogin = users[userIndex].login
                                val thatLogin = inputJsonObject[JSONKeys.JSON_KEY_USER_LOGIN] as String
                                val startTime = inputJsonObject[JSONKeys.JSON_KEY_START_INDEX] as Long
                                val count = (inputJsonObject[JSONKeys.JSON_KEY_COUNT] as Long).toInt()
                                var dialog = Dialog(thisLogin, thatLogin)
                                dialog = dialogs.find { it == dialog }!!
                                if (startTime == -1.toLong()) {
                                    dialog.messages.clear()
                                    dialogs.remove(dialog)
                                } else {
                                    val startIndex = dialog.messages.indices.find { dialog.messages[it].time == startTime }!!
                                    (0 until count).forEach { dialog.messages.removeAt(startIndex) }
                                }
                                printStringToClient(inputJsonObject.toJSONString())
                            }
                            ServerKeys.ON_EXIT -> {
                                run = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    break
                }
            }
            if (userIndex != -1) {
                val user = users[userIndex]
                user.online = false
                if (user.callCompanion != null) {
                    user.callCompanion!!.connection!!.printStringToClient(
                            createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_UNSUCCESSFUL_CALL).toJSONString())
                }
                checkingThread!!.interrupt()
                println("	ServerUser left: " + user.login)
            }
            destroy()
        }

        @Suppress("OverridingDeprecatedMember")
        override fun destroy() {
            try {
                Thread.currentThread().interrupt()
                input!!.close()
                output!!.close()
                socket.close()
                connections.remove(this)
                println("Server: Client left")
            } catch (e: Exception) {
                System.err.println("Потоки не были закрыты!")
            }

        }

        private fun onConnect() {
            val keySpec = RSAEncryptionUtil.getPublicKeySpec(keyPair.public)
            val jsonObject = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_SUCCESSFUL_CONNECT)
            jsonObject.put(JSONKeys.JSON_KEY_VALUE1, keySpec.modulus.toString())
            jsonObject.put(JSONKeys.JSON_KEY_VALUE2, keySpec.publicExponent.toString())
            printStringToClient(jsonObject.toJSONString())
        }

        fun printStringToClient(s: String) {
            synchronized(connections) {
                output!!.writeUTF(s)
            }
        }

        private fun loadFile(fileName: String, length: Int) {
            synchronized(socket) {
                try {
                    println("		Start loading file")
                    val path = createFilePath(fileName)
                    val fos = FileOutputStream(path)

                    val bytes = ByteArray(length)
                    var count: Int
                    var total = 0

                    while (true) {
                        count = input!!.read(bytes, 0, length - total)
                        total += count
                        println("			$total   $length")

                        if ((total.toDouble() / length * 100).toInt() % 10 == 0) {
                            val jsonObject = createShortJSON(JSONKeys.JSON_KEY_RESPONSE_TYPE, ServerKeys.ON_ATTACHMENT_SENDING_PROGRESS)
                            jsonObject.put(JSONKeys.JSON_KEY_ATTACHMENT_PROGRESS, total)
                            jsonObject.put(JSONKeys.JSON_KEY_ATTACHMENT_LENGTH, length)
                            printStringToClient(jsonObject.toJSONString())
                        }
                        fos.write(bytes, 0, count)
                        if (total == length) {
                            break
                        }
                    }
                    fos.close()

                    println("		Stop loading file: " + length)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        private fun sendFile(connection: Connection, path: String) {
            try {
                val file = File(path)
                val inF = FileInputStream(file)
                println("				Start sending file, " + file.length() + ", " + file.path)
                var count: Int
                val length = file.length().toInt()
                val bytes = ByteArray(length)
                while (true) {
                    count = inF.read(bytes)
                    if (count <= -1)
                        break
                    connection.output!!.write(bytes, 0, count)
                }
                inF.close()
                println("				Stop sending file")
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}