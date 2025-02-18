import java.util.*;
import java.io.*;
import java.nio.file.*;

public class Cache extends LinkedHashMap<String, File> {
    public int max_size;
    public int curr_size;
    public LinkedHashMap<String, File> lru_cache;

    public Cache (int capacity) {
        max_size = capacity;
        curr_size = 0;
        lru_cache = new LinkedHashMap<String, File>(max_size, (float) 0.75, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, File> eldest) {
        return size() > max_size;
    }
}
