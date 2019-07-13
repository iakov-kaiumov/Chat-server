import helfi2012.server.models.Dialog;
import helfi2012.server.models.ServerUser;
import helfi2012.server.utils.ServerUtils;
import helfi2012.server.tcpconnection.TCPServer;

import java.util.ArrayList;
import java.util.Scanner;

public class Server {

	public static void main(String[] args) throws Exception {
	    System.out.println(ServerUtils.INSTANCE.getIPAddress(true));

		TCPServer server = new TCPServer(Constants.PORT, Constants.USERS_FILE_PATH, Constants.MESSAGES_FILE_PATH, Constants.SOCKET_TIMEOUT1,
                Constants.SOCKET_TIMEOUT2, Constants.ATTACHMENTS_PATH);
		Scanner in = new Scanner(System.in);
		while (true) {
			String s = in.nextLine();
			if (s != null) {
				switch (s) {
					case "stop":
						server.closeAll();
						break;
					case "exit":
						in.close();
						System.exit(-1);
					case "dialogs":
						ArrayList<Dialog> dialogs = server.getDialogs();
						System.out.println("Dialogs: ");
						for (Dialog dialog: dialogs) System.out.println(dialog.toString());
						break;
					case "users":
						System.out.println("Users: " + server.getUsersCount());
						for (ServerUser serverUser : server.getUsers()) System.out.println(serverUser.toString());
						break;
					default:
						System.out.println("NO SUCH COMMAND AS: " + s);
						break;
				}
			}
		}
	}
}