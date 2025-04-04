import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RMIInterface extends Remote {
    public int getRole (int id) throws RemoteException;
    public void addRequest (Cloud.FrontEndOps.Request r) throws RemoteException;
    public Cloud.FrontEndOps.Request getRequest () throws RemoteException;
}
