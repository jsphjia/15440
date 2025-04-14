/**
 * File: Server.java
 * Description: Implements the server of a two-phase commit operation
 * Author: Joseph Jia (josephji)
 * 
 * ...
 */
import java.util.*;
import java.io.*;
import java.util.concurrent.*;

public class Server implements ProjectLib.CommitServing {
	/* Global Constants */
	public final int NO = 0;
	public final int YES = 1;
	public final int ABORT = 2;
	public final int COMMIT = 3;

	/* Global Variables */
	public static ProjectLib PL;
	public static ConcurrentHashMap<String, Integer> votes;

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
	 * Function: startCommit
	 * Starts the two-phase commit operation
	 * 
	 * @param filename - name of candidate image file
	 * @param img - byte array of image file contents
	 * @param sources - string array of contributing files
	 */
	public void startCommit (String filename, byte[] img, String[] sources) {
		System.out.println("Server: Got request to commit " + filename);

		// determine source nodes
		Set<String> nodes = new HashSet<>();
		String sourceNode;
		System.out.println("=== GETTING SOURCES FOR " + filename + " ===");
		for (String source : sources) {
			System.out.println(source);
			sourceNode = source.split(":")[0];
			nodes.add(sourceNode);
		}

		ProjectLib.Message msg;
		Message m;
		// send PREPARE messages to all nodes
		for (String node : nodes) {
			// create PREPARE message
			m = new Message(filename, img, sources, Message.mType.PREPARE, false);
			byte[] body = serialize(m);
			msg = new ProjectLib.Message(node, body);

			PL.sendMessage(msg);
		}

		// receive REPLY votes from all nodes
		int votes = 0;
		boolean canCommit = true;
		System.out.println("=== GETTING VOTES FOR " + filename + " ===");
		while (votes < nodes.size()) {
			System.out.println("Server: " + filename + " vals... " + votes + " " + nodes.size());
			// get responses and deserialize
			msg = PL.getMessage();
			m = deserialize(msg.body);
			System.out.println("Server: " + msg.addr + " voted " + m.vote + " for " + m.filename);

			// add to vote count
			if (m.filename.equals(filename)) {
				if (m.type == Message.mType.REPLY) {
					votes++;
					if (!m.vote) canCommit = false;
				}
			}
			else {
				PL.sendMessage(new ProjectLib.Message("Server", msg.body));
			}
		}

		// send DECISION messages to all nodes
		System.out.println("=== SENDING DECISION FOR " + filename + " ===");
		for (String node : nodes) {
			// create DECISION message
			m = new Message(filename, img, sources, Message.mType.DECISION, canCommit);
			byte[] body = serialize(m);
			msg = new ProjectLib.Message(node, body);

			PL.sendMessage(msg);
		}

		// create collage
		if (canCommit) {
			try {
				System.out.println("Server: adding collage to server: " + filename);
				FileOutputStream fos = new FileOutputStream(filename);
				fos.write(img);
				fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	/*
	 * Function: main
	 * Main function to start up a Server
	 * 
	 * @param args[0] - port
	 */
	public static void main (String args[]) throws Exception {
		if (args.length != 1) throw new Exception("Need 1 arg: <port>");
		// parse arguments
		int port = Integer.parseInt(args[0]);
		Server srv = new Server();
		PL = new ProjectLib(port, srv);
		votes = new ConcurrentHashMap<String, Integer>();
	}
}

