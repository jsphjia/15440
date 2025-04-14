/**
 * File: UserNode.java
 * Description: Implements the nodes of a two-phase commit operation
 * Author: Joseph Jia (josephji)
 * 
 * ...
 */

import java.util.*;
import java.io.*;

public class UserNode implements ProjectLib.MessageHandling {
	public final String myId;

	/* Global Variables */
	public static HashSet<String> lockedImgs;
	public static ProjectLib PL;

	/* Global Constants */
	public static final int PREPARE = 0;
	public static final int DECISION = 1;

	/*
	 * Function: UserNode
	 * Additional constructor for UserNode objects
	 * 
	 * @param id - id of the UserNode
	 */
	public UserNode (String id) {
		myId = id;
	}

	public Message deserialize (byte[] msg) {
		try {
			ByteArrayInputStream bais = new ByteArrayInputStream(msg);
			ObjectInputStream ois = new ObjectInputStream(bais);
			return (Message) ois.readObject();
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			return null;
		}
	}

	public byte[] serialize (Message msg) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(baos);
			oos.writeObject(msg);
			return baos.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			return new byte[0];
		}
	}

	/*
	 * Function: deliverMessage
	 * Receive a message to deliver with a getMessage call
	 * 
	 * @param msg - message received
	 * @return false if message not handled, true otherwise
	 */
	public boolean deliverMessage (ProjectLib.Message msg) {
		// System.out.println("deliverMessage request for " + myId);
		Message m = deserialize(msg.body);
		System.out.println(msg.addr);
		ProjectLib.Message sentM;
		Message retM;
		byte[] finalMsg;
		String[] parts;
		switch (m.type) {
			case PREPARE: // prepare message
				System.out.println(myId + ": received PREPARE request for collage " + m.filename);
				boolean voteCommit = true;
				List<String> images = new ArrayList<>();
				// iterate through source images
				for (String source : m.sources) {
					parts = source.split(":");
					// check if source is from this node
					if (parts[0].equals(myId)) {
						images.add(parts[1]);
						// check if source is available
						if (lockedImgs.contains(parts[1])) {
							System.out.println(myId + ": " + parts[1] + " locked");
							voteCommit = false;
						}
						// check if the file exists
						File f = new File(parts[1]);
						if (!f.exists()) {
							System.out.println(myId + ": " + parts[1] + " doesn't exist");
							voteCommit = false;
						}
					}
				}
				
				if (voteCommit) {
					// create array of relevant images for this userNode
					String[] nodeImages = new String[images.size()];
					for (int i = 0; i < images.size(); i++) {
						nodeImages[i] = images.get(i);
					}

					if (PL.askUser(m.img, nodeImages)) {
						// lock all relevant images
						for (String img : images) {
							lockedImgs.add(img);
						}
					}
					else voteCommit = false;
				}

				// create and serialize return message
				retM = new Message(m.filename, m.img, m.sources, Message.mType.REPLY, voteCommit);
				finalMsg = serialize(retM);
				sentM = new ProjectLib.Message(msg.addr, finalMsg);
				PL.sendMessage(sentM);
				System.out.println(myId + ": sent VOTE " + voteCommit + " for collage " + m.filename);
				break;
			case DECISION:
				System.out.println(myId + ": received DECISION request of " + m.vote + " for collage " + m.filename);
				for (String source : m.sources) {
					parts = source.split(":");
					if (parts[0].equals(myId)) {
						// delete relevant sources if commit
						if (m.vote) {
							File imgF = new File (parts[1]);
							if (imgF.exists()) imgF.delete();
						}
						// unlock source image regardless of commit
						lockedImgs.remove(parts[1]);
					}
				}
				// retM = new Message(m.filename, m.img, m.sources, Message.mType.ACK, m.vote);
				// finalMsg = serialize(retM);
				// sentM = new ProjectLib.Message(msg.addr, finalMsg);
				// PL.sendMessage(sentM);
				// System.out.println(myId +  ": sent ACK");
				break;
			default:
				System.out.println(myId + ": Got other message from " + msg.addr);
				break;
		}
		return true;
	}
	
	/*
	 * Function: main
	 * Main function to start up a UserNode
	 * 
	 * @param args[0] - port
	 * @param args[1] - UserNode id
	 */
	public static void main (String args[]) throws Exception {
		if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
		// parse arguments
		int port = Integer.parseInt(args[0]);
		String id = args[1];
		UserNode UN = new UserNode(id);
		lockedImgs = new HashSet<>();
		PL = new ProjectLib(port, id, UN);
	}
}

