/**
 * File: Cache.java
 * Description: Implements the proxy cache
 * Author: Joseph Jia (josephji)
 * 
 * This file implements the proxy cache as a LinkedHashMap.
 * It uses an overridden version of removeEldestEntry to 
 * implement LRU eviction.
 */

// Imported Libraries
import java.util.*;
import java.io.*;
import java.util.concurrent.ConcurrentHashMap;

public class Cache extends LinkedHashMap<String, File> {
    // Additional class variables
    public int max_size;
    public int curr_size;
    public Object lock = new Object();
    public ConcurrentHashMap<String, Integer> clients;

    /* 
     * Function: Cache Constructor
     * Creates a cache as a LinkedHashMap
     * 
     * @param capacity - max total file size allowed in the cache
     */
    public Cache (int capacity) {
        super(16, 0.75f, true);
        clients = new ConcurrentHashMap<String, Integer>();
        max_size = capacity;
        curr_size = 0;
    }

    /*
     * Function: addClient
     * Adds 1 client to the client count of a given path
     *  
     * @param path - pathname to add client to
     */
    public void addClient (String path) {
        synchronized (lock) {
            int curr_clients = clients.get(path) + 1;
            clients.replace(path, curr_clients);
        }
    }

    /*
     * Function: removeClient
     * Removes 1 client to the client count of a given path
     * Deletes file if it was a write file
     *  
     * @param path - pathname to remove client from
     * @param isWrite - true if pathname was a write file, false otherwise
     * @param fd - file descriptor used if isWrite is true
     */
    public void removeClient (String path, boolean isWrite, int fd) {
        synchronized (lock) {
            if (isWrite) {
                path = path + "-w" + fd;
            }
            int old_clients = clients.get(path);
            int curr_clients;
            if (old_clients == 0) {
                curr_clients = 0;
            }
            else {
                curr_clients = clients.get(path) - 1;
            }
            clients.replace(path, curr_clients);
            if (isWrite) {
                super.remove(path);
                File f = new File (path);
                curr_size -= f.length();
                f.delete();
                return;
            }
            checkStaleVersions(path);
        }
    }

    /*
     * Function: checkStaleVersions
     * Checks the cache for any stale versions of a file path and removes them
     * 
     * @param p - pathname to check stale versions for
     */
    public void checkStaleVersions (String p) {
        String prefix = p.substring(0, p.lastIndexOf("-"));
        int ver = Integer.parseInt(p.substring(p.lastIndexOf("-") + 1));
        Iterator<Map.Entry<String, File>> it = this.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, File> ent = it.next();
            if (ent.getKey().startsWith(prefix)) {
                String path_end = ent.getKey().substring(p.lastIndexOf("-") + 1);
                if (!path_end.contains("w")) {
                    int ent_ver = Integer.parseInt(path_end);
                    if (ver != ent_ver && clients.get(ent.getKey()) == 0) {
                        File f = ent.getValue();
                        curr_size -= f.length();
                        it.remove(); 
                        f.delete();
                    }
                }
            }
        }
    }

    /*
     * Function: checkEvict
     * Checks if cache eviction is needed and evicts in LRU order
     */
    public void checkEvict () {
        Iterator<Map.Entry<String, File>> it = this.entrySet().iterator();
        while (it.hasNext() && curr_size > max_size) {
            Map.Entry<String, File> ent = it.next();
            if (clients.get(ent.getKey()) == 0) {
                File f = ent.getValue();
                curr_size -= f.length();
                it.remove(); 
                f.delete();
            }
        }
    }

    /*
     * Function: removeEldestEntry
     * Note: I just override the function so that this is never triggered
     * 
     * @param eldest - eldest entry in the cache
     */
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, File> eldest) {
        return false;
    }

    /*
     * Function: put
     * Puts the file into the cache and evicts other files in the cache if needed
     * Prioritizes evicting stale older versions of the file being inserted
     * 
     * @param path - pathname for the file
     * @param file - File object of the pathname
     * @return pervious value associated with the path, if it exists
     */
    @Override
    public File put(String path, File file) {
        synchronized (lock) {
            clients.putIfAbsent(path, 0);
            int file_size = (int) file.length();
            curr_size += file_size; // Update current cache size
            if (!path.substring(path.lastIndexOf("-") + 1).contains("w")) {
                checkStaleVersions(path);
            }
            checkEvict();
            return super.put(path, file);
        }
    }
}
