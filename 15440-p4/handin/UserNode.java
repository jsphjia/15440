/* Skeleton code for UserNode */

public class UserNode implements ProjectLib.MessageHandling {
	public final String myId;
	public UserNode (String id) {
		myId = id;
	}

	public boolean deliverMessage (ProjectLib.Message msg) {
		System.out.println(myId + ": Got message from " + msg.addr);
		return true;
	}
	
	public static void main (String args[]) throws Exception {
		if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
		// parse arguments
		int port = Integer.parseInt(args[0]);
		String id = args[1];
		UserNode UN = new UserNode(id);
		ProjectLib PL = new ProjectLib(port, id, UN);
		
		ProjectLib.Message msg = new ProjectLib.Message("Server", "hello".getBytes());
		System.out.println(id + ": Sending message to " + msg.addr);
		PL.sendMessage(msg);
	}
}

