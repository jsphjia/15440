import java.rmi.*;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;

public class Server extends UnicastRemoteObject implements RMIInterface {
    public static ConcurrentHashMap<String, File> server_files;
    public String root_dir;

    public Server (int port) throws RemoteException {
        super(port);
    }

    public String sayHello (String name) {
        return "Hello " + name;
    }

    public void updateFile (String path, byte[] buf) {
        String serv_path = root_dir + "/" + path;
        System.err.printf("updateFile: buf length [%d]\n", buf.length);
        if (!server_files.containsKey(serv_path)) {
            System.err.printf("file [%s] not found on server\n", path);
        }
        else {
            File file = server_files.get(serv_path);
            try {
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                raf.seek(0);
                // clear raf
                byte[] temp = new byte[(int)raf.length()];
                raf.write(temp);
                // update to most recent close content
                raf.seek(0);
                raf.write(buf);
                System.err.printf("updateFile: finished updating path [%s]\n", serv_path);
                raf.close();
            } catch (IOException e) {
                System.err.println("updateFile: raf failed.");
            }
        }
    }

    public byte[] getFileInfo (String path) {
        String serv_path = root_dir + "/" + path;
        File check_file = new File(serv_path);
        if (!check_file.exists()) {
            System.err.printf("File [%s] not found on server\n", path);
            return null;
        }

        if (!server_files.containsKey(serv_path)) {
            server_files.put(serv_path, check_file);
        }
        File file = server_files.get(serv_path);
        byte[] buf = null;
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            buf = new byte[(int) raf.length()];
            raf.seek(0);
            raf.read(buf);
            raf.close();
        } catch (IOException e) {
            System.err.println("getFileInfo: raf failed.");
        }
        return buf;
    }

    public void createFile (String path) {
        String serv_path = root_dir + "/" + path;
        File file = new File(serv_path);
        if (server_files.containsKey(serv_path)) {
            System.err.printf("server already created file [%s]\n", path);
            return;
        }
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            server_files.put(serv_path, file);
            System.err.printf("file exists: %b\n", file.exists());
            System.err.printf("file on server: %b\n", server_files.containsKey(serv_path));
        } catch (IOException e) {
            System.err.println("server-side error creating file.");
        }
    }

    // Checks if the file exists on the server
    // Returns true if the file exists, false otherwise
    public boolean serverExists (String path) {
        String serv_path = root_dir + "/" + path;
        File file = new File(serv_path);
        return file.exists();
    }

    public static void main (String args[]) {
        server_files = new ConcurrentHashMap<String, File>();
        int port = Integer.parseInt(args[0]);
        System.err.printf("Server starting with port [%d]\n", port);
        try {
            Server serv = new Server(port);
            Registry registry = LocateRegistry.createRegistry(port);
            registry.bind("RMIInterface", serv);
            serv.root_dir = args[1];
            System.err.println("Server ready.");
        } catch (Exception e) {
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
        }
    }
}
