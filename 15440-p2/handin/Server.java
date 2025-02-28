/**
 * File: Server.java
 * Description: Implements the server-side of the Java RMI Interface
 * Author: Joseph Jia (josephji)
 * 
 * This file implements the server-side of the Java RMI Interface that
 * interacts with the proxy from Proxy.java. 
 */

// Imported Libraries
import java.rmi.*;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Server extends UnicastRemoteObject implements RMIInterface {
    // Global Variables
    public static ConcurrentHashMap<String, ReentrantReadWriteLock> locks;
    public static ConcurrentHashMap<String, Integer> max_versions;
    public String root_dir;
    public static final int CHUNK_SIZE = 50000;

    /* 
     * Function: Server Constructor
     * Creates a server object
     * 
     * @param port - port to communicate with the proxy
     */
    public Server (int port) throws RemoteException {
        super(port);
    }

    /*
     * Function: createFile
     * Creates the file on the server, if it doesn't already exist
     * 
     * @param path - pathname to create the file with
     */
    public void createFile (String path) {
        String serv_path = Path.of(root_dir + "/" + path).normalize().toString();

        // get the lock
        locks.putIfAbsent(serv_path, new ReentrantReadWriteLock());
        ReentrantReadWriteLock lock = locks.get(serv_path);
        lock.writeLock().lock();

        File file = new File(serv_path);
        try {
            if (!file.exists()) {
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                file.createNewFile();
            }
        } catch (IOException e) {
            System.err.println(e.toString());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /*
     * Function: updateFile
     * Updates the file content on the server copy
     * 
     * @param path - pathname of the file to update
     * @param buf - contents to update the file with
     * @param pos - position in the file to start at
     */
    public void updateFile (String path, byte[] buf, long pos) {
        String serv_path = Path.of(root_dir + "/" + path).normalize().toString();

        // get the lock
        locks.putIfAbsent(serv_path, new ReentrantReadWriteLock());
        ReentrantReadWriteLock lock = locks.get(serv_path);
        lock.writeLock().lock();

        File file = new File(serv_path);
        try {
            if (!file.exists()) {
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                file.createNewFile();
            }
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            // clear raf
            byte[] temp = new byte[(int)buf.length];
            raf.seek(pos);
            raf.write(temp);

            // update to most recent close content
            raf.seek(pos);
            raf.write(buf);
            raf.close();

            // update max version value
            int curr_ver = max_versions.get(serv_path);
            max_versions.replace(serv_path, curr_ver + 1);
        } catch (IOException e) {
            System.err.println(e.toString());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /*
     * Function: deleteFile
     * Deletes the file from the server on an unlink call
     * 
     * @param path - pathname of the file to delete
     * @return 0 on success, -1 if the file doesn't exist
     */
    public int deleteFile (String path) {
        String serv_path = Path.of(root_dir + "/" + path).normalize().toString();

        // get the lock
        locks.putIfAbsent(serv_path, new ReentrantReadWriteLock());
        ReentrantReadWriteLock lock = locks.get(serv_path);
        lock.writeLock().lock();

        File file = new File(serv_path);
        try {
            if (file.exists()) {
                file.delete();
                return 0;
            }
            else {
                return -1;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /*
     * Function: getFileLength
     * Gets the length of the file on the server
     * 
     * @param path - pathname of the file
     * @return the length, in bytes, of the file
     */
    public long getFileLength (String path) {
        String serv_path = Path.of(root_dir + "/" + path).normalize().toString();

        // get the lock
        locks.putIfAbsent(serv_path, new ReentrantReadWriteLock());
        ReentrantReadWriteLock lock = locks.get(serv_path);
        lock.readLock().lock();

        File file = new File(serv_path);
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            long length = raf.length();
            raf.close();
            return length;
        } catch (IOException e) {
            System.err.println(e.toString());
            return -5;
        } finally {
            lock.readLock().unlock();
        }
    }

    /*
     * Function: getFileInfo
     * Gets file content from a specfic file on the server
     * 
     * @param path - pathname of the file to get content from
     * @param pos - position of the file to start at
     * @return byte array of content from the file, null on error or if file doesn't exist
     */
    public byte[] getFileInfo (String path, long pos) {
        String serv_path = Path.of(root_dir + "/" + path).normalize().toString();

        // get the lock
        locks.putIfAbsent(serv_path, new ReentrantReadWriteLock());
        ReentrantReadWriteLock lock = locks.get(serv_path);
        lock.readLock().lock();

        File file = new File(serv_path);
        byte[] buf = null;
        try {
            if (!file.exists()) {
                return null;
            }

            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            int buf_size;
            if (raf.length() - pos > CHUNK_SIZE) {
                buf_size = CHUNK_SIZE;
            }
            else {
                buf_size = (int)(raf.length() - pos);
            }
            buf = new byte[buf_size];
            raf.seek(pos);
            raf.read(buf);
            raf.close();
            return buf;
        } catch (IOException e) {
            System.err.println(e.toString());
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /*
     * Function: getMaxVersion
     * Gets the max version number of a file on the server
     * 
     * @param path - pathname of the file
     * @return the max version number of the file
     */
    public int getMaxVersion (String path) {
        String serv_path = Path.of(root_dir + "/" + path).normalize().toString();

        // get the lock
        locks.putIfAbsent(serv_path, new ReentrantReadWriteLock());
        ReentrantReadWriteLock lock = locks.get(serv_path);
        lock.readLock().lock();

        try {
            if (!max_versions.containsKey(serv_path)) {
                max_versions.put(serv_path, 1);
            }
            return max_versions.get(serv_path);
        } finally {
            lock.readLock().unlock();
        }
    }

    /*
     * Function: serverExists
     * Checks if the file exists on the server
     * 
     * @param path - pathname of the file
     * @return the max version number if file exists, 0 otherwise
     */
    public int serverExists (String path) {
        String serv_path = Path.of(root_dir + "/" + path).normalize().toString();

        // get the lock
        locks.putIfAbsent(serv_path, new ReentrantReadWriteLock());
        ReentrantReadWriteLock lock = locks.get(serv_path);
        lock.readLock().lock();

        try {
            File file = new File(serv_path);
            if (file.exists()) {
                return getMaxVersion(path); 
            }
            else {
                return 0;
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /*
     * Function: main
     * Starts the server and sets up communication with proxy
     * 
     * @param args[0] - port value
     * @param args[1] - server root directory
     */
    public static void main (String args[]) {
        locks = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
        max_versions = new ConcurrentHashMap<String, Integer>();
        int port = Integer.parseInt(args[0]);
        try {
            Server serv = new Server(port);
            Registry registry = LocateRegistry.createRegistry(port);
            registry.bind("RMIInterface", serv);
            serv.root_dir = args[1];
        } catch (Exception e) {
            System.err.println(e.toString());
            e.printStackTrace();
        }
    }
}
