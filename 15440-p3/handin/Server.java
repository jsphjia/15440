/**
 * File: Server.java
 * Description: Implements the multi-tier scalable web service
 * Author: Joseph Jia (josephji)
 * 
 * This file implements the entire multi-tier scable web service with dynamic scaling
 */

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
	public static final int INITIAL_TIME = 1500;
	public static final int MASTER_PROCESS_TIME = 5000;
	public static final int BETWEEN_TIME = 5000;
	public static final double FRONT_SCALE_UP = 1.75;
	public static final double MIDDLE_SCALE_UP = 1.85;
	public static final double MIDDLE_SCALE_DOWN = 0.5;

	// VM tracking variables
	public static int role;
	public static List<Integer> frontVMs;
	public static List<Integer> middleVMs;
	public static ConcurrentHashMap<Integer, Integer> VMroles;

	// front to middle VM request queue
	public static Queue<Cloud.FrontEndOps.Request> reqs;

	// global variables
	public static boolean scaleDone;
	public static long lastTime;
	public static long initialTime;
	public static long currTime;
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

	/*
	 * Function: main
	 * Main implementation for the scalable web service
	 * @param args[0] - IP value
	 * @param args[1] - port value
	 * @param args[2] - VM ID
	 */
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
			System.out.println(role);
			// register front VMs
			if (role == FRONT) SL.register_frontend();
		}
		
		// get current time
		lastTime = System.currentTimeMillis();
		initialTime = lastTime;
		scaleDone = false;
		int count = 0;
		int currReqLen = 0;
		int prevReqLen = 0;
		int currLen = 0;
		int prevLen = 0;

		// main loop
		while (true) {
			Cloud.FrontEndOps.Request r;
			// role-based execution
			if (role == MASTER) {
				// request load check (after 2 seconds)
				currTime = System.currentTimeMillis();
				elapsedTime = currTime - lastTime;
				
				// initial VM scaling
				if (!scaleDone && elapsedTime > INITIAL_TIME) {
					int queueLen = SL.getQueueLength();
					arrivalRate = (double)(queueLen + count) / Math.round(elapsedTime/1000.0);
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

					// initial middle scaling
					int tmpID;
					for (int i = 1; i < middles; i++) {
						tmpID = SL.startVM();
						middleVMs.add(tmpID);
						VMroles.put(tmpID, MIDDLE);
					}
					
					// initial front scaling
					for (int i = 1; i < fronts; i++) {
						tmpID = SL.startVM();
						frontVMs.add(tmpID);
						VMroles.put(tmpID, FRONT);
					};

					scaleDone = true;
					lastTime = currTime;
					prevReqLen = reqs.size();
					prevLen = queueLen;
				}

				// dynamic scaling cooldown
				if (scaleDone && elapsedTime > BETWEEN_TIME) {
					int tmpID;
					currLen = SL.getQueueLength();
					int frontSize = frontVMs.size() + 1;
					if (prevLen != 0 && (double)currLen > FRONT_SCALE_UP * prevLen) {
						if (frontSize < 2) {
							System.out.println("time: " + System.currentTimeMillis());
							System.out.println("queueLens: " + currLen + "	" + prevLen);
							System.out.println("frontSize: " + frontSize);
							tmpID = SL.startVM();
							frontVMs.add(tmpID);
							VMroles.put(tmpID, FRONT);
						}
					}

					currReqLen = reqs.size();
					int middleSize = middleVMs.size();
					if ((double)currReqLen >= prevReqLen * MIDDLE_SCALE_UP) {
						int numVMs;
						if (middleSize < 10) {
							if (prevReqLen == 0) numVMs = currReqLen / 2;
							else numVMs = (currReqLen / prevReqLen) - 1;
							System.out.println("reqLen: " + currReqLen + "	" + prevReqLen);
							System.out.println("middleSize: " + middleSize);
							for (int i = 0; i < numVMs; i++) {
								tmpID = SL.startVM();
								middleVMs.add(tmpID);
								VMroles.put(tmpID, MIDDLE);
							}
						}
					}
					else if (currReqLen < prevReqLen * MIDDLE_SCALE_DOWN) {
						// should always have 1 middle VM
						if (middleSize > 1) {
							System.out.println("reqLen: " + currReqLen + "	" + prevReqLen);
							tmpID = middleVMs.remove(middleSize - 1);
							System.out.println("ID: " + tmpID);

							// end middle vm from master
							VMroles.remove(tmpID);
							SL.endVM(tmpID);
						}
					}
					lastTime = currTime;
					prevReqLen = currReqLen;
					prevLen = currLen;
				}

				// get next request from clients (act as front VM)
				r = SL.getNextRequest();
				count++;
				// process request (act as middle VM until there is one ready)
				reqs.add(r);
				if (middleVMs.size() == 0 || currTime - initialTime < MASTER_PROCESS_TIME) {
					r = reqs.poll();
					SL.processRequest(r);
				}
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

	/*
	 * Function: getRole
	 * Allows for a VM to get its role from the master VM
	 * @param id - id of the VM
	 * @return role - value of the role of the VM
	 */
	public int getRole (int id) throws RemoteException { return VMroles.get(id); }

	/*
	 * Function: addRequest
	 * Allows for a frontend VM to add a request to the queue for the middle tier
	 * @param r - request to add to the queue
	 */
	public void addRequest (Cloud.FrontEndOps.Request r) throws RemoteException { reqs.add(r); }

	/*
	 * Function: getRequest
	 * Allows for a middle tier VM to get a request to process from the queue
	 * @return r - request from the queue
	 */
	public Cloud.FrontEndOps.Request getRequest () throws RemoteException { return reqs.poll(); }

}
