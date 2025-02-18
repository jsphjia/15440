import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.*;
import java.io.*;

interface RMIInterface extends Remote {
    String sayHello (String name) throws RemoteException;
    void updateFile (String path, byte[] buf) throws RemoteException;
    byte[] getFileInfo (String path) throws RemoteException;
    void createFile (String path) throws RemoteException;
    boolean serverExists (String path) throws RemoteException;
}
