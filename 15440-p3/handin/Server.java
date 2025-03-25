import java.util.*;
import java.rmi.*;
import java.rmi.server.*;
import java.rmi.registry.*;
import java.util.concurrent.*;

public class Server extends UnicastRemoteObject implements RMIInterface{
	// global constants
	public static final int MASTER = 0;
	public static final int FRONT = 1;
	public static final int MIDDLE = 2;

	// VM tracking variables
	public static int role;
	public static List<Integer> frontVMs;
	public static List<Integer> middleVMs;
	public static ConcurrentHashMap<Integer, Integer> VMroles;

	// front to middle VM request queue
	public static Queue<Cloud.FrontEndOps.Request> reqs;

	// global variables
	public static boolean scaleDone;
	public static long bootTime;
	public static long elapsedTime;
	public static double arrivalRate;
	public static ServerLib SL;
	public static Server serv;
	public static Registry registry;
	public static RMIInterface stub;
	public static Cloud.CloudOps.VMStatus status;

	protected Server () throws RemoteException { 
		super(); 
	}

	public void scaleUp (int role) {
		// start the new VM
		int newID = SL.startVM();
		VMroles.put(newID, role);

		// add to correct VM list
		if (role == FRONT) frontVMs.add(newID);
		else if (role == MIDDLE) middleVMs.add(newID);
	}

	public void scaleDown (int role) {
		int numVMs, currID;
		int currVM = 0;

		// get number of VMs for given role
		if (role == FRONT) numVMs = frontVMs.size();
		else if (role == MIDDLE) numVMs = middleVMs.size();
		else return;

		while (currVM < numVMs) {
			// get specific VM ID
			if (role == FRONT) currID = frontVMs.get(currVM);
			else if (role == MIDDLE) currID = middleVMs.get(currVM);
			else currID = -1;

			// find a running VM to end
			if (currID != 1 && SL.getStatusVM(currID).equals(status.valueOf("Running"))) {
				// remove VM from lists
				VMroles.remove(currID);
				if (role == FRONT) frontVMs.remove(currID);
				else if (role == MIDDLE) middleVMs.remove(currID);

				// stop VM
				SL.endVM(currID);
			}
		}
	}

	public static void main (String args[]) throws Exception {
		// get command line arguments
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
		String ip = args[0];
		int port = Integer.parseInt(args[1]);
		SL = new ServerLib(ip, port);
		int ID = Integer.parseInt(args[2]);

		// set up master VM
		int mID = -1;
		if (ID == 1) {
			// initialize VM tracking variables
			frontVMs = new ArrayList<Integer>();
			middleVMs = new ArrayList<Integer>();
			VMroles = new ConcurrentHashMap<Integer, Integer>();

			// initialize queue
			reqs = new ConcurrentLinkedQueue<Cloud.FrontEndOps.Request>();

			// set master VM roles
			role = MASTER;
			VMroles.put(ID, MASTER);
			SL.register_frontend();

			// start up server registry
			try {
				serv = new Server();
				registry = LocateRegistry.getRegistry(port);
				// maybe do naming.rebind
				String name = "//" + ip + ":" + port + "/RMIInterface";
				Naming.rebind(name, serv); // rename "//ip:port/rmiinterface"
			} catch (Exception e) {
				System.err.println(e.toString());
				e.printStackTrace();
			}

			// immediately start a middle VM
			mID = SL.startVM();
			middleVMs.add(mID);
			VMroles.put(mID, MIDDLE);
		}
		// set up other VMs
		else {
			// connect to the master
			try {
				registry = LocateRegistry.getRegistry(port);
				String name = "//" + ip + ":" + port + "/RMIInterface";
				stub = (RMIInterface) Naming.lookup(name);
			} catch (RemoteException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (NotBoundException e) {
				e.printStackTrace();
				System.exit(1);
			}

			// get role of VM
			role = stub.getRole(ID);
			// register front VMs
			if (role == FRONT) SL.register_frontend();
		}
		
		// get current time
		bootTime = System.currentTimeMillis();
		scaleDone = false;
		int count = 0;
		// main loop
		while (true) {
			Cloud.FrontEndOps.Request r;
			// role-based execution
			if (role == MASTER) {
				// request load check (after 2 seconds)
				elapsedTime = System.currentTimeMillis() - bootTime;
				if (!scaleDone && elapsedTime > 2000) {
					int queueLen = SL.getQueueLength();
					arrivalRate = (double)(queueLen + count) / Math.round(elapsedTime/1000.0); // find another way to track load value
					int arrivalInt = (int) Math.floor(arrivalRate + 0.45);
					System.out.println("Requests done: " + count);
					System.out.println("Queue length: " + queueLen);
					System.out.println("Elapsed time: " + elapsedTime);
					System.out.println("Measured rate: " + arrivalRate);
					System.out.println("Measured rate (rounded): " + arrivalInt);

					// determine how many VMs to start up for each tier based on arrival rate
					int fronts, middles;
					if (arrivalInt < 1) {
						fronts = 1;
						middles = 1;
					}
					else if (arrivalInt <= 3) {
						fronts = 1;
						middles = arrivalInt + 1;
					}
					else {
						fronts = arrivalInt / 2;
						middles = fronts * 3;
						if (arrivalInt % 2 == 1) middles++;
					}

					System.out.println("Front VMs: " + fronts);
					System.out.println("Middle VMs: " + middles);

					// benchmarking loop
					// int fronts = 1;
					// int middles = 2;
					int tmpID;
					for (int i = 1; i < middles; i++) {
						tmpID = SL.startVM();
						middleVMs.add(tmpID);
						VMroles.put(tmpID, MIDDLE);
					}
					for (int i = 1; i < fronts; i++) {
						tmpID = SL.startVM();
						frontVMs.add(tmpID);
						VMroles.put(tmpID, FRONT);
					};
					scaleDone = true;
				}

				// *** add some drop request mechanism ***
				// if queue is getting too long and VMs haven't booted up yet
				// if (SL.getQueueLength() > 3 * middleVMs.size()) SL.dropRequest(reqs.poll());

				// get next request from clients (act as front VM)
				r = SL.getNextRequest();
				count++;
				// process request (act as middle VM until there is one ready)
				if (middleVMs.size() == 0 || elapsedTime < 5000)
					SL.processRequest(r);
				// otherwise add it to the queue
				else reqs.add(r);

			}
			else if (role == FRONT) {
				// get next request from clients
				r = SL.getNextRequest();
				// send it for middle VMs
				stub.addRequest(r);
			}
			else if (role == MIDDLE) {
				// get next request from front VMs
				r = stub.getRequest();
				// process request
				SL.processRequest(r);
			}
		}
	}

	public int getRole (int id) throws RemoteException { return VMroles.get(id); }

	public void addRequest (Cloud.FrontEndOps.Request r) throws RemoteException { reqs.add(r); }

	public Cloud.FrontEndOps.Request getRequest () throws RemoteException { return reqs.poll(); }

}
