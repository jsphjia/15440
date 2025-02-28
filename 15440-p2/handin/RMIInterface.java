/**
 * File: RMIInterface.java
 * Description: Defines the RMI interface
 * Author: Joseph Jia (josephji)
 * 
 * This file defines the implemented Java RMI Interface
 * used in Server.java.
 */

// Imported libraries
import java.rmi.Remote;
import java.rmi.RemoteException;

interface RMIInterface extends Remote {
    void createFile (String path) throws RemoteException;
    void updateFile (String path, byte[] buf, long pos) throws RemoteException;
    int deleteFile (String path) throws RemoteException;
    long getFileLength (String path) throws RemoteException;
    byte[] getFileInfo (String path, long pos) throws RemoteException;
    int serverExists (String path) throws RemoteException;
}
